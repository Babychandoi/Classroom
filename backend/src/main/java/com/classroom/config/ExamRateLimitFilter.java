package com.classroom.config;

import com.classroom.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PathPattern;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * R13-11(a) (NFR-04/HLD §5): per-user (not per-IP — an exam-taking classroom of students behind
 * the same school NAT/proxy must not share one IP-keyed budget) throttle for the three exam-attempt
 * endpoints a student's browser calls automatically during a live attempt: start attempt, save
 * answers (autosave) and submit.
 *
 * <p>Runs after {@link JwtAuthenticationFilter} (registered later in the chain in
 * {@link SecurityConfig}), so the authenticated principal is already on the SecurityContext; an
 * unauthenticated request is not this filter's concern (JwtAuthenticationFilter already left it
 * anonymous, and the endpoint itself requires authentication).
 *
 * <p>Limits are sized well above ExamAttemptPage's real usage: the frontend autosaves at most once
 * every 500ms after a debounce (see ExamAttemptPage.tsx handleAnswerChange), so a sustained burst
 * of edits tops out around 120/minute — the save-answers budget below is far above that so a normal
 * exam session, including flushAutosave firing right before every submit, never trips it. Start and
 * submit are rare, one-shot actions per attempt, so their budgets are much smaller.
 *
 * <p>R14-10: the budget is keyed on {@code userId + endpoint KIND} (start / save-answers / submit),
 * <em>not</em> on the request path. The path embeds the examId/attemptId, so a path key gave every
 * resource its own fresh budget (a user could dodge the limit by cycling ids) and let random ids
 * grow the tracking map without bound. The tracked-window store is additionally hard-capped: expired
 * windows are swept from the front on every call and, if the cap is still reached, the oldest live
 * windows are evicted (the evicted caller merely gets a fresh window - availability over strictness
 * during an exam).
 */
