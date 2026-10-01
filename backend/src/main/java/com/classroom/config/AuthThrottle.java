package com.classroom.config;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * R20-02: the decision logic behind {@link AuthRateLimitFilter}, free of servlet types so every rule can be unit-tested with a
 * controllable clock.
 *
 * <p><b>Why this replaced the old "N requests per IP per path per minute" bucket.</b> A school NAT puts a whole class (200+
 * students) behind one address; keyed by IP alone, 10 logins/min meant 190 of 200 students were rejected, and one abusive
 * student could lock the rest out. The budgets are now attached to what is actually being protected:
 *
 * <ul>
 *   <li><b>login</b> - (a) a per-(IP, account) attempt window (default 10/min), (b) exponential backoff on consecutive FAILED
 *   attempts, tracked per (IP, account) pair (5 failures -> 60 s, doubling to 15 min) and, at a higher threshold, per account
 *   from any address (distributed guessing), (c) a generous per-IP ceiling on FAILED attempts (200/min) against credential
 *   stuffing across many accounts, and (d) a very high per-IP ceiling on attempts of any kind that only bounds password-hash CPU.
 *   A successful login resets the failure state of its account and never consumes the failure budget, so 200 different students
 *   signing in from one address within seconds all succeed while 20 wrong passwords for one account are stopped after five.
 *   When the per-IP failure ceiling IS reached (an attacker behind the NAT), pairs that logged in successfully from that address
 *   recently ("regulars") keep working, so the class is not locked out by one abusive user; only never-seen pairs wait for the
 *   window to end;</li>
 *   <li><b>register</b> - a high per-IP ceiling (default 300/min) plus a per-e-mail window (duplicate submissions);</li>
 *   <li><b>refresh / logout</b> - a per-session window (keyed by a hash of the refresh cookie) plus a high per-IP ceiling (600/min).</li>
 * </ul>
 *
 * <p>Nothing here depends on whether an account EXISTS: keys are derived from the e-mail string the caller sent, and every
 * verdict/response is identical for known and unknown addresses, so the throttle cannot be used to enumerate users.
 *
 * <p>All state is in memory, per JVM (see {@link AuthRateLimitProperties}), lock-free on the request path (concurrent hash
 * maps, atomic counters) and hard-bounded (see {@link BoundedMap}), so memory cannot grow without bound.
 */
class AuthThrottle {

    static final long WINDOW_SECONDS = 60;
    /** How long a successful (IP, account) login keeps that pair exempt from the per-IP failure ceiling. */
    static final long KNOWN_PAIR_TTL_SECONDS = 6 * 60 * 60;

    /** Result of a pre-check: 0 = allowed, otherwise the number of seconds the caller should wait. */
    record Verdict(long retryAfterSeconds) {
        static final Verdict ALLOWED = new Verdict(0);

        boolean allowed() {
            return retryAfterSeconds <= 0;
        }

        static Verdict blocked(long retryAfterSeconds) {
            return new Verdict(Math.max(1, retryAfterSeconds));
        }
    }

    private final AuthRateLimitProperties.Login login;
    private final AuthRateLimitProperties.Register register;
    private final AuthRateLimitProperties.Refresh refresh;
    private final AuthRateLimitProperties.Invite invite;
    private final LongSupplier clock;

    private final WindowCounters loginPair;
    private final WindowCounters loginIpAttempts;
    private final WindowCounters loginIpFailures;
    private final FailureTracker pairLocks;
    private final FailureTracker accountLocks;
    private final KnownPairs knownPairs;
    private final WindowCounters registerIp;
    private final WindowCounters registerEmail;
    private final WindowCounters sessionIp;
    private final WindowCounters session;
    private final WindowCounters inviteIpAttempts;
    private final WindowCounters inviteIpFailures;

    AuthThrottle(AuthRateLimitProperties properties, LongSupplier epochSecondClock) {
        this.login = properties.getLogin();
        this.register = properties.getRegister();
        this.refresh = properties.getRefresh();
        this.invite = properties.getInvite();
        this.clock = epochSecondClock;
        int cap = properties.getMaxTrackedKeys();
        this.loginPair = new WindowCounters(cap, epochSecondClock);
        this.loginIpAttempts = new WindowCounters(cap, epochSecondClock);
        this.loginIpFailures = new WindowCounters(cap, epochSecondClock);
        // A streak is remembered for twice the longest lock, so waiting out a maximum lock does not amnesty the attacker.
        long decay = 2 * Math.max(login.getLockMaxSeconds(), WINDOW_SECONDS);
        this.pairLocks = new FailureTracker(login.getLockThreshold(), login.getLockBaseSeconds(), login.getLockMaxSeconds(),
                decay, cap, epochSecondClock);
        this.accountLocks = new FailureTracker(login.getAccountLockThreshold(), login.getLockBaseSeconds(),
                login.getLockMaxSeconds(), decay, cap, epochSecondClock);
        this.knownPairs = new KnownPairs(KNOWN_PAIR_TTL_SECONDS, cap, epochSecondClock);
        this.registerIp = new WindowCounters(cap, epochSecondClock);
        this.registerEmail = new WindowCounters(cap, epochSecondClock);
        this.sessionIp = new WindowCounters(cap, epochSecondClock);
        this.session = new WindowCounters(cap, epochSecondClock);
        this.inviteIpAttempts = new WindowCounters(cap, epochSecondClock);
        this.inviteIpFailures = new WindowCounters(cap, epochSecondClock);
    }

