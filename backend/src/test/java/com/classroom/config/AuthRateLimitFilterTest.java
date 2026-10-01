package com.classroom.config;

import com.classroom.config.AuthFilterFixtures.Clock;
import com.classroom.config.AuthFilterFixtures.FakeLogin;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.classroom.config.AuthFilterFixtures.MAPPER;
import static com.classroom.config.AuthFilterFixtures.filter;
import static com.classroom.config.AuthFilterFixtures.login;
import static com.classroom.config.AuthFilterFixtures.post;
import static com.classroom.config.AuthFilterFixtures.register;
import static com.classroom.config.AuthFilterFixtures.run;
import static com.classroom.config.AuthFilterFixtures.sessionCall;
import static com.classroom.config.AuthFilterFixtures.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Servlet-level behaviour of the auth throttle: which address is trusted, the 429 envelope, and (R20-02) that budgets are attached
 * to the (IP, account) pair, the failed-login stream and the refresh session instead of the source address alone. The pure decision
 * rules are covered in {@link AuthThrottleTest}; the multi-user NAT/brute-force/credential-stuffing scenarios in
 * {@link AuthRateLimitScenarioTest}.
 */
class AuthRateLimitFilterTest {

    private static final int PAIR_LIMIT = 10;
    private static final int SESSION_LIMIT = 60;

    private static final String LOGIN = "/api/v1/auth/login";

    @Test
    @DisplayName("R1-05: an untrusted socket cannot forge X-Real-IP to get a fresh budget (each forged value would otherwise be its own bucket)")
    void untrustedRemoteAddressCannotForgeXRealIpToBypassTheLimit() throws Exception {
        AuthRateLimitFilter filter = filter("", new AuthRateLimitProperties(), new Clock());

        for (int i = 0; i < PAIR_LIMIT; i++) {
            MockHttpServletRequest request = login("203.0.113.7", "student@example.com", "pw");
            request.addHeader("X-Real-IP", "1.1.1." + i); // attacker-controlled, must be ignored
            assertEquals(200, run(filter, request, status(200)).getStatus());
        }

        MockHttpServletRequest blocked = login("203.0.113.7", "student@example.com", "pw");
        blocked.addHeader("X-Real-IP", "9.9.9.9");
        assertEquals(429, run(filter, blocked, status(200)).getStatus());
    }

    @Test
    @DisplayName("R1-05: behind a trusted proxy each real client (X-Real-IP) has its own budget, not the proxy's address")
    void trustedProxyAddressStillHonorsXRealIp() throws Exception {
        AuthRateLimitFilter filter = filter("203.0.113.7", new AuthRateLimitProperties(), new Clock());

        for (int i = 0; i < PAIR_LIMIT; i++) {
            MockHttpServletRequest request = login("203.0.113.7", "student@example.com", "pw");
            request.addHeader("X-Real-IP", "10.0.0.1");
            assertEquals(200, run(filter, request, status(200)).getStatus());
        }

        MockHttpServletRequest anotherClient = login("203.0.113.7", "student@example.com", "pw");
        anotherClient.addHeader("X-Real-IP", "10.0.0.2");
        assertEquals(200, run(filter, anotherClient, status(200)).getStatus());

        MockHttpServletRequest sameClientAgain = login("203.0.113.7", "student@example.com", "pw");
        sameClientAgain.addHeader("X-Real-IP", "10.0.0.1");
        assertEquals(429, run(filter, sameClientAgain, status(200)).getStatus());
    }

    @Test
    @DisplayName("R20-02: login is limited per (IP, account): the same account is capped at 10/min, other accounts from the same IP are not affected")
    void loginBudgetIsPerAccountNotPerIp() throws Exception {
        AuthRateLimitFilter filter = filter("", new AuthRateLimitProperties(), new Clock());

        for (int i = 0; i < PAIR_LIMIT; i++) {
            assertEquals(200, run(filter, login("203.0.113.10", "a@example.com", "pw"), status(200)).getStatus());
        }
        assertEquals(429, run(filter, login("203.0.113.10", "a@example.com", "pw"), status(200)).getStatus());

        // The same address keeps serving every other account.
        for (int i = 0; i < 50; i++) {
            assertEquals(200, run(filter, login("203.0.113.10", "other" + i + "@example.com", "pw"), status(200)).getStatus());
        }
        // ... and the exhausted account is only exhausted for THIS address.
        assertEquals(200, run(filter, login("203.0.113.11", "a@example.com", "pw"), status(200)).getStatus());
    }

