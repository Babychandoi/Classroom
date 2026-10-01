package com.classroom.config;

import com.classroom.config.AuthFilterFixtures.Clock;
import com.classroom.config.AuthThrottle.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R20-02: the decision rules of {@link AuthThrottle}, driven by a controllable clock. */
class AuthThrottleTest {

    private final Clock clock = new Clock();
    private final AuthRateLimitProperties properties = new AuthRateLimitProperties();
    private AuthThrottle throttle = new AuthThrottle(properties, clock);

    /** One login attempt that reaches the controller and fails (401). */
    private Verdict failedLogin(String ip, String account) {
        Verdict verdict = throttle.beforeLogin(ip, account);
        if (verdict.allowed()) {
            throttle.afterLogin(ip, account, 401);
        }
        return verdict;
    }

    private Verdict successfulLogin(String ip, String account) {
        Verdict verdict = throttle.beforeLogin(ip, account);
        if (verdict.allowed()) {
            throttle.afterLogin(ip, account, 200);
        }
        return verdict;
    }

    @Test
    @DisplayName("per (IP, account) window: 10 attempts per minute, the 11th waits for the window to end, then the budget is fresh")
    void pairWindow() {
        for (int i = 0; i < 10; i++) {
            assertTrue(successfulLogin("1.1.1.1", "a").allowed(), "attempt " + i);
        }
        Verdict blocked = throttle.beforeLogin("1.1.1.1", "a");
        assertFalse(blocked.allowed());
        assertTrue(blocked.retryAfterSeconds() >= 1 && blocked.retryAfterSeconds() <= 60, "retryAfter=" + blocked.retryAfterSeconds());

        assertTrue(throttle.beforeLogin("1.1.1.1", "b").allowed(), "another account of the same IP is untouched");
        assertTrue(throttle.beforeLogin("2.2.2.2", "a").allowed(), "the same account from another IP is untouched");

        clock.advance(60);
        assertTrue(throttle.beforeLogin("1.1.1.1", "a").allowed(), "a new window starts after 60 s");
    }

    @Test
    @DisplayName("backoff: 5 consecutive failures lock the pair for 60 s, then 120, 240, 480 and the 900 s cap; Retry-After follows")
    void exponentialBackoffWithCap() {
        for (int i = 0; i < 5; i++) {
            assertTrue(failedLogin("1.1.1.1", "a").allowed(), "failure " + (i + 1) + " is still evaluated");
        }
        assertEquals(60, throttle.beforeLogin("1.1.1.1", "a").retryAfterSeconds());
        clock.advance(30);
        assertEquals(30, throttle.beforeLogin("1.1.1.1", "a").retryAfterSeconds());
        clock.advance(30);

        long[] expected = {120, 240, 480, 900, 900, 900};
        for (long lock : expected) {
            assertTrue(failedLogin("1.1.1.1", "a").allowed(), "first attempt after the lock is evaluated");
            assertEquals(lock, throttle.beforeLogin("1.1.1.1", "a").retryAfterSeconds());
            clock.advance(lock);
        }
    }

    @Test
    @DisplayName("a successful login resets the failure counter and lifts the backoff")
    void successResetsBackoff() {
        for (int i = 0; i < 4; i++) {
            failedLogin("1.1.1.1", "a");
        }
        assertTrue(successfulLogin("1.1.1.1", "a").allowed());
        // Four more failures do not lock: the counter restarted at the success.
        for (int i = 0; i < 4; i++) {
            assertTrue(failedLogin("1.1.1.1", "a").allowed());
        }
        assertTrue(throttle.beforeLogin("1.1.1.1", "a").allowed());

        failedLogin("1.1.1.1", "a"); // 5th consecutive
        assertFalse(throttle.beforeLogin("1.1.1.1", "a").allowed());
    }

    @Test
    @DisplayName("a failure streak is forgotten after 30 idle minutes (twice the longest lock)")
    void staleFailuresDecay() {
        for (int i = 0; i < 4; i++) {
            failedLogin("1.1.1.1", "a");
        }
        clock.advance(1801);
        for (int i = 0; i < 4; i++) {
            assertTrue(failedLogin("1.1.1.1", "a").allowed());
        }
        assertTrue(throttle.beforeLogin("1.1.1.1", "a").allowed(), "8 failures in total but only 4 in the current streak");
    }