    // ------------------------------------------------------------------ login

    /**
     * Pre-check for {@code POST /auth/login}. {@code account} is the hashed, normalized e-mail the caller sent (or a fixed
     * placeholder when the body carried none). Locks and the failure ceiling are only PEEKED (a blocked request consumes no
     * budget); the attempt windows are charged up front so a parallel burst cannot overshoot them.
     */
    Verdict beforeLogin(String ip, String account) {
        String pair = ip + '|' + account;
        long retry = 0;
        if (login.getPerIpFailuresPerMinute() > 0) {
            long ceiling = loginIpFailures.retryAfterIfAtLeast(ip, login.getPerIpFailuresPerMinute());
            // A regular of this address (a pair that logged in successfully recently) is not held hostage by whoever is failing.
            if (ceiling > 0 && !knownPairs.contains(pair)) {
                retry = ceiling;
            }
        }
        retry = Math.max(retry, pairLocks.retryAfter(pair));
        retry = Math.max(retry, accountLocks.retryAfter(account));
        if (retry > 0) {
            return Verdict.blocked(retry);
        }
        if (login.getPerIpAttemptsPerMinute() > 0) {
            retry = loginIpAttempts.tryAcquire(ip, login.getPerIpAttemptsPerMinute());
        }
        if (retry == 0 && login.getPerAccountPerMinute() > 0) {
            retry = loginPair.tryAcquire(pair, login.getPerAccountPerMinute());
        }
        return retry > 0 ? Verdict.blocked(retry) : Verdict.ALLOWED;
    }

    /**
     * Records the outcome of a login that was let through: 2xx clears the failure state of the account (and of the pair), marks
     * the pair as a regular and consumes no failure budget; 401 (unknown e-mail OR wrong password - indistinguishable on purpose)
     * counts as a failure. Anything else (400 validation, 403 inactive account, 5xx) is neutral.
     */
    void afterLogin(String ip, String account, int httpStatus) {
        String pair = ip + '|' + account;
        if (httpStatus >= 200 && httpStatus < 300) {
            pairLocks.recordSuccess(pair);
            accountLocks.recordSuccess(account);
            if (!NO_ACCOUNT_KEY.equals(account)) {
                knownPairs.remember(pair);
            }
        } else if (httpStatus == 401) {
            pairLocks.recordFailure(pair);
            accountLocks.recordFailure(account);
            if (login.getPerIpFailuresPerMinute() > 0) {
                loginIpFailures.add(ip);
            }
        }
    }

    /** Must equal {@link AuthRateLimitFilter#NO_ACCOUNT}: the key of a body without a usable e-mail is never a "regular". */
    private static final String NO_ACCOUNT_KEY = AuthRateLimitFilter.NO_ACCOUNT;

    // --------------------------------------------------------------- register

    /** Pre-check for {@code POST /auth/register}; {@code emailKey} may be null when the body carried no usable e-mail. */
    Verdict beforeRegister(String ip, String emailKey) {
        long retry = 0;
        if (register.getPerIpPerMinute() > 0) {
            retry = registerIp.tryAcquire(ip, register.getPerIpPerMinute());
        }
        if (emailKey != null && register.getPerEmailPerMinute() > 0) {
            retry = Math.max(retry, registerEmail.tryAcquire(emailKey, register.getPerEmailPerMinute()));
        }
        return retry > 0 ? Verdict.blocked(retry) : Verdict.ALLOWED;
    }

    // ------------------------------------------------------- refresh / logout

