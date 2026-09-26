package com.classroom.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Basic per-process throttle for public authentication endpoints; production ingress should also rate-limit. */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {
    private static final int LIMIT = 10;
    private static final long WINDOW_SECONDS = 60;
    // Hard cap on tracked keys: bounds memory even if the periodic sweep below falls behind a
    // burst of distinct callers (e.g. a spoofed-IP flood), instead of growing unbounded.
    private static final int MAX_TRACKED_WINDOWS = 20000;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final List<String> trustedProxyAddresses;

    public AuthRateLimitFilter(
            @Value("${app.security.trusted-proxies:127.0.0.1,0:0:0:0:0:0:0:1}") String trustedProxies) {
        this.trustedProxyAddresses = Arrays.stream(trustedProxies.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !"/api/v1/auth/login".equals(path) && !"/api/v1/auth/register".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = Instant.now().getEpochSecond();
        String clientAddress = resolveClientAddress(request);
        String key = clientAddress.trim() + ":" + request.getServletPath();
        Window window = windows.compute(key, (ignored, current) -> current == null || now - current.startedAt >= WINDOW_SECONDS
                ? new Window(now, new AtomicInteger()) : current);
        if (window.count.incrementAndGet() > LIMIT) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(Math.max(1, WINDOW_SECONDS - (now - window.startedAt))));
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Quá nhiều yêu cầu xác thực; vui lòng thử lại sau\"}");
            return;
        }
        if (windows.size() > MAX_TRACKED_WINDOWS) {
            Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
            while (iterator.hasNext()) if (now - iterator.next().getValue().startedAt >= WINDOW_SECONDS) iterator.remove();
        }
        chain.doFilter(request, response);
    }

    /**
     * X-Real-IP is only meaningful when it was set by an ingress we control (the Docker Nginx
     * proxy, which always overwrites it with the actual connecting client's address). The
     * backend port can also be reached directly (e.g. the compose-published host port), so an
     * untrusted caller could otherwise forge X-Real-IP to bypass the per-IP limit entirely.
     * Only honor the header when the immediate TCP peer (remoteAddr) is a configured trusted
     * proxy; everything else is keyed by the socket address itself.
     */
    private String resolveClientAddress(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (trustedProxyAddresses.contains(remoteAddr)) {
            String forwarded = request.getHeader("X-Real-IP");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded;
            }
        }
        return remoteAddr;
    }

    private record Window(long startedAt, AtomicInteger count) {}
}