    @Test
    @DisplayName("distributed guessing: 30 consecutive failures for one account from 30 different IPs lock the account everywhere")
    void accountWideLock() {
        for (int i = 0; i < 30; i++) {
            assertTrue(failedLogin("10.0.0." + i, "victim").allowed());
        }
        Verdict fromNewIp = throttle.beforeLogin("10.9.9.9", "victim");
        assertFalse(fromNewIp.allowed());
        assertEquals(60, fromNewIp.retryAfterSeconds());
        assertTrue(throttle.beforeLogin("10.9.9.9", "someone-else").allowed(), "other accounts are unaffected");
    }

    @Test
    @DisplayName("credential stuffing: 500 failures across 500 accounts from one IP - the per-IP failure ceiling (200/min) hits, other IPs are unaffected")
    void perIpFailureCeiling() {
        int reachedController = 0;
        int rejected = 0;
        for (int i = 0; i < 500; i++) {
            if (failedLogin("6.6.6.6", "victim" + i).allowed()) {
                reachedController++;
            } else {
                rejected++;
            }
        }
        assertEquals(200, reachedController);
        assertEquals(300, rejected);

        Verdict blocked = throttle.beforeLogin("6.6.6.6", "fresh-account");
        assertFalse(blocked.allowed());
        assertTrue(blocked.retryAfterSeconds() >= 1 && blocked.retryAfterSeconds() <= 60);
        assertTrue(throttle.beforeLogin("7.7.7.7", "fresh-account").allowed(), "another address is not affected");

        clock.advance(60);
        assertTrue(throttle.beforeLogin("6.6.6.6", "fresh-account").allowed(), "the ceiling is per minute");
    }

    @Test
    @DisplayName("successful logins never consume the failure budget: 800 successes + 199 failures from one IP are all evaluated, the 201st failure is not")
    void successesDoNotConsumeTheFailureBudget() {
        for (int i = 0; i < 800; i++) {
            assertTrue(successfulLogin("5.5.5.5", "student" + i).allowed(), "success " + i);
        }
        for (int i = 0; i < 199; i++) {
            assertTrue(failedLogin("5.5.5.5", "typo" + i).allowed(), "failure " + i);
        }
        assertTrue(failedLogin("5.5.5.5", "typo-200").allowed(), "the 200th failure is still evaluated");
        assertFalse(throttle.beforeLogin("5.5.5.5", "typo-201").allowed(), "the 201st is over the ceiling");
    }

    @Test
    @DisplayName("regulars of an address (pairs that logged in successfully) keep working while an attacker exhausts the per-IP failure ceiling")
    void regularsSurviveTheFailureCeiling() {
        for (int i = 0; i < 100; i++) {
            assertTrue(successfulLogin("5.5.5.5", "student" + i).allowed());
        }
        for (int i = 0; i < 250; i++) {
            failedLogin("5.5.5.5", "guess" + i); // the attacker behind the same NAT
        }
        assertFalse(throttle.beforeLogin("5.5.5.5", "guess-new").allowed(), "unknown pairs wait for the window");
        assertFalse(throttle.beforeLogin("5.5.5.5", "student-never-logged-in").allowed(), "a first-time login also waits");
        for (int i = 0; i < 100; i++) {
            assertTrue(throttle.beforeLogin("5.5.5.5", "student" + i).allowed(), "regular student" + i + " is not locked out");
        }
    }

    @Test
    @DisplayName("all-attempts ceiling per IP (1000/min) only bounds password-hash CPU: successes count towards it, failures budget is separate")
    void perIpAttemptCeiling() {
        for (int i = 0; i < 1000; i++) {
            assertTrue(successfulLogin("5.5.5.5", "student" + i).allowed(), "attempt " + i);
        }
        Verdict blocked = throttle.beforeLogin("5.5.5.5", "student1000");
        assertFalse(blocked.allowed());
        assertTrue(throttle.beforeLogin("5.5.5.6", "student1000").allowed());
    }

    @Test
    @DisplayName("neutral outcomes (400 validation, 403 inactive, 5xx) are not failures")
    void neutralOutcomesAreNotFailures() {
        for (int status : new int[]{400, 403, 500, 503}) {
            for (int i = 0; i < 20; i++) {
                throttle.afterLogin("4.4.4.4", "x" + status, status);
            }
            assertTrue(throttle.beforeLogin("4.4.4.4", "x" + status).allowed(), "status " + status);
        }
    }

    @Test
    @DisplayName("a rejected request consumes no budget: hammering a locked pair does not extend the lock or exhaust other budgets")
    void rejectedRequestsAreFree() {
        for (int i = 0; i < 5; i++) {
            failedLogin("1.1.1.1", "a");
        }
        for (int i = 0; i < 5000; i++) {
            assertFalse(throttle.beforeLogin("1.1.1.1", "a").allowed());
        }
        assertEquals(60, throttle.beforeLogin("1.1.1.1", "a").retryAfterSeconds());
        assertTrue(throttle.beforeLogin("1.1.1.1", "b").allowed(), "the IP attempt ceiling was not charged by the rejected calls");
    }