    /**
     * Pre-check for {@code POST /auth/refresh} and {@code /auth/logout}. {@code kind} keeps the two endpoints in separate
     * buckets; {@code sessionKey} is a hash of the refresh cookie, or null when the request carried none (then only the
     * per-IP ceiling applies).
     */
    Verdict beforeSession(String kind, String ip, String sessionKey) {
        long retry = 0;
        if (refresh.getPerIpPerMinute() > 0) {
            retry = sessionIp.tryAcquire(kind + '|' + ip, refresh.getPerIpPerMinute());
        }
        if (sessionKey != null && refresh.getPerSessionPerMinute() > 0) {
            retry = Math.max(retry, session.tryAcquire(kind + '|' + sessionKey, refresh.getPerSessionPerMinute()));
        }
        return retry > 0 ? Verdict.blocked(retry) : Verdict.ALLOWED;
    }

    // ---------------------------------------------------------------- invites

    /**
     * Pre-check for the two invite-code endpoints (D-19), keyed by client address. The failure ceiling (unknown codes answered with 404) is only
     * PEEKED, so a blocked request consumes nothing; every call is charged to the overall window so a parallel burst cannot overshoot it.
     */
    Verdict beforeInvite(String ip) {
        long retry = 0;
        if (invite.getPerIpFailuresPerMinute() > 0) {
            retry = inviteIpFailures.retryAfterIfAtLeast(ip, invite.getPerIpFailuresPerMinute());
        }
        if (retry == 0 && invite.getPerIpPerMinute() > 0) {
            retry = inviteIpAttempts.tryAcquire(ip, invite.getPerIpPerMinute());
        }
        return retry > 0 ? Verdict.blocked(retry) : Verdict.ALLOWED;
    }

    /** Records the outcome of an invite call that was let through: a 404 (no such usable code) counts toward the enumeration ceiling. */
    void afterInvite(String ip, int httpStatus) {
        if (httpStatus == 404 && invite.getPerIpFailuresPerMinute() > 0) {
            inviteIpFailures.add(ip);
        }
    }

    // ------------------------------------------------------------- diagnostics

    /** Total entries currently tracked across every store; exposed for the boundedness tests. */
    int trackedKeys() {
        return loginPair.size() + loginIpAttempts.size() + loginIpFailures.size() + pairLocks.size() + accountLocks.size()
                + knownPairs.size() + registerIp.size() + registerEmail.size() + sessionIp.size() + session.size()
                + inviteIpAttempts.size() + inviteIpFailures.size();
    }

    /** Number of stores; the hard bound on {@link #trackedKeys()} is {@code stores() x 2 x maxTrackedKeys}. */
    static int stores() {
        return 12;
    }

    long now() {
        return clock.getAsLong();
    }

    // ===================================================================== stores

    /**
     * A concurrent map that can never grow without bound. Expired entries are swept at most once per second (an O(n) pass on a hot
     * request path must not run per request); if a flood of distinct keys still leaves the map over {@code maxKeys}, entries are
     * evicted down to 90 % of it - those the subclass marks as {@link #precious} last - and if the map has already doubled between
     * two sweeps the once-per-second gate is skipped, so the hard bound is about {@code 2 x maxKeys}. Eviction only ever gives the
     * evicted caller a fresh budget: availability over strictness.
     */
    abstract static class BoundedMap<V> {
        final Map<String, V> map = new ConcurrentHashMap<>();
        final int maxKeys;
        final LongSupplier clock;
        private final AtomicLong lastSweep = new AtomicLong(Long.MIN_VALUE);

        BoundedMap(int maxKeys, LongSupplier clock) {
            this.maxKeys = maxKeys;
            this.clock = clock;
        }

        abstract boolean expired(V value, long now);

        /** Entries worth keeping when memory pressure forces eviction of live ones (e.g. a still-locked account). */
        boolean precious(V value, long now) {
            return false;
        }

        final void sweepIfNeeded(long now) {
            int size = map.size();
            if (size <= maxKeys) {
                return;
            }
            if (size < 2L * maxKeys) {
                long last = lastSweep.get();
                if (last == now || !lastSweep.compareAndSet(last, now)) {
                    return;
                }
            }
            map.values().removeIf(value -> expired(value, now));
            evictDownTo(maxKeys * 9 / 10, now);
        }

        private void evictDownTo(int target, long now) {
            if (map.size() <= target) {
                return;
            }
            Iterator<Map.Entry<String, V>> iterator = map.entrySet().iterator();
            while (map.size() > target && iterator.hasNext()) {
                if (!precious(iterator.next().getValue(), now)) {
                    iterator.remove();
                }
            }
            iterator = map.entrySet().iterator();
            while (map.size() > target && iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }

        final int size() {
            return map.size();
        }
    }

    /** Fixed 60-second windows per key. */
    static final class WindowCounters extends BoundedMap<WindowCounters.Window> {
        record Window(long startedAt, AtomicInteger count) {}

