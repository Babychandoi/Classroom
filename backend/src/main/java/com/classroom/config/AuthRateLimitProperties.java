package com.classroom.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * R20-02: tunables of the public-authentication throttle ({@link AuthRateLimitFilter} / {@link AuthThrottle}), bound from
 * {@code app.security.rate-limit.*}. The defaults are safe for a school-sized deployment where a whole class (200+ students)
 * signs in from ONE NAT address within seconds; see docs/RUNBOOK.md for the reasoning and how to tune them.
 *
 * <p>A limit {@code <= 0} disables that one check (never do that for the login limits on an internet-facing deployment).
 * All counters live in this JVM only: with several backend instances behind a load balancer each instance enforces its own
 * budgets, so the effective ceiling is instances x the configured value. That is deliberate (no shared store to fail)
 * and is why the ingress should still rate-limit as a coarse outer layer.
 */
@Component
@ConfigurationProperties(prefix = "app.security.rate-limit")
public class AuthRateLimitProperties {

    /** Hard cap on tracked keys PER limiter store: bounds memory however many distinct callers/accounts show up. */
    private int maxTrackedKeys = 20000;
    private final Login login = new Login();
    private final Register register = new Register();
    private final Refresh refresh = new Refresh();
    private final Invite invite = new Invite();

    public int getMaxTrackedKeys() {
        return maxTrackedKeys;
    }

    public void setMaxTrackedKeys(int maxTrackedKeys) {
        this.maxTrackedKeys = Math.max(100, maxTrackedKeys);
    }

    public Login getLogin() {
        return login;
    }

    public Register getRegister() {
        return register;
    }

    public Refresh getRefresh() {
        return refresh;
    }

    public Invite getInvite() {
        return invite;
    }

    /** {@code POST /auth/login}. */
    public static class Login {
        /** Attempts per minute for one (client IP, account) pair - successes included; the primary brute-force limiter. */
        private int perAccountPerMinute = 10;
        /** FAILED (401) logins per minute from one IP across all accounts - credential stuffing. Successes never count. */
        private int perIpFailuresPerMinute = 200;
        /**
         * ALL login attempts per minute from one IP. Only bounds the CPU a single address can burn on password hashing
         * (failures are counted after the fact, so a parallel burst would otherwise slip past the failure ceiling); it is
         * far above what a class signing in at once needs.
         */
        private int perIpAttemptsPerMinute = 1000;
        /** Consecutive failures of one (IP, account) pair before it is locked out for {@link #lockBaseSeconds}. */
        private int lockThreshold = 5;
        /** Length of the first lock; it doubles with every further failure up to {@link #lockMaxSeconds}. */
        private int lockBaseSeconds = 60;
        private int lockMaxSeconds = 900;
        /** Consecutive failures of one account from ANY IPs before the account is locked (distributed guessing). */
        private int accountLockThreshold = 30;

        public int getPerAccountPerMinute() {
            return perAccountPerMinute;
        }

        public void setPerAccountPerMinute(int perAccountPerMinute) {
            this.perAccountPerMinute = perAccountPerMinute;
        }

        public int getPerIpFailuresPerMinute() {
            return perIpFailuresPerMinute;
        }

        public void setPerIpFailuresPerMinute(int perIpFailuresPerMinute) {
            this.perIpFailuresPerMinute = perIpFailuresPerMinute;
        }

        public int getPerIpAttemptsPerMinute() {
            return perIpAttemptsPerMinute;
        }

        public void setPerIpAttemptsPerMinute(int perIpAttemptsPerMinute) {
            this.perIpAttemptsPerMinute = perIpAttemptsPerMinute;
        }

        public int getLockThreshold() {
            return lockThreshold;
        }

        public void setLockThreshold(int lockThreshold) {
            this.lockThreshold = lockThreshold;
        }

        public int getLockBaseSeconds() {
            return lockBaseSeconds;
        }

        public void setLockBaseSeconds(int lockBaseSeconds) {
            this.lockBaseSeconds = Math.max(1, lockBaseSeconds);
        }

        public int getLockMaxSeconds() {
            return lockMaxSeconds;
        }

        public void setLockMaxSeconds(int lockMaxSeconds) {
            this.lockMaxSeconds = Math.max(1, lockMaxSeconds);
        }

        public int getAccountLockThreshold() {
            return accountLockThreshold;
        }

        public void setAccountLockThreshold(int accountLockThreshold) {
            this.accountLockThreshold = accountLockThreshold;
        }
    }

    /** {@code POST /auth/register}. */
    public static class Register {
        /**
         * Registrations per minute from one IP. High enough that a class of 200+ students behind one NAT can all sign up in
         * the same minute; it only stops scripted account creation at scale.
         */
        private int perIpPerMinute = 300;
        /** Register attempts per minute for one e-mail address from anywhere (double-submit / duplicate protection); 0 = off. */
        private int perEmailPerMinute = 10;

        public int getPerIpPerMinute() {
            return perIpPerMinute;
        }

        public void setPerIpPerMinute(int perIpPerMinute) {
            this.perIpPerMinute = perIpPerMinute;
        }

        public int getPerEmailPerMinute() {
            return perEmailPerMinute;
        }

        public void setPerEmailPerMinute(int perEmailPerMinute) {
            this.perEmailPerMinute = perEmailPerMinute;
        }
    }

    /**
     * D-19: {@code GET /classes/invites/{code}} (preview, public) and {@code POST /classes/invites/{code}/join}: the two endpoints that take an
     * invite code. Keyed by client address only - the code is the secret being guessed, so there is no account to key on. A code is 192 random bits,
     * so guessing is hopeless anyway; the limits exist so the endpoints cannot be used to hammer the database or to probe for the fixed demo code.
     */
    public static class Invite {
        /** ALL calls per minute from one address (valid and invalid): bounds the database work. A class joining from one NAT must fit. */
        private int perIpPerMinute = 600;
        /** UNKNOWN-code answers (404) per minute from one address: the enumeration limiter. Valid previews / joins never count. */
        private int perIpFailuresPerMinute = 30;

        public int getPerIpPerMinute() {
            return perIpPerMinute;
        }

        public void setPerIpPerMinute(int perIpPerMinute) {
            this.perIpPerMinute = perIpPerMinute;
        }

        public int getPerIpFailuresPerMinute() {
            return perIpFailuresPerMinute;
        }

        public void setPerIpFailuresPerMinute(int perIpFailuresPerMinute) {
            this.perIpFailuresPerMinute = perIpFailuresPerMinute;
        }
    }

    /** {@code POST /auth/refresh} and {@code POST /auth/logout} (each with its own buckets). */
    public static class Refresh {
        /** Calls per minute presenting the same refresh cookie (= one browser session). */
        private int perSessionPerMinute = 60;
        /** Calls per minute from one IP across all sessions - a whole class opening tabs at once must fit. */
        private int perIpPerMinute = 600;

        public int getPerSessionPerMinute() {
            return perSessionPerMinute;
        }

        public void setPerSessionPerMinute(int perSessionPerMinute) {
            this.perSessionPerMinute = perSessionPerMinute;
        }

        public int getPerIpPerMinute() {
            return perIpPerMinute;
        }

        public void setPerIpPerMinute(int perIpPerMinute) {
            this.perIpPerMinute = perIpPerMinute;
        }
    }
}