@Component
public class ExamRateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_SECONDS = 60;
    /** Hard cap on tracked (user, kind) windows; bounds memory regardless of caller behaviour. */
    static final int DEFAULT_MAX_TRACKED_WINDOWS = 20000;
    static final String RATE_LIMITED_MESSAGE = "Quá nhiều yêu cầu làm bài thi; vui lòng thử lại sau ít phút";

    /** The three throttled endpoint kinds and their per-window budgets. */
    enum Kind {
        // Start attempt / submit: rare, deliberate actions; a normal session calls each once (retries
        // on transient failure are the only reason for more than one).
        START(20),
        // Save-answers (autosave): generous — see class javadoc for the 500ms-debounce math.
        SAVE_ANSWERS(300),
        SUBMIT(20);

        final int limit;

        Kind(int limit) {
            this.limit = limit;
        }
    }

    private static final PathPattern START_PATTERN = new PathPatternParser().parse("/api/v1/exams/{examId}/attempts");
    private static final PathPattern ANSWERS_PATTERN = new PathPatternParser().parse("/api/v1/attempts/{attemptId}/answers");
    private static final PathPattern SUBMIT_PATTERN = new PathPatternParser().parse("/api/v1/attempts/{attemptId}/submit");

    private final int maxTrackedWindows;
    private final LongSupplier epochSecondClock;
    /**
     * Insertion-ordered (== window-start-ordered, because entries are only ever appended with a
     * monotonically non-decreasing "now") so expiry/eviction is O(1) amortised from the head.
     * All access is guarded by {@code synchronized (windows)}.
     */
    private final Map<String, Window> windows = new LinkedHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SharedRateLimitStore sharedStore;
    private final java.util.concurrent.ConcurrentHashMap<String, Prepaid> prepaid = new java.util.concurrent.ConcurrentHashMap<>();
    private record Prepaid(int remaining, long deadline, long retrySeconds) {}

    public ExamRateLimitFilter() {
        this(DEFAULT_MAX_TRACKED_WINDOWS, () -> Instant.now().getEpochSecond());
    }

    /** Test seam: custom cap and clock. */
    ExamRateLimitFilter(int maxTrackedWindows, LongSupplier epochSecondClock) {
        if (maxTrackedWindows < 1) {
            throw new IllegalArgumentException("maxTrackedWindows must be >= 1");
        }
        this.maxTrackedWindows = maxTrackedWindows;
        this.epochSecondClock = epochSecondClock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return resolveKind(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Kind kind = resolveKind(request);
        if (kind == null) {
            chain.doFilter(request, response);
            return;
        }

        String userId = currentUserId();
        if (userId == null) {
            // No authenticated principal (should not normally happen for these endpoints — they all
            // require authentication downstream) — let it through; the auth layer rejects it.
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds;
        try { retryAfterSeconds = sharedStore == null ? tryAcquire(userId + ":" + kind.name(), kind.limit)
                : kind == Kind.SAVE_ANSWERS ? acquirePrepaid("exam:" + userId + ":" + kind.name(), kind.limit)
                : sharedStore.acquire("exam:" + userId + ":" + kind.name(), kind.limit); }
        catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException unavailable) {
            response.setHeader("Retry-After", "5");
            response.sendError(503, "Dịch vụ làm bài tạm thời gián đoạn; câu trả lời chưa gửi vẫn được giữ tại trình duyệt.");
            return;
        }
        if (retryAfterSeconds > 0) {
            // R14-09: standard ApiResponse error envelope, explicit UTF-8.
            response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
            ApiErrorResponseWriter.write(request, response, ErrorCode.RATE_LIMITED, RATE_LIMITED_MESSAGE);
            return;
        }
        chain.doFilter(request, response);
    }

    private long acquirePrepaid(String key, int limit) {
        // Eviction wastes prepaid permits; it cannot create a fresh database budget.
        if (!prepaid.containsKey(key) && prepaid.size() >= maxTrackedWindows) {
            var iterator = prepaid.keySet().iterator();
            if (iterator.hasNext()) prepaid.remove(iterator.next());
        }
        long[] retry = {0};
        prepaid.compute(key, (ignored, cached) -> {
            long started = System.nanoTime();
            if (cached != null && started < cached.deadline() && cached.remaining() > 0)
                return new Prepaid(cached.remaining() - 1, cached.deadline(), cached.retrySeconds());
            if (cached != null && started < cached.deadline() && cached.remaining() < 0) {
                retry[0] = cached.retrySeconds();
                return cached;
            }
            var reservation = sharedStore.reserve(key, limit, 8);
            // Start before database IO and subtract a safety margin: local tokens cannot outlive
            // the database window, even when connection acquisition or a row lock takes time.
            long deadline = started + Math.max(0, reservation.lifetimeMillis() - 250) * 1_000_000L;
            if (reservation.permits() == 0) {
                retry[0] = reservation.retrySeconds();
                return new Prepaid(-1, deadline, reservation.retrySeconds());
            }
            if (System.nanoTime() >= deadline) {
                // The database granted the batch, but IO consumed its safe local lifetime.
                // Discard it and authorize THIS request atomically against the current DB
                // window. An expired cache must neither admit old permits nor invent a 429
                // for a learner whose current window still has budget.
                retry[0] = sharedStore.acquire(key, limit);
                return null;
            }
            return new Prepaid(reservation.permits() - 1, deadline, reservation.retrySeconds());
        });
        return retry[0];
    }

    /**
     * Counts one request against {@code key}'s fixed window.
     *
     * @return 0 when the request is within budget, otherwise the (>= 1) number of seconds until the
     *         current window ends.
     */
    private long tryAcquire(String key, int limit) {
        synchronized (windows) {
            long now = epochSecondClock.getAsLong();
            sweepExpired(now);

            Window window = windows.get(key);
            if (window == null || now - window.startedAt >= WINDOW_SECONDS) {
                if (window != null) {
                    // Re-append so the map stays ordered by window start time.
                    windows.remove(key);
                } else {
                    evictOldestToFit();
                }
                window = new Window(now);
                windows.put(key, window);
            }
            window.count++;
            if (window.count > limit) {
                return Math.max(1, WINDOW_SECONDS - (now - window.startedAt));
            }
            return 0;
        }
    }

    /** Drops expired windows from the (oldest-first) head. Must hold the {@code windows} lock. */
    private void sweepExpired(long now) {
        Iterator<Window> iterator = windows.values().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().startedAt < WINDOW_SECONDS) {
                break;
            }
            iterator.remove();
        }
    }

    /** Makes room for one new key by evicting the oldest live windows. Must hold the lock. */
    private void evictOldestToFit() {
        Iterator<Window> iterator = windows.values().iterator();
        while (windows.size() >= maxTrackedWindows && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    /** Number of (user, kind) windows currently tracked; exposed for tests. */
    int trackedWindows() {
        synchronized (windows) {
            return windows.size();
        }
    }

    private Kind resolveKind(HttpServletRequest request) {
        String method = request.getMethod();
        // R20-13: NOT getServletPath() - it is empty under MockMvc / some servlet mappings, which made every exam throttle
        // silently fail open. RequestPaths uses the request URI below the context path (same as AuthRateLimitFilter).
        String path = RequestPaths.withinContext(request);
        org.springframework.http.server.PathContainer container = org.springframework.http.server.PathContainer.parsePath(path);
        if ("POST".equalsIgnoreCase(method) && START_PATTERN.matches(container)) return Kind.START;
        if ("PUT".equalsIgnoreCase(method) && ANSWERS_PATTERN.matches(container)) return Kind.SAVE_ANSWERS;
        if ("POST".equalsIgnoreCase(method) && SUBMIT_PATTERN.matches(container)) return Kind.SUBMIT;
        return null;
    }

    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            return null;
        }
        return principal.getId();
    }

    /** Mutable window state; only touched while holding the {@code windows} lock. */
    private static final class Window {
        final long startedAt;
        int count;

        Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
