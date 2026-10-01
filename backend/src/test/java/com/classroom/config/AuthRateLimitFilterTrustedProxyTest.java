package com.classroom.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R18-02: trusted-proxy hostnames (the compose service name of the ingress) are resolved on a
 * background task into an immutable snapshot. The request path only reads that snapshot - no DNS lookup
 * and no lock - so a name that resolves slowly (or never) can no longer stall login/register/refresh.
 */
class AuthRateLimitFilterTrustedProxyTest {

    private static final String PROXY_IP = "172.20.0.5";
    private static final int LIMIT = 10;

    private ListAppender<ILoggingEvent> appender;
    private Logger filterLogger;

    @BeforeEach
    void captureLogs() {
        filterLogger = (Logger) LoggerFactory.getLogger(AuthRateLimitFilter.class);
        appender = new ListAppender<>();
        appender.start();
        filterLogger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        filterLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("R18-02: the request path never resolves a hostname - X-Real-IP is only trusted once the background refresh ran")
    void requestPathDoesNoDnsLookup() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        AuthRateLimitFilter filter = new AuthRateLimitFilter("127.0.0.1,frontend", host -> {
            lookups.incrementAndGet();
            return new InetAddress[]{InetAddress.getByName(PROXY_IP)};
        });

        // Before the snapshot is refreshed the hostname entry contributes no address: X-Real-IP is
        // ignored and the caller is keyed by its socket address (fail closed), with zero lookups.
        assertClientKeyedBySocket(filter, PROXY_IP);
        assertEquals(0, lookups.get(), "requests must not trigger a DNS lookup");

        filter.refreshTrustedProxies();
        assertEquals(1, lookups.get());

        // Now X-Real-IP from the resolved proxy address is honoured: distinct real clients get their own bucket.
        for (int i = 0; i < LIMIT; i++) {
            assertEquals(200, proxied(filter, PROXY_IP, "10.1.1.1").getStatus());
        }
        assertEquals(429, proxied(filter, PROXY_IP, "10.1.1.1").getStatus());
        assertEquals(200, proxied(filter, PROXY_IP, "10.1.1.2").getStatus());
        assertEquals(1, lookups.get(), "still exactly one lookup: requests never resolve names");
    }