    @Test
    @DisplayName("register: per-IP ceiling (300/min) and per-e-mail window (10/min)")
    void registerLimits() {
        for (int i = 0; i < 300; i++) {
            assertTrue(throttle.beforeRegister("3.3.3.3", "email" + i).allowed(), "registration " + i);
        }
        assertFalse(throttle.beforeRegister("3.3.3.3", "email300").allowed());
        assertTrue(throttle.beforeRegister("3.3.3.4", "email300").allowed());

        for (int i = 0; i < 10; i++) {
            assertTrue(throttle.beforeRegister("9.9.9." + i, "dup").allowed());
        }
        assertFalse(throttle.beforeRegister("9.9.9.99", "dup").allowed());
        assertTrue(throttle.beforeRegister("9.9.9.99", null).allowed(), "a body without an e-mail only counts against the IP");
    }

    @Test
    @DisplayName("refresh/logout: per-session window (60/min) and per-IP ceiling (600/min), separate per endpoint")
    void sessionLimits() {
        for (int i = 0; i < 60; i++) {
            assertTrue(throttle.beforeSession("/refresh", "8.8.8.8", "cookie").allowed());
        }
        assertFalse(throttle.beforeSession("/refresh", "8.8.8.8", "cookie").allowed());
        assertTrue(throttle.beforeSession("/logout", "8.8.8.8", "cookie").allowed(), "logout has its own bucket");
        assertTrue(throttle.beforeSession("/refresh", "8.8.8.8", "other-cookie").allowed());

        AuthThrottle fresh = new AuthThrottle(properties, clock);
        for (int i = 0; i < 600; i++) {
            assertTrue(fresh.beforeSession("/refresh", "8.8.4.4", "s" + i).allowed(), "session " + i);
        }
        assertFalse(fresh.beforeSession("/refresh", "8.8.4.4", "s600").allowed());
    }

    @Test
    @DisplayName("memory is bounded: a flood of distinct keys never holds more than stores x 2 x maxTrackedKeys entries, and the throttle keeps working")
    void trackedKeysAreBounded() {
        properties.setMaxTrackedKeys(100);
        throttle = new AuthThrottle(properties, clock);
        for (int i = 0; i < 60_000; i++) {
            String ip = "ip" + i;
            throttle.beforeLogin(ip, "acct" + i);
            throttle.afterLogin(ip, "acct" + i, i % 2 == 0 ? 401 : 200);
            throttle.beforeRegister(ip, "mail" + i);
            throttle.beforeSession("/refresh", ip, "cookie" + i);
        }
        int bound = AuthThrottle.stores() * 2 * 100;
        assertTrue(throttle.trackedKeys() <= bound, "tracked " + throttle.trackedKeys() + " > " + bound);

        // Still functional afterwards.
        for (int i = 0; i < 10; i++) {
            assertTrue(successfulLogin("late", "student").allowed());
        }
        assertFalse(throttle.beforeLogin("late", "student").allowed());
    }

    @Test
    @DisplayName("memory pressure evicts the flood, not the accounts that are locked out right now")
    void lockedEntriesSurviveEviction() {
        properties.setMaxTrackedKeys(100);
        throttle = new AuthThrottle(properties, clock);
        for (int i = 0; i < 5; i++) {
            failedLogin("1.1.1.1", "locked");
        }
        assertFalse(throttle.beforeLogin("1.1.1.1", "locked").allowed());

        for (int i = 0; i < 20_000; i++) {
            failedLogin("flood" + i, "acct" + i); // one failure each: never reaches a lock, only fills the maps
        }
        assertFalse(throttle.beforeLogin("1.1.1.1", "locked").allowed(), "the lock survived the flood");
    }

    @Test
    @DisplayName("expired entries are dropped when the map is swept")
    void expiredEntriesAreSwept() {
        properties.setMaxTrackedKeys(100);
        throttle = new AuthThrottle(properties, clock);
        for (int i = 0; i < 150; i++) {
            throttle.beforeRegister("old" + i, null);
        }
        clock.advance(120);
        for (int i = 0; i < 150; i++) {
            throttle.beforeRegister("new" + i, null);
        }
        assertTrue(throttle.trackedKeys() <= 150, "tracked " + throttle.trackedKeys());
    }
}
