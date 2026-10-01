package com.classroom.config;

import com.classroom.common.ErrorCode;
import com.classroom.modules.identity.controller.AuthController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Per-process throttle for the public authentication endpoints; production ingress should also rate-limit.
 *
 * <p><b>R20-02.</b> The throttle used to be one bucket per (client IP, path): 10 logins/min, 10 registrations/min, 60
 * refreshes/min. Behind a single school NAT that rejected 190 of 200 students and let one abusive user lock everyone out. The
 * budgets are now attached to what they protect - the (IP, account) pair with exponential failure backoff, a generous per-IP
 * ceiling on FAILED logins, per-session refresh windows - see {@link AuthThrottle} for the rules and
 * {@link AuthRateLimitProperties} for the {@code app.security.rate-limit.*} knobs. This class only does the HTTP side: it
 * resolves the caller's address (trusting {@code X-Real-IP} solely from configured proxies), extracts the account from the login
 * body (replaying the body to the controller), asks {@link AuthThrottle}, answers 429, and reports the login outcome afterwards.
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);
    static final String RATE_LIMITED_MESSAGE = "Quá nhiều yêu cầu xác thực; vui lòng thử lại sau";
    static final String BODY_TOO_LARGE_MESSAGE = "Nội dung yêu cầu quá lớn";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REGISTER_PATH = "/api/v1/auth/register";
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";
    private static final String LOGOUT_PATH = "/api/v1/auth/logout";
    /** D-19: {@code GET <prefix>{code}} (preview) and {@code POST <prefix>{code}/join} take an invite code and are throttled per client address. */
    static final String INVITE_PREFIX = "/api/v1/classes/invites/";
    /** A login/register body is a few hundred bytes; anything past this is not a legitimate request (and is not buffered). */
    static final int MAX_BODY_BYTES = 16 * 1024;
    /** Longest client address kept as a key: a forged, oversized X-Real-IP from a trusted proxy must not become an oversized key. */
    private static final int MAX_ADDRESS_KEY_LENGTH = 64;
    /** Account key used when a body carries no usable e-mail (the controller will answer 400 for it anyway). */
    static final String NO_ACCOUNT = "-";
    private static final ObjectMapper BODY_MAPPER = new ObjectMapper();
    // Re-resolve proxy hostnames periodically: the Docker frontend container's bridge-network IP is
    // assigned at container start and is not guaranteed stable across restarts/rescheduling. While a name does
    // not resolve at all - the normal state right after `compose up`, since the frontend starts only after the
    // backend is healthy - retry quickly so X-Real-IP is trusted within seconds, not after a full interval.
    private static final long HOSTNAME_REFRESH_SECONDS = 30;
    private static final long HOSTNAME_RETRY_SECONDS = 5;
    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private AuthThrottle throttle;

    @Autowired(required = false)
    void useSharedThrottle(MySqlAuthThrottle shared) { this.throttle = shared; }
    /** IP-literal entries, normalized once at construction (no DNS involved). */
    private final Set<String> literalAddresses;
    /** Hostname entries; resolved ONLY by {@link #refreshTrustedProxies()}, never on a request thread. */
    private final List<String> proxyHostnames;
    private final HostResolver resolver;
    /**
     * R18-02: the addresses X-Real-IP is trusted from. An immutable snapshot that the request path only
     * ever reads (one volatile read: no DNS lookup, no lock). It starts as the literal entries and is
     * replaced wholesale by the background refresh, so a slow or failing DNS lookup can never stall an
     * authentication request. The previous implementation resolved names lazily on the request thread,
     * inside a synchronized block, once a minute: for a name that does not resolve on the network (the
     * old default listed "nginx") that stalled a login/register/refresh for ~5-9 s every time.
     */
    private volatile Set<String> trustedAddresses;
    /** Hostnames already reported as unresolvable, so a permanently missing name is logged once, not every minute. */
    private final Set<String> unresolvableReported = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService refresher;

    /** Seam for tests: a hostname lookup that is allowed to be slow or to fail. */
    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    @Autowired
    public AuthRateLimitFilter(
            @Value("${app.security.trusted-proxies:127.0.0.1,0:0:0:0:0:0:0:1}") String trustedProxies,
            AuthRateLimitProperties properties) {
        this(trustedProxies, InetAddress::getAllByName, properties, () -> Instant.now().getEpochSecond());
    }

    /** Default limits, real clock: what tests that only care about proxy handling use. */
    AuthRateLimitFilter(String trustedProxies) {
        this(trustedProxies, InetAddress::getAllByName);
    }

    AuthRateLimitFilter(String trustedProxies, HostResolver resolver) {
        this(trustedProxies, resolver, new AuthRateLimitProperties(), () -> Instant.now().getEpochSecond());
    }

    AuthRateLimitFilter(String trustedProxies, HostResolver resolver, AuthRateLimitProperties properties,
                        LongSupplier epochSecondClock) {
        this.resolver = resolver;
        this.throttle = new AuthThrottle(properties, epochSecondClock);
        Set<String> literals = new HashSet<>();
        List<String> hostnames = new ArrayList<>();
        Arrays.stream(trustedProxies.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .forEach(entry -> {
                    String literal = normalizeIpLiteral(entry);
                    if (literal != null) {
                        literals.add(literal);
                    } else {
                        hostnames.add(entry);
                    }
                });
        this.literalAddresses = Set.copyOf(literals);
        this.proxyHostnames = List.copyOf(hostnames);
        this.trustedAddresses = this.literalAddresses;
    }

    /**
     * The canonical textual form of an IPv4/IPv6 literal, or null when {@code entry} is not a literal (i.e.
     * a hostname). Never performs a DNS lookup: IPv4 is validated by hand, and only strings containing ':'
     * (which cannot be hostnames) are handed to {@link InetAddress#getByName}, which parses them as IPv6.
     */
    static String normalizeIpLiteral(String entry) {
        try {
            if (IPV4_LITERAL.matcher(entry).matches()) {
                for (String octet : entry.split("\\.")) {
                    if (Integer.parseInt(octet) > 255) return null;
                }
                return InetAddress.getByName(entry).getHostAddress();
            }
            if (entry.indexOf(':') >= 0) {
                return InetAddress.getByName(entry).getHostAddress();
            }
        } catch (UnknownHostException | NumberFormatException e) {
            return null;
        }
        return null;
    }

    /** Starts the background hostname resolution (immediately, then repeatedly). No-op without hostname entries. */
    @PostConstruct
    void startTrustedProxyRefresh() {
        if (proxyHostnames.isEmpty() || refresher != null) {
            return;
        }
        refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "auth-trusted-proxy-resolver");
            thread.setDaemon(true);
            return thread;
        });
        refresher.schedule(this::refreshAndReschedule, 0, TimeUnit.SECONDS);
    }

    private void refreshAndReschedule() {
        try {
            refreshTrustedProxies();
        } catch (RuntimeException e) {
            log.warn("Refreshing trusted proxy addresses failed: {}", e.getMessage());
        }
        ScheduledExecutorService executor = refresher;
        if (executor == null || executor.isShutdown()) {
            return;
        }
        try {
            executor.schedule(this::refreshAndReschedule, nextRefreshDelaySeconds(), TimeUnit.SECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            // The context is closing; nothing left to refresh.
        }
    }

    /** Quick retries while any trusted hostname is unresolved, the slow steady-state interval otherwise. */
    long nextRefreshDelaySeconds() {
        return unresolvableReported.isEmpty() ? HOSTNAME_REFRESH_SECONDS : HOSTNAME_RETRY_SECONDS;
    }

    @PreDestroy
    void stopTrustedProxyRefresh() {
        if (refresher != null) {
            refresher.shutdownNow();
            refresher = null;
        }
    }

    /**
     * Resolves every trusted-proxy hostname and publishes the result as a new immutable snapshot. Runs on
     * the background thread (and directly in tests) - never on a request thread. A name that does not
     * resolve is skipped and reported once; it is picked up automatically as soon as it starts resolving.
     */
    void refreshTrustedProxies() {
        Set<String> next = new HashSet<>(literalAddresses);
        for (String host : proxyHostnames) {
            try {
                Set<String> hostAddresses = new HashSet<>();
                for (InetAddress address : resolver.resolve(host)) {
                    hostAddresses.add(address.getHostAddress());
                }
                next.addAll(hostAddresses);
                if (unresolvableReported.remove(host)) {
                    log.info("Trusted proxy host '{}' now resolves to {}", host, hostAddresses);
                }
            } catch (UnknownHostException | RuntimeException e) {
                if (unresolvableReported.add(host)) {
                    log.warn("Trusted proxy host '{}' cannot be resolved and is ignored until it resolves "
                            + "(X-Real-IP is not trusted from it meanwhile): {}", host, e.getMessage());
                }
            }
        }
        trustedAddresses = Set.copyOf(next);
    }


    /**
     * The request path below the context path, from the request URI. Deliberately NOT {@code getServletPath()}: that is only the full
     * path when the DispatcherServlet is mapped at "/" and is empty under MockMvc, so a different servlet mapping or a context path would
     * have made {@link #shouldNotFilter} skip the throttle silently (fail open). Shared with {@link ExamRateLimitFilter} (R20-13) through
     * {@link RequestPaths}.
     */
    static String requestPath(HttpServletRequest request) {
        return RequestPaths.withinContext(request);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = requestPath(request);
        // R8-06: /auth/refresh is reachable without a valid access token (it is what re-establishes
        // one), so it needs a throttle against credential-stuffing style abuse of stolen refresh cookies.
        // R19-08: /auth/logout is public too (it must work after the access token expired), so it is throttled
        // like refresh instead of being a free unauthenticated write endpoint.
        return !LOGIN_PATH.equals(path) && !REGISTER_PATH.equals(path)
                && !REFRESH_PATH.equals(path) && !LOGOUT_PATH.equals(path)
                && !path.startsWith(INVITE_PREFIX);
    }

    /** Whether {@code method path} is one of the two invite-code endpoints (GET preview / POST join); anything else under the prefix is not throttled here. */
    static boolean isInviteEndpoint(String method, String path) {
        if (path == null || !path.startsWith(INVITE_PREFIX)) {
            return false;
        }
        String rest = path.substring(INVITE_PREFIX.length());
        if (rest.isEmpty()) {
            return false;
        }
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return "GET".equalsIgnoreCase(method);
        }
        return "/join".equals(rest.substring(slash)) && "POST".equalsIgnoreCase(method);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try { filterRequest(request, response, chain); }
        catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException unavailable) {
            if (response.isCommitted()) throw unavailable;
            response.setHeader("Retry-After", "5");
            response.setStatus(503);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"error\":{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"Dịch vụ xác thực tạm thời gián đoạn. Thử lại sau ít giây.\"}}");
        }
    }

    private void filterRequest(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = requestPath(request);
        if (path.startsWith(INVITE_PREFIX)) {
            if (!isInviteEndpoint(request.getMethod(), path)) {
                chain.doFilter(request, response);
                return;
            }
            String inviteIp = clientKey(resolveClientAddress(request));
            AuthThrottle.Verdict inviteVerdict = throttle.beforeInvite(inviteIp);
            if (!inviteVerdict.allowed()) {
                reject(request, response, inviteVerdict);
                return;
            }
            chain.doFilter(request, response);
            throttle.afterInvite(inviteIp, response.getStatus());
            return;
        }
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response); // nothing to brute-force with a GET/OPTIONS; the controller answers 405
            return;
        }
        String ip = clientKey(resolveClientAddress(request));
        switch (path) {
            case LOGIN_PATH -> filterLogin(request, response, chain, ip);
            case REGISTER_PATH -> filterRegister(request, response, chain, ip);
            default -> filterSessionMaintenance(request, response, chain, ip, path);
        }
    }

    private void filterLogin(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String ip)
            throws ServletException, IOException {
        CachedBodyRequest cached = CachedBodyRequest.read(request);
        if (cached == null) {
            ApiErrorResponseWriter.write(request, response, ErrorCode.BAD_REQUEST, BODY_TOO_LARGE_MESSAGE);
            return;
        }
        String account = accountKey(cached.body());
        AuthThrottle.Verdict verdict = throttle.beforeLogin(ip, account);
        if (!verdict.allowed()) {
            reject(request, response, verdict);
            return;
        }
        chain.doFilter(cached, response);
        // Only the outcome of a request we let through matters; a 401 (bad e-mail OR password) is a failure, a 2xx clears the account.
        throttle.afterLogin(ip, account, response.getStatus());
    }

    private void filterRegister(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String ip)
            throws ServletException, IOException {
        CachedBodyRequest cached = CachedBodyRequest.read(request);
        if (cached == null) {
            ApiErrorResponseWriter.write(request, response, ErrorCode.BAD_REQUEST, BODY_TOO_LARGE_MESSAGE);
            return;
        }
        String account = accountKey(cached.body());
        AuthThrottle.Verdict verdict = throttle.beforeRegister(ip, NO_ACCOUNT.equals(account) ? null : account);
        if (!verdict.allowed()) {
            reject(request, response, verdict);
            return;
        }
        chain.doFilter(cached, response);
    }

    private void filterSessionMaintenance(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                                          String ip, String path) throws ServletException, IOException {
        String sessionKey = null;
        String rawCookie = refreshCookie(request);
        if (rawCookie != null && !rawCookie.isEmpty()) {
            sessionKey = digest(rawCookie);
        }
        AuthThrottle.Verdict verdict = throttle.beforeSession(path, ip, sessionKey);
        if (!verdict.allowed()) {
            reject(request, response, verdict);
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, AuthThrottle.Verdict verdict) throws IOException {
        // R14-09: standard ApiResponse error envelope, explicit UTF-8 (the previous bare
        // "application/json" was served as ISO-8859-1 and garbled the Vietnamese message).
        response.setHeader("Retry-After", Long.toString(verdict.retryAfterSeconds()));
        ApiErrorResponseWriter.write(request, response, ErrorCode.RATE_LIMITED, RATE_LIMITED_MESSAGE);
    }

    private static String refreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (AuthController.REFRESH_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * The throttle key of the account a login/register body names: a SHA-256 of the trimmed, lower-cased e-mail (never the
     * address itself, and a fixed length however long the input), or {@link #NO_ACCOUNT} when the body has none. It depends
     * only on the string the caller sent - not on whether such an account exists.
     */
    static String accountKey(byte[] body) {
        try {
            JsonNode root = BODY_MAPPER.readTree(body);
            JsonNode email = root == null ? null : root.get("email");
            if (email != null && email.isTextual()) {
                String normalized = email.asText().trim().toLowerCase(Locale.ROOT);
                if (!normalized.isEmpty()) {
                    return digest(normalized);
                }
            }
        } catch (IOException | RuntimeException notJson) {
            // unparseable body: the controller answers 400; it shares the caller's "no account" bucket
        }
        return NO_ACCOUNT;
    }

    /** 128 bits of SHA-256 as hex: a fixed-size, non-reversible key. */
    static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16)).append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    private static String clientKey(String address) {
        String trimmed = address == null ? "" : address.trim();
        return trimmed.length() > MAX_ADDRESS_KEY_LENGTH ? trimmed.substring(0, MAX_ADDRESS_KEY_LENGTH) : trimmed;
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
        if (isTrustedProxy(remoteAddr)) {
            String forwarded = request.getHeader("X-Real-IP");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded;
            }
        }
        return remoteAddr;
    }

    /**
     * Whether the immediate TCP peer is a configured trusted proxy. Shared with
     * {@link TrustedForwardedHeaderFilter} (R19-02) so X-Real-IP and X-Forwarded-Host/Proto/Port are trusted
     * from exactly the same peers.
     */
    boolean isTrustedProxy(String remoteAddr) {
        return remoteAddr != null && trustedAddresses.contains(remoteAddr);
    }

    /** Entries currently tracked by the throttle (all limiters together); exposed for the boundedness tests. */
    int trackedKeys() {
        return throttle.trackedKeys();
    }

    /**
     * A request whose (small) body has been read once so both this filter and the controller can read it. Bodies over
     * {@link #MAX_BODY_BYTES} are refused by {@link #read} rather than buffered.
     */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        /** The buffered request, or null when the body is larger than the limit. */
        static CachedBodyRequest read(HttpServletRequest request) throws IOException {
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            return body.length > MAX_BODY_BYTES ? null : new CachedBodyRequest(request, body);
        }

        byte[] body() {
            return body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return source.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("asynchronous reads are not supported on a buffered body");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = StandardCharsets.UTF_8;
            if (encoding != null) {
                try {
                    charset = Charset.forName(encoding);
                } catch (RuntimeException unknownCharset) {
                    // keep UTF-8: the controller would fail on the same header
                }
            }
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
