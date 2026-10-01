package com.classroom.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The same NAT/account/session rules enforced across all backends sharing MySQL. */
@Component
@ConditionalOnProperty(name = "app.security.rate-limit.store", havingValue = "mysql")
class MySqlAuthThrottle extends AuthThrottle {
    private final SharedRateLimitStore store;
    private final AuthRateLimitProperties properties;
    MySqlAuthThrottle(SharedRateLimitStore store, AuthRateLimitProperties properties) {
        super(properties, () -> java.time.Instant.now().getEpochSecond());
        this.store = store; this.properties = properties;
    }
    private Verdict verdict(long retry) { return retry > 0 ? Verdict.blocked(retry) : Verdict.ALLOWED; }
    @Override Verdict beforeLogin(String ip, String account) {
        var l = properties.getLogin(); String pair = ip + '|' + account;
        long retry = Math.max(store.peek("lock-pair:" + pair, 0, true), store.peek("lock-account:" + account, 0, true));
        if (retry > 0) return verdict(retry);
        boolean known = store.peek("known:" + pair, 1, false) > 0;
        if (!known) retry = store.peek("login-failures:" + ip, l.getPerIpFailuresPerMinute(), false);
        if (retry > 0) return verdict(retry);
        retry = store.acquire("login-ip:" + ip, l.getPerIpAttemptsPerMinute());
        if (retry == 0) retry = store.acquire("login-pair:" + pair, l.getPerAccountPerMinute());
        return verdict(retry);
    }
    @Override void afterLogin(String ip, String account, int status) {
        var l = properties.getLogin(); String pair = ip + '|' + account;
        if (status >= 200 && status < 300) {
            store.clear("lock-pair:" + pair); store.clear("lock-account:" + account);
            if (!AuthRateLimitFilter.NO_ACCOUNT.equals(account)) store.add("known:" + pair, KNOWN_PAIR_TTL_SECONDS, 0, 0, 0);
        } else if (status == 401) {
            long ttl = 2L * Math.max(l.getLockMaxSeconds(), 60);
            store.add("lock-pair:" + pair, ttl, l.getLockThreshold(), l.getLockBaseSeconds(), l.getLockMaxSeconds());
            store.add("lock-account:" + account, ttl, l.getAccountLockThreshold(), l.getLockBaseSeconds(), l.getLockMaxSeconds());
            if (l.getPerIpFailuresPerMinute() > 0) store.add("login-failures:" + ip, 60, 0, 0, 0);
        }
    }
    @Override Verdict beforeRegister(String ip, String account) {
        var r = properties.getRegister(); long retry = store.acquire("register-ip:" + ip, r.getPerIpPerMinute());
        if (account != null) retry = Math.max(retry, store.acquire("register-email:" + account, r.getPerEmailPerMinute()));
        return verdict(retry);
    }
    @Override Verdict beforeSession(String kind, String ip, String session) {
        var r = properties.getRefresh(); long retry = store.acquire("session-ip:" + kind + '|' + ip, r.getPerIpPerMinute());
        if (session != null) retry = Math.max(retry, store.acquire("session:" + kind + '|' + session, r.getPerSessionPerMinute()));
        return verdict(retry);
    }
    @Override Verdict beforeInvite(String ip) {
        var i = properties.getInvite(); long retry = store.peek("invite-failures:" + ip, i.getPerIpFailuresPerMinute(), false);
        return verdict(retry > 0 ? retry : store.acquire("invite-ip:" + ip, i.getPerIpPerMinute()));
    }
    @Override void afterInvite(String ip, int status) {
        if (status == 404 && properties.getInvite().getPerIpFailuresPerMinute() > 0) store.add("invite-failures:" + ip, 60, 0, 0, 0);
    }
}