    @Test
    @DisplayName("R18-02: a resolver that hangs never blocks or slows a request (no lock, no lookup on the request thread)")
    void slowResolverDoesNotStallRequests() throws Exception {
        CountDownLatch resolverEntered = new CountDownLatch(1);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        AuthRateLimitFilter filter = new AuthRateLimitFilter("127.0.0.1,frontend", host -> {
            resolverEntered.countDown();
            try {
                releaseResolver.await(30, TimeUnit.SECONDS); // simulate a multi-second DNS timeout
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new UnknownHostException(host);
        });

        Thread background = new Thread(filter::refreshTrustedProxies, "test-refresh");
        background.setDaemon(true);
        background.start();
        try {
            assertTrue(resolverEntered.await(5, TimeUnit.SECONDS), "background refresh should be inside the resolver");

            for (int i = 0; i < 5; i++) {
                long started = System.nanoTime();
                MockHttpServletResponse response = proxied(filter, "192.0.2.1", "10.9.9." + i);
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertEquals(200, response.getStatus());
                assertTrue(elapsedMs < 500, "request took " + elapsedMs + " ms while the resolver was blocked");
            }
        } finally {
            releaseResolver.countDown();
            background.join(5000);
        }
    }

    @Test
    @DisplayName("R18-02: an unresolvable name is skipped and reported once; loopback stays trusted; it is picked up when it starts resolving")
    void unresolvableNameIsLoggedOnceAndSkipped() throws Exception {
        AtomicBoolean resolvable = new AtomicBoolean(false);
        AuthRateLimitFilter filter = new AuthRateLimitFilter("127.0.0.1,0:0:0:0:0:0:0:1,nginx", host -> {
            if (!resolvable.get()) throw new UnknownHostException(host);
            return new InetAddress[]{InetAddress.getByName(PROXY_IP)};
        });

        filter.refreshTrustedProxies();
        filter.refreshTrustedProxies();
        filter.refreshTrustedProxies();

        long warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
        assertEquals(1, warnings, "the same unresolvable host must be reported once, not on every refresh");
        assertTrue(appender.list.get(0).getFormattedMessage().contains("nginx"));

        // Skipped, not fatal: the literal loopback entries are still trusted...
        for (int i = 0; i < LIMIT; i++) {
            assertEquals(200, proxied(filter, "127.0.0.1", "10.2.2.2").getStatus());
        }
        assertEquals(429, proxied(filter, "127.0.0.1", "10.2.2.2").getStatus());
        assertEquals(200, proxied(filter, "0:0:0:0:0:0:0:1", "10.2.2.3").getStatus());
        // ...but the missing host's address is not.
        assertClientKeyedBySocket(filter, PROXY_IP);

        resolvable.set(true);
        filter.refreshTrustedProxies();
        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.INFO
                && e.getFormattedMessage().contains("now resolves")));
        // The socket bucket above is exhausted, but with X-Real-IP now trusted a fresh real client passes.
        for (int i = 0; i < LIMIT; i++) {
            assertEquals(200, proxied(filter, PROXY_IP, "10.3.3.3").getStatus());
        }
        assertEquals(429, proxied(filter, PROXY_IP, "10.3.3.3").getStatus());
        assertEquals(200, proxied(filter, PROXY_IP, "10.3.3.4").getStatus());
    }

    @Test
    @DisplayName("R18-02: the built-in default trusts loopback only - no 'nginx' name that does not exist on the compose network")
    void defaultTrustedProxiesAreLoopbackOnly() throws Exception {
        Value value = AuthRateLimitFilter.class.getConstructor(String.class, AuthRateLimitProperties.class).getParameters()[0].getAnnotation(Value.class);
        String defaults = value.value();
        assertFalse(defaults.contains("nginx"), defaults);
        assertTrue(defaults.contains("127.0.0.1") && defaults.contains("0:0:0:0:0:0:0:1"), defaults);
    }

    @Test
    @DisplayName("R18-02: IP literals are recognised without DNS, hostnames are not")
    void ipLiteralDetection() {
        assertEquals("127.0.0.1", AuthRateLimitFilter.normalizeIpLiteral("127.0.0.1"));
        assertEquals("0:0:0:0:0:0:0:1", AuthRateLimitFilter.normalizeIpLiteral("0:0:0:0:0:0:0:1"));
        assertEquals("0:0:0:0:0:0:0:1", AuthRateLimitFilter.normalizeIpLiteral("::1"));
        assertNull(AuthRateLimitFilter.normalizeIpLiteral("frontend"));
        assertNull(AuthRateLimitFilter.normalizeIpLiteral("nginx.internal"));
        assertNull(AuthRateLimitFilter.normalizeIpLiteral("999.1.1.1"));
    }

    @Test
    @DisplayName("R18-02: an unresolved name is retried within seconds (right after compose up the frontend does not exist yet); a resolved one settles to the slow interval")
    void unresolvedNamesAreRetriedQuickly() throws Exception {
        AtomicBoolean resolvable = new AtomicBoolean(false);
        AuthRateLimitFilter filter = new AuthRateLimitFilter("127.0.0.1,frontend", host -> {
            if (!resolvable.get()) throw new UnknownHostException(host);
            return new InetAddress[]{InetAddress.getByName(PROXY_IP)};
        });

        filter.refreshTrustedProxies();
        assertEquals(5, filter.nextRefreshDelaySeconds(), "unresolved -> retry quickly");

        resolvable.set(true);
        filter.refreshTrustedProxies();
        assertEquals(30, filter.nextRefreshDelaySeconds(), "resolved -> slow steady-state interval");
    }

    @Test
    @DisplayName("R18-02: the background refresher starts only when there are hostnames, and stops cleanly")
    void refresherLifecycle() throws Exception {
        CountDownLatch resolved = new CountDownLatch(1);
        AuthRateLimitFilter withHost = new AuthRateLimitFilter("frontend", host -> {
            resolved.countDown();
            return new InetAddress[]{InetAddress.getByName(PROXY_IP)};
        });
        withHost.startTrustedProxyRefresh();
        try {
            assertTrue(resolved.await(5, TimeUnit.SECONDS), "the scheduled task should resolve the name right away");
            long deadline = System.currentTimeMillis() + 5000;
            boolean trusted = false;
            for (int probe = 0; !trusted && System.currentTimeMillis() < deadline; probe++) {
                trusted = honoursXRealIp(withHost, "10.77." + probe + ".1", "10.77." + probe + ".2");
                if (!trusted) Thread.sleep(20);
            }
            assertTrue(trusted, "snapshot should contain the resolved proxy address");
        } finally {
            withHost.stopTrustedProxyRefresh();
        }

        AtomicInteger lookups = new AtomicInteger();
        AuthRateLimitFilter literalsOnly = new AuthRateLimitFilter("127.0.0.1", host -> {
            lookups.incrementAndGet();
            return new InetAddress[0];
        });
        literalsOnly.startTrustedProxyRefresh();
        literalsOnly.stopTrustedProxyRefresh();
        assertEquals(0, lookups.get());
    }

    /**
     * True when the proxy address is trusted: after {@code realA} exhausts its budget, a different {@code realB}
     * still passes (own bucket) - behind an untrusted socket both would share one bucket and get a 429.
     */
    private boolean honoursXRealIp(AuthRateLimitFilter filter, String realA, String realB) throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            proxied(filter, PROXY_IP, realA);
        }
        return proxied(filter, PROXY_IP, realB).getStatus() == 200;
    }

    private void assertClientKeyedBySocket(AuthRateLimitFilter filter, String socketAddress) throws Exception {
        // Untrusted socket: every request lands in the socket address's bucket regardless of X-Real-IP,
        // so the 11th request is limited even though each carries a different forged header.
        for (int i = 0; i < LIMIT; i++) {
            assertEquals(200, proxied(filter, socketAddress, "1.2.3." + i).getStatus());
        }
        assertEquals(429, proxied(filter, socketAddress, "9.9.9.9").getStatus());
    }

    private static MockHttpServletResponse proxied(AuthRateLimitFilter filter, String socketAddress, String realIp) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setServletPath("/api/v1/auth/login");
        request.setRemoteAddr(socketAddress);
        request.addHeader("X-Real-IP", realIp);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, new MockFilterChain());
        return response;
    }
}
