package com.classroom.config;

import com.classroom.config.AuthFilterFixtures.Clock;
import com.classroom.config.AuthFilterFixtures.FakeLogin;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static com.classroom.config.AuthFilterFixtures.MAPPER;
import static com.classroom.config.AuthFilterFixtures.filter;
import static com.classroom.config.AuthFilterFixtures.login;
import static com.classroom.config.AuthFilterFixtures.register;
import static com.classroom.config.AuthFilterFixtures.run;
import static com.classroom.config.AuthFilterFixtures.sessionCall;
import static com.classroom.config.AuthFilterFixtures.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R20-02 end to end through {@link AuthRateLimitFilter} with the real body-replay path and a fake controller that answers 200/401
 * from the (replayed) JSON body. These are the situations the review measured against the old per-IP bucket: a class behind one
 * school NAT, brute force on one account, and credential stuffing.
 */
class AuthRateLimitScenarioTest {

    private static final String NAT = "198.51.100.7";
    private static final String PASSWORD = "R20Passw0rd!";

    private final Clock clock = new Clock();
    private final AuthRateLimitFilter filter = filter("", new AuthRateLimitProperties(), clock);

    private static Map<String, String> classOf(int students) {
        Map<String, String> accounts = new HashMap<>();
        for (int i = 0; i < students; i++) {
            accounts.put("student" + i + "@school.example", PASSWORD);
        }
        return accounts;
    }

    @Test
    @DisplayName("NAT: 200 different students log in from ONE address within 30 s - all 200 succeed (was 10 of 200)")
    void classBehindOneNatAllLogIn() throws Exception {
        FakeLogin controller = new FakeLogin(classOf(200));
        int ok = 0;
        for (int i = 0; i < 200; i++) {
            if (i % 7 == 0) clock.advance(1); // ~29 s in total
            MockHttpServletResponse response = run(filter, login(NAT, "student" + i + "@school.example", PASSWORD), controller);
            if (response.getStatus() == 200) ok++;
        }
        assertEquals(200, ok);
        assertEquals(200, controller.invocations);

        // A second wave (students who reload / log in again on another tab) is fine too.
        for (int i = 0; i < 200; i++) {
            assertEquals(200, run(filter, login(NAT, "student" + i + "@school.example", PASSWORD), controller).getStatus());
        }
    }

    @Test
    @DisplayName("NAT: 200 students register from ONE address within 30 s - all 200 succeed (was 10 of 200)")
    void classBehindOneNatAllRegister() throws Exception {
        int ok = 0;
        for (int i = 0; i < 200; i++) {
            if (i % 7 == 0) clock.advance(1);
            if (run(filter, register(NAT, "new" + i + "@school.example"), status(200)).getStatus() == 200) ok++;
        }
        assertEquals(200, ok);
    }

    @Test
    @DisplayName("NAT: 200 sessions refresh from ONE address within 30 s - all 200 succeed (was 60 of 200)")
    void classBehindOneNatAllRefresh() throws Exception {
        int ok = 0;
        for (int i = 0; i < 200; i++) {
            if (i % 7 == 0) clock.advance(1);
            if (run(filter, sessionCall("/api/v1/auth/refresh", NAT, "cookie-" + i), status(200)).getStatus() == 200) ok++;
        }
        assertEquals(200, ok);
    }

    @Test
    @DisplayName("brute force: 20 wrong passwords for ONE account are stopped after 5, and the rest of the class on the same address is untouched")
    void bruteForceOnOneAccountIsThrottledWithoutHurtingTheClass() throws Exception {
        Map<String, String> accounts = classOf(60);
        FakeLogin controller = new FakeLogin(accounts);
        String victim = "student0@school.example";

        int reachedController = 0;
        int throttled = 0;
        long firstRetryAfter = 0;
        for (int attempt = 0; attempt < 20; attempt++) {
            int before = controller.invocations;
            MockHttpServletResponse response = run(filter, login(NAT, victim, "guess-" + attempt), controller);
            if (controller.invocations > before) {
                reachedController++;
                assertEquals(401, response.getStatus());
            } else {
                throttled++;
                assertEquals(429, response.getStatus());
                if (firstRetryAfter == 0) firstRetryAfter = Long.parseLong(response.getHeader("Retry-After"));
            }
            // Meanwhile another student of the same class signs in.
            String other = "student" + (attempt + 1) + "@school.example";
            assertEquals(200, run(filter, login(NAT, other, PASSWORD), controller).getStatus(), "classmate " + other);
        }
        assertEquals(5, reachedController, "only 5 guesses ever reach the password check");
        assertEquals(15, throttled);
        assertEquals(60, firstRetryAfter);

        // Even the CORRECT password is refused during the lock (no oracle for the attacker), from this address...
        assertEquals(429, run(filter, login(NAT, victim, PASSWORD), controller).getStatus());
        // ... and the guesses do not become cheaper by waiting only a little.
        clock.advance(59);
        assertEquals(429, run(filter, login(NAT, victim, "another-guess"), controller).getStatus());

        // After the lock the account is evaluated again; one more wrong guess doubles the lock (120 s).
        clock.advance(1);
        MockHttpServletResponse retry = run(filter, login(NAT, victim, "guess-21"), controller);
        assertEquals(401, retry.getStatus());
        MockHttpServletResponse locked = run(filter, login(NAT, victim, PASSWORD), controller);
        assertEquals(429, locked.getStatus());
        assertEquals("120", locked.getHeader("Retry-After"));

        // The owner comes back after the lock with the right password: 200, and the failure history is gone.
        clock.advance(120);
        assertEquals(200, run(filter, login(NAT, victim, PASSWORD), controller).getStatus());
        for (int i = 0; i < 4; i++) {
            assertEquals(401, run(filter, login(NAT, victim, "typo" + i), controller).getStatus());
        }
        assertEquals(200, run(filter, login(NAT, victim, PASSWORD), controller).getStatus(), "4 typos after a success do not lock");
    }