    @Test
    @DisplayName("R20-02: the e-mail is normalised (case, surrounding whitespace) so changing its spelling does not buy a fresh budget")
    void emailSpellingDoesNotBuyAFreshBudget() throws Exception {
        AuthRateLimitFilter filter = filter("", new AuthRateLimitProperties(), new Clock());
        String[] spellings = {"a@example.com", "A@Example.com", "  a@example.com ", "A@EXAMPLE.COM", "a@example.com",
                "a@example.com", "A@example.com", "a@Example.com", "a@example.com", "a@example.com"};
        for (String spelling : spellings) {
            assertEquals(200, run(filter, login("203.0.113.12", spelling, "pw"), status(200)).getStatus());
        }
        assertEquals(429, run(filter, login("203.0.113.12", "a@EXAMPLE.com", "pw"), status(200)).getStatus());
    }

    @Test
    @DisplayName("R9-05/R20-02: refresh is limited per session (refresh cookie) and per IP, with far higher budgets than login")
    void refreshIsLimitedPerSessionWithAHighIpCeiling() throws Exception {
        AuthRateLimitFilter filter = filter("", new AuthRateLimitProperties(), new Clock());
        String path = "/api/v1/auth/refresh";

        for (int i = 0; i < SESSION_LIMIT; i++) {
            assertEquals(200, run(filter, sessionCall(path, "203.0.113.9", "cookie-A"), status(200)).getStatus(),
                    "refresh #" + (i + 1) + " of one session should not be limited");
        }
        assertEquals(429, run(filter, sessionCall(path, "203.0.113.9", "cookie-A"), status(200)).getStatus());

        // Another session behind the same NAT is untouched.
        assertEquals(200, run(filter, sessionCall(path, "203.0.113.9", "cookie-B"), status(200)).getStatus());
    }

    @Test
    @DisplayName("R20-02: many sessions from one NAT are limited only by the high per-IP refresh ceiling (600/min by default)")
    void refreshPerIpCeilingIsHighEnoughForAClass() throws Exception {
        AuthRateLimitFilter filter = filter("", new AuthRateLimitProperties(), new Clock());
        String path = "/api/v1/auth/refresh";

        for (int i = 0; i < 600; i++) {
            assertEquals(200, run(filter, sessionCall(path, "203.0.113.13", "session-" + i), status(200)).getStatus(),
                    "session #" + i);
        }
        assertEquals(429, run(filter, sessionCall(path, "203.0.113.13", "session-600"), status(200)).getStatus());
    }

    @Test
    @DisplayName("R20-02: a refresh without a cookie only counts against the per-IP ceiling")
    void cookielessRefreshFallsBackToTheIpCeiling() throws Exception {
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        properties.getRefresh().setPerIpPerMinute(5);
        AuthRateLimitFilter filter = filter("", properties, new Clock());

        for (int i = 0; i < 5; i++) {
            assertEquals(200, run(filter, sessionCall("/api/v1/auth/refresh", "203.0.113.14", null), status(200)).getStatus());
        }
        assertEquals(429, run(filter, sessionCall("/api/v1/auth/refresh", "203.0.113.14", null), status(200)).getStatus());
    }

    @Test
    @DisplayName("R19-08: /auth/logout is public and throttled like refresh, in its own bucket")
    void logoutEndpointIsRateLimitedInItsOwnBucket() throws Exception {
        AuthRateLimitFilter filter = filter("", new AuthRateLimitProperties(), new Clock());
        String logout = "/api/v1/auth/logout";

        assertFalse(filter.shouldNotFilter(sessionCall(logout, "203.0.113.20", "c")), "logout must go through the throttle");
        for (int i = 0; i < SESSION_LIMIT; i++) {
            assertEquals(200, run(filter, sessionCall(logout, "203.0.113.20", "c"), status(200)).getStatus(), "logout #" + (i + 1));
        }
        assertEquals(429, run(filter, sessionCall(logout, "203.0.113.20", "c"), status(200)).getStatus());

        // The exhausted logout budget of this session must not lock the same caller out of refresh or login.
        assertEquals(200, run(filter, sessionCall("/api/v1/auth/refresh", "203.0.113.20", "c"), status(200)).getStatus());
        assertEquals(200, run(filter, login("203.0.113.20", "x@example.com", "pw"), status(200)).getStatus());
    }

