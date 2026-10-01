package com.classroom.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Shared helpers for the auth rate-limit tests: requests, a controllable clock and a fake "AuthController" chain. */
final class AuthFilterFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private AuthFilterFixtures() {
    }

    /** A settable epoch-second clock. */
    static final class Clock implements java.util.function.LongSupplier {
        private final AtomicLong seconds = new AtomicLong(1_800_000_000L);

        @Override
        public long getAsLong() {
            return seconds.get();
        }

        void advance(long by) {
            seconds.addAndGet(by);
        }
    }

    static AuthRateLimitFilter filter(String trustedProxies, AuthRateLimitProperties properties, Clock clock) {
        return new AuthRateLimitFilter(trustedProxies, host -> new java.net.InetAddress[0], properties, clock);
    }

    static MockHttpServletRequest post(String path, String remoteAddr, String jsonBody) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.setRemoteAddr(remoteAddr);
        if (jsonBody != null) {
            request.setContentType("application/json");
            request.setCharacterEncoding("UTF-8");
            request.setContent(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        return request;
    }

    static MockHttpServletRequest login(String remoteAddr, String email, String password) {
        return post("/api/v1/auth/login", remoteAddr, credentials(email, password));
    }

    static MockHttpServletRequest register(String remoteAddr, String email) {
        return post("/api/v1/auth/register", remoteAddr,
                "{\"email\":\"" + email + "\",\"password\":\"R20Passw0rd!\",\"fullName\":\"Hoc Vien\"}");
    }

    static MockHttpServletRequest sessionCall(String path, String remoteAddr, String refreshCookie) {
        MockHttpServletRequest request = post(path, remoteAddr, null);
        if (refreshCookie != null) {
            request.setCookies(new Cookie("refresh_token", refreshCookie));
        }
        return request;
    }

    static String credentials(String email, String password) {
        try {
            return MAPPER.writeValueAsString(Map.of("email", email, "password", password));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A chain that answers a fixed status. */
    static FilterChain status(int httpStatus) {
        return (request, response) -> ((HttpServletResponse) response).setStatus(httpStatus);
    }

    /**
     * Stands in for AuthController + AuthService: reads the (replayed) JSON body, answers 200 for a known e-mail with the right
     * password and 401 otherwise, and remembers what the "controller" saw so tests can prove the body was not consumed.
     */
    static final class FakeLogin implements FilterChain {
        private final Map<String, String> accounts;
        final AtomicReference<String> lastBodySeen = new AtomicReference<>();
        int invocations;

        FakeLogin(Map<String, String> accounts) {
            this.accounts = accounts;
        }

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) throws IOException {
            invocations++;
            String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            lastBodySeen.set(body);
            JsonNode json = MAPPER.readTree(body);
            String email = json.path("email").asText("").trim().toLowerCase();
            String password = json.path("password").asText("");
            boolean ok = password.equals(accounts.get(email));
            ((HttpServletResponse) response).setStatus(ok ? 200 : 401);
        }
    }

    static MockHttpServletResponse run(AuthRateLimitFilter filter, MockHttpServletRequest request, FilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);
        return response;
    }
}
