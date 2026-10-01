package com.classroom.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * R19-02: lets Spring's same-origin CORS check see the origin the BROWSER used, not the internal hop.
 *
 * <p>The Docker ingress (nginx) terminates the browser connection and forwards {@code X-Forwarded-Host},
 * {@code X-Forwarded-Proto} and {@code X-Forwarded-Port}. {@code CorsUtils.isCorsRequest} compares the
 * {@code Origin} header with {@code request.getScheme()/getServerName()/getServerPort()}; without this filter
 * a same-origin browser POST from any origin other than the one hardcoded in the CORS allow-list (the drill
 * stack on :13000, a custom {@code FRONTEND_PORT}, a LAN IP, a real domain) looked cross-origin and was
 * rejected with 403 "Invalid CORS request".
 *
 * <p>Deliberately NOT {@code server.forward-headers-strategy=framework}/{@code native}: those honour the
 * headers from ANY sender and also rewrite {@code getRemoteAddr()} from {@code X-Forwarded-For}, which would
 * silently defeat the trusted-proxy check that keeps {@link AuthRateLimitFilter} from trusting a forged
 * {@code X-Real-IP} (R18-02). This filter reuses that same trusted-proxy set: the forwarded scheme/host/port
 * are applied only when the immediate TCP peer is a configured trusted proxy, and ONLY those three values
 * are overridden (the remote address and every header stay exactly as received). A request that reaches the
 * backend port directly is evaluated on its own {@code Host} header as before.
 *
 * <p>Runs before Spring Security's filter chain (which contains the CORS filter).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TrustedForwardedHeaderFilter extends OncePerRequestFilter {

    private static final Pattern HOST = Pattern.compile("(?:[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\\[[0-9A-Fa-f:.]{2,45}])");

    private final AuthRateLimitFilter trustedProxies;

    public TrustedForwardedHeaderFilter(AuthRateLimitFilter trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!trustedProxies.isTrustedProxy(request.getRemoteAddr())) {
            chain.doFilter(request, response);
            return;
        }
        String forwardedScheme = parseScheme(request.getHeader("X-Forwarded-Proto"));
        HostAndPort forwardedHost = parseHost(request.getHeader("X-Forwarded-Host"));
        int forwardedPort = parsePort(request.getHeader("X-Forwarded-Port"));
        if (forwardedScheme == null && forwardedHost == null && forwardedPort < 0) {
            chain.doFilter(request, response);
            return;
        }

        String scheme = forwardedScheme != null ? forwardedScheme : request.getScheme();
        String serverName = forwardedHost != null ? forwardedHost.host() : request.getServerName();
        int port;
        if (forwardedHost != null && forwardedHost.port() > 0) {
            port = forwardedHost.port();                       // "host:port" is the most specific statement
        } else if (forwardedPort > 0) {
            port = forwardedPort;
        } else if (forwardedHost != null || (forwardedScheme != null && request.getServerPort() == defaultPort(request.getScheme()))) {
            port = defaultPort(scheme);                        // bare host (or only a scheme change on a default port)
        } else {
            port = request.getServerPort();
        }
        chain.doFilter(new ForwardedRequest(request, scheme, serverName, port), response);
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    /** First comma-separated element, trimmed; null when absent/blank. */
    private static String first(String headerValue) {
        if (headerValue == null) return null;
        int comma = headerValue.indexOf(',');
        String value = (comma >= 0 ? headerValue.substring(0, comma) : headerValue).trim();
        return value.isEmpty() ? null : value;
    }

    static String parseScheme(String headerValue) {
        String value = first(headerValue);
        if (value == null) return null;
        value = value.toLowerCase(Locale.ROOT);
        return "http".equals(value) || "https".equals(value) ? value : null;
    }

    static int parsePort(String headerValue) {
        String value = first(headerValue);
        if (value == null || value.length() > 5 || !value.chars().allMatch(Character::isDigit)) return -1;
        int port = Integer.parseInt(value);
        return port >= 1 && port <= 65535 ? port : -1;
    }

    /** A syntactically valid {@code host[:port]} (IPv6 in brackets), otherwise null: a malformed value is ignored, never trusted. */
    static HostAndPort parseHost(String headerValue) {
        String value = first(headerValue);
        if (value == null) return null;
        String host = value;
        int port = -1;
        int colon = value.lastIndexOf(':');
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) return null;
            host = value.substring(0, close + 1);
            String rest = value.substring(close + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) return null;
                port = parsePort(rest.substring(1));
                if (port < 0) return null;
            }
        } else if (colon >= 0) {
            host = value.substring(0, colon);
            port = parsePort(value.substring(colon + 1));
            if (port < 0) return null;
        }
        return HOST.matcher(host).matches() ? new HostAndPort(host.toLowerCase(Locale.ROOT), port) : null;
    }

    record HostAndPort(String host, int port) {}

    /** Overrides only what a CORS same-origin comparison reads; every header and the remote address are untouched. */
    private static final class ForwardedRequest extends HttpServletRequestWrapper {
        private final String scheme;
        private final String serverName;
        private final int serverPort;

        ForwardedRequest(HttpServletRequest request, String scheme, String serverName, int serverPort) {
            super(request);
            this.scheme = scheme;
            this.serverName = serverName;
            this.serverPort = serverPort;
        }

        @Override
        public String getScheme() {
            return scheme;
        }

        @Override
        public boolean isSecure() {
            return "https".equals(scheme);
        }

        @Override
        public String getServerName() {
            return serverName;
        }

        @Override
        public int getServerPort() {
            return serverPort;
        }
    }
}