    @Test
    @DisplayName("R14-09: a 429 is UTF-8 JSON in the standard ApiResponse envelope with a sane Retry-After")
    void rateLimitedResponseIsUtf8EnvelopeWithRetryAfter() throws Exception {
        AuthRateLimitFilter filter = filter("", new AuthRateLimitProperties(), new Clock());
        for (int i = 0; i < PAIR_LIMIT; i++) {
            run(filter, login("203.0.113.11", "a@example.com", "pw"), status(200));
        }

        MockHttpServletRequest blocked = login("203.0.113.11", "a@example.com", "pw");
        blocked.addHeader("X-Request-Id", "req-auth-429");
        MockHttpServletResponse response = run(filter, blocked, status(200));

        assertEquals(429, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"), response.getContentType());
        assertTrue(response.getCharacterEncoding().equalsIgnoreCase("UTF-8"), response.getCharacterEncoding());
        long retryAfter = Long.parseLong(response.getHeader("Retry-After"));
        assertTrue(retryAfter >= 1 && retryAfter <= 60, "Retry-After=" + retryAfter);

        String raw = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(raw.contains("Quá nhiều yêu cầu xác thực"), "Vietnamese text must round-trip as UTF-8: " + raw);
        JsonNode json = MAPPER.readTree(raw);
        assertFalse(json.get("success").asBoolean());
        assertEquals("RATE_LIMITED", json.get("error").get("code").asText());
        assertEquals(AuthRateLimitFilter.RATE_LIMITED_MESSAGE, json.get("error").get("message").asText());
        assertEquals("req-auth-429", json.get("error").get("requestId").asText());
        assertEquals("req-auth-429", json.get("requestId").asText());
        assertNotNull(json.get("timestamp"));
        assertFalse(json.has("code"), "no legacy top-level {code,message} body");
    }

    @Test
    @DisplayName("R14-09: the rejection body has exactly the standard ApiResponse error fields")
    void rejectionEnvelopeHasExactlyTheStandardFields() throws Exception {
        AuthRateLimitFilter filter = filter("", new AuthRateLimitProperties(), new Clock());
        for (int i = 0; i < PAIR_LIMIT; i++) {
            run(filter, login("203.0.113.12", "a@example.com", "pw"), status(200));
        }
        MockHttpServletResponse response = run(filter, login("203.0.113.12", "a@example.com", "pw"), status(200));

        JsonNode json = MAPPER.readTree(response.getContentAsByteArray());
        Set<String> topLevel = new TreeSet<>();
        json.fieldNames().forEachRemaining(topLevel::add);
        Set<String> errorFields = new TreeSet<>();
        json.get("error").fieldNames().forEachRemaining(errorFields::add);
        assertEquals(Set.of("error", "requestId", "success", "timestamp"), topLevel);
        assertEquals(Set.of("code", "message", "requestId"), errorFields);
    }

    @Test
    @DisplayName("R19-08: only the four auth endpoints are filtered; GET and other paths are untouched")
    void nonAuthPathsAndNonPostMethodsAreNotThrottled() throws Exception {
        AuthRateLimitFilter filter = filter("", new AuthRateLimitProperties(), new Clock());
        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/api/v1/auth/me");
        other.setServletPath("/api/v1/auth/me");
        assertTrue(filter.shouldNotFilter(other));

        for (int i = 0; i < 100; i++) {
            MockHttpServletRequest get = new MockHttpServletRequest("GET", LOGIN);
            get.setServletPath(LOGIN);
            get.setRemoteAddr("203.0.113.30");
            assertEquals(200, run(filter, get, status(200)).getStatus());
        }
        assertEquals(0, filter.trackedKeys(), "a GET is not counted");
    }

    @Test
    @DisplayName("R20-02: the login body is replayed unchanged to the controller (the filter read it to find the account)")
    void loginBodyIsReplayedToTheController() throws Exception {
        AuthRateLimitFilter filter = filter("", new AuthRateLimitProperties(), new Clock());
        FakeLogin controller = new FakeLogin(Map.of("student@example.com", "Secret-123"));

        MockHttpServletResponse ok = run(filter, login("203.0.113.40", "Student@Example.com", "Secret-123"), controller);
        assertEquals(200, ok.getStatus());
        assertEquals(AuthFilterFixtures.credentials("Student@Example.com", "Secret-123"), controller.lastBodySeen.get());
    }

    @Test
    @DisplayName("R20-02: an oversized or non-JSON login body is not buffered/trusted: 400 for oversized, unparseable shares one 'no account' bucket")
    void oversizedAndMalformedBodies() throws Exception {
        AuthRateLimitFilter filter = filter("", new AuthRateLimitProperties(), new Clock());

        String huge = "{\"email\":\"a@example.com\",\"password\":\"" + "x".repeat(AuthRateLimitFilter.MAX_BODY_BYTES) + "\"}";
        MockHttpServletResponse tooLarge = run(filter, post(LOGIN, "203.0.113.50", huge), status(200));
        assertEquals(400, tooLarge.getStatus());
        assertTrue(new String(tooLarge.getContentAsByteArray(), StandardCharsets.UTF_8).contains("BAD_REQUEST"));

        // Not JSON / no e-mail: passed to the controller (which answers 400) but throttled as one anonymous bucket per IP.
        for (int i = 0; i < PAIR_LIMIT; i++) {
            assertEquals(400, run(filter, post(LOGIN, "203.0.113.51", "not json"), status(400)).getStatus());
        }
        assertEquals(429, run(filter, post(LOGIN, "203.0.113.51", "{\"password\":\"x\"}"), status(400)).getStatus());
        // ... which does not touch real accounts from the same address.
        assertEquals(200, run(filter, login("203.0.113.51", "real@example.com", "pw"), status(200)).getStatus());
    }

    @Test
    @DisplayName("R20-02: registration allows a whole class from one NAT in the same minute, still bounded per IP and per e-mail")
    void registerHasAHighIpCeilingAndADuplicateGuard() throws Exception {
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        properties.getRegister().setPerIpPerMinute(300);
        properties.getRegister().setPerEmailPerMinute(3);
        AuthRateLimitFilter filter = filter("", properties, new Clock());

        for (int i = 0; i < 300; i++) {
            assertEquals(200, run(filter, register("203.0.113.60", "new" + i + "@example.com"), status(200)).getStatus(), "new #" + i);
        }
        assertEquals(429, run(filter, register("203.0.113.60", "new301@example.com"), status(200)).getStatus());

        // Duplicate protection: the same address submitted from anywhere is capped at 3/min.
        for (int i = 0; i < 3; i++) {
            assertEquals(200, run(filter, register("203.0.113." + (100 + i), "dup@example.com"), status(200)).getStatus());
        }
        assertEquals(429, run(filter, register("203.0.113.109", "dup@example.com"), status(200)).getStatus());
    }

    @Test
    @DisplayName("R20-02: a disabled check (limit <= 0) lets everything through")
    void nonPositiveLimitsDisableTheCheck() throws Exception {
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        properties.getLogin().setPerAccountPerMinute(0);
        properties.getLogin().setPerIpAttemptsPerMinute(0);
        properties.getLogin().setLockThreshold(0);
        properties.getLogin().setAccountLockThreshold(0);
        properties.getLogin().setPerIpFailuresPerMinute(0);
        AuthRateLimitFilter filter = filter("", properties, new Clock());
        for (int i = 0; i < 100; i++) {
            assertEquals(401, run(filter, login("203.0.113.70", "a@example.com", "wrong"), status(401)).getStatus());
        }
    }

    @Test
    @DisplayName("sanity: the default chain of MockFilterChain still works with the wrapper (status stays 200)")
    void mockFilterChainPassesThrough() throws Exception {
        AuthRateLimitFilter filter = filter("", new AuthRateLimitProperties(), new Clock());
        MockHttpServletResponse response = run(filter, login("203.0.113.80", "a@example.com", "pw"), new MockFilterChain());
        assertEquals(200, response.getStatus());
    }
}