        WindowCounters(int maxKeys, LongSupplier clock) {
            super(maxKeys, clock);
        }

        /** Counts one request against {@code key}; returns 0 when within {@code limit}, else the seconds until the window ends. */
        long tryAcquire(String key, int limit) {
            long now = clock.getAsLong();
            Window window = current(key, now);
            int used = window.count().incrementAndGet();
            sweepIfNeeded(now);
            return used > limit ? Math.max(1, WINDOW_SECONDS - (now - window.startedAt())) : 0;
        }

        /** Counts one event for {@code key} without judging it (used for the failure counter). */
        void add(String key) {
            long now = clock.getAsLong();
            current(key, now).count().incrementAndGet();
            sweepIfNeeded(now);
        }

        /** Returns the seconds until the window ends if {@code key} has already used {@code limit} events in it, else 0. Counts nothing. */
        long retryAfterIfAtLeast(String key, int limit) {
            long now = clock.getAsLong();
            Window window = map.get(key);
            if (window == null || now - window.startedAt() >= WINDOW_SECONDS || window.count().get() < limit) {
                return 0;
            }
            return Math.max(1, WINDOW_SECONDS - (now - window.startedAt()));
        }

        private Window current(String key, long now) {
            return map.compute(key, (ignored, existing) ->
                    existing == null || now - existing.startedAt() >= WINDOW_SECONDS ? new Window(now, new AtomicInteger()) : existing);
        }

        @Override
        boolean expired(Window window, long now) {
            return now - window.startedAt() >= WINDOW_SECONDS;
        }
    }

    /**
     * Consecutive-failure counter with exponential lock-out: from {@code threshold} failures on, the key is locked for
     * {@code base} seconds, doubling with every further failure up to {@code max}. A success clears it; an entry that is
     * neither locked nor failed for {@code decay} seconds is forgotten. A threshold {@code <= 0} disables the tracker.
     */
    static final class FailureTracker extends BoundedMap<FailureTracker.State> {
        record State(int failures, long lockedUntil, long lastFailureAt) {}

        private final int threshold;
        private final long baseSeconds;
        private final long maxSeconds;
        private final long decaySeconds;

        FailureTracker(int threshold, long baseSeconds, long maxSeconds, long decaySeconds, int maxKeys, LongSupplier clock) {
            super(maxKeys, clock);
            this.threshold = threshold;
            this.baseSeconds = Math.max(1, baseSeconds);
            this.maxSeconds = Math.max(this.baseSeconds, maxSeconds);
            this.decaySeconds = decaySeconds;
        }

        /** Seconds until {@code key} may try again, 0 when it is not locked. Counts nothing. */
        long retryAfter(String key) {
            if (threshold <= 0) {
                return 0;
            }
            State state = map.get(key);
            return state == null ? 0 : Math.max(0, state.lockedUntil() - clock.getAsLong());
        }

        void recordFailure(String key) {
            if (threshold <= 0) {
                return;
            }
            long now = clock.getAsLong();
            map.compute(key, (ignored, existing) -> {
                State base = existing == null || expired(existing, now) ? new State(0, 0, 0) : existing;
                int failures = base.failures() + 1;
                long lockedUntil = base.lockedUntil();
                if (failures >= threshold) {
                    int doublings = Math.min(failures - threshold, 30);
                    long lock = Math.min(maxSeconds, baseSeconds << doublings);
                    lockedUntil = now + Math.max(1, lock);
                }
                return new State(failures, lockedUntil, now);
            });
            sweepIfNeeded(now);
        }

        void recordSuccess(String key) {
            if (threshold > 0) {
                map.remove(key);
            }
        }

        @Override
        boolean expired(State state, long now) {
            return now >= state.lockedUntil() && now - state.lastFailureAt() >= decaySeconds;
        }

        @Override
        boolean precious(State state, long now) {
            return state.lockedUntil() > now;
        }
    }

    /** (IP, account) pairs that recently logged in successfully: the "regulars" of an address, kept for {@code ttl} seconds. */
    static final class KnownPairs extends BoundedMap<Long> {
        private final long ttlSeconds;

        KnownPairs(long ttlSeconds, int maxKeys, LongSupplier clock) {
            super(maxKeys, clock);
            this.ttlSeconds = ttlSeconds;
        }

        void remember(String pair) {
            long now = clock.getAsLong();
            map.put(pair, now + ttlSeconds);
            sweepIfNeeded(now);
        }

        boolean contains(String pair) {
            Long expiresAt = map.get(pair);
            return expiresAt != null && expiresAt > clock.getAsLong();
        }

        @Override
        boolean expired(Long expiresAt, long now) {
            return expiresAt <= now;
        }
    }
}