    @Test
    @DisplayName("credential stuffing: 500 failures over 500 accounts from one IP - only 200 are evaluated, the ceiling then blocks new accounts until the minute ends")
    void credentialStuffingHitsThePerIpCeiling() throws Exception {
        FakeLogin controller = new FakeLogin(classOf(1));
        int evaluated = 0;
        for (int i = 0; i < 500; i++) {
            int before = controller.invocations;
            MockHttpServletResponse response = run(filter, login("203.0.113.66", "victim" + i + "@example.com", "Password1"), controller);
            if (controller.invocations > before) {
                evaluated++;
                assertEquals(401, response.getStatus());
            } else {
                assertEquals(429, response.getStatus());
            }
        }
        assertEquals(200, evaluated);
        // Another address is not affected.
        assertEquals(401, run(filter, login("203.0.113.67", "victim1@example.com", "Password1"), controller).getStatus());
        // The minute ends: the address may try again.
        clock.advance(60);
        assertEquals(401, run(filter, login("203.0.113.66", "victim501@example.com", "Password1"), controller).getStatus());
    }

    @Test
    @DisplayName("successful logins do not consume the failure budget: 190 typos + 400 successes from one NAT, all evaluated")
    void successesDoNotEatIntoTheFailureBudget() throws Exception {
        FakeLogin controller = new FakeLogin(classOf(400));
        int failures = 0;
        for (int i = 0; i < 400; i++) {
            assertEquals(200, run(filter, login(NAT, "student" + i + "@school.example", PASSWORD), controller).getStatus(), "student " + i);
            if (i < 190) {
                assertEquals(401, run(filter, login(NAT, "typo" + i + "@school.example", "x"), controller).getStatus());
                failures++;
            }
        }
        assertEquals(190, failures);
    }

    @Test
    @DisplayName("an attacker behind the same NAT who exhausts the failure ceiling cannot lock out the students who already logged in from it")
    void attackerBehindTheNatCannotLockOutRegulars() throws Exception {
        FakeLogin controller = new FakeLogin(classOf(50));
        for (int i = 0; i < 50; i++) {
            assertEquals(200, run(filter, login(NAT, "student" + i + "@school.example", PASSWORD), controller).getStatus());
        }
        for (int i = 0; i < 300; i++) {
            run(filter, login(NAT, "guess" + i + "@school.example", "nope"), controller);
        }
        assertEquals(429, run(filter, login(NAT, "guess-more@school.example", "nope"), controller).getStatus());
        for (int i = 0; i < 50; i++) {
            assertEquals(200, run(filter, login(NAT, "student" + i + "@school.example", PASSWORD), controller).getStatus(),
                    "student" + i + " is a regular of this address");
        }
    }

    @Test
    @DisplayName("no account enumeration: a known and an unknown e-mail get the same statuses, Retry-After and 429 body under the same failures")
    void knownAndUnknownAccountsAreIndistinguishable() throws Exception {
        FakeLogin controller = new FakeLogin(Map.of("known@school.example", PASSWORD));
        String ip = "203.0.113.90";
        for (int attempt = 0; attempt < 8; attempt++) {
            MockHttpServletResponse known = run(filter, login(ip, "known@school.example", "wrong" + attempt), controller);
            MockHttpServletResponse unknown = run(filter, login(ip, "ghost@school.example", "wrong" + attempt), controller);
            assertEquals(known.getStatus(), unknown.getStatus(), "attempt " + attempt);
            assertEquals(known.getHeader("Retry-After"), unknown.getHeader("Retry-After"));
            if (known.getStatus() == 429) {
                assertEquals(errorOf(known), errorOf(unknown));
            }
        }
    }

    private static String errorOf(MockHttpServletResponse response) throws Exception {
        JsonNode error = MAPPER.readTree(response.getContentAsByteArray()).get("error");
        return error.get("code").asText() + "|" + error.get("message").asText();
    }

    @Test
    @DisplayName("memory: 100k failed logins for distinct accounts and IPs keep the tracked state under the hard bound")
    void floodDoesNotGrowMemoryWithoutBound() throws Exception {
        AuthRateLimitProperties small = new AuthRateLimitProperties();
        small.setMaxTrackedKeys(100);
        AuthRateLimitFilter bounded = filter("", small, clock);
        for (int i = 0; i < 20_000; i++) {
            run(bounded, login("10." + (i / 250) + "." + (i % 250) + ".1", "flood" + i + "@example.com", "x"), status(401));
            run(bounded, register("10.99." + (i % 250) + ".1", "reg" + i + "@example.com"), status(200));
            run(bounded, sessionCall("/api/v1/auth/refresh", "10.98." + (i % 250) + ".1", "cookie" + i), status(200));
        }
        assertTrue(bounded.trackedKeys() <= AuthThrottle.stores() * 2 * 100, "tracked " + bounded.trackedKeys());
    }
}
