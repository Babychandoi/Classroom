package com.classroom.config;

import com.classroom.config.AuthFilterFixtures.Clock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static com.classroom.config.AuthFilterFixtures.MAPPER;
import static com.classroom.config.AuthFilterFixtures.filter;
import static com.classroom.config.AuthFilterFixtures.run;
import static com.classroom.config.AuthFilterFixtures.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-19: the per-client-address throttle of the two invite-code endpoints (GET /classes/invites/{code} preview and POST .../join), so codes cannot
 * be enumerated. It sits in the same {@link AuthRateLimitFilter} family as login / register / refresh and is configured the same way
 * ({@code app.security.rate-limit.invite.*}). Valid answers never count toward the enumeration ceiling; "no such usable code" (404) does.
 */
class InviteRateLimitTest {

    private static final String PREVIEW = "/api/v1/classes/invites/";
    private static final String CODE = "AbCdEfGhIjKlMnOpQrStUvWxYz012345";

    private final Clock clock = new Clock();

    private static MockHttpServletRequest preview(String ip, String code) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PREVIEW + code);
        request.setServletPath(PREVIEW + code);
        request.setRemoteAddr(ip);
        return request;
    }

    private static MockHttpServletRequest join(String ip, String code) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PREVIEW + code + "/join");
        request.setServletPath(PREVIEW + code + "/join");
        request.setRemoteAddr(ip);
        return request;
    }

    private AuthRateLimitFilter filterWith(int perIp, int failures) {
        AuthRateLimitProperties props = new AuthRateLimitProperties();
        props.getInvite().setPerIpPerMinute(perIp);
        props.getInvite().setPerIpFailuresPerMinute(failures);
        return filter("", props, clock);
    }

    @Test
    @DisplayName("defaults: 600 calls and 30 unknown-code answers per address per minute")
    void defaults() {
        AuthRateLimitProperties props = new AuthRateLimitProperties();
        assertEquals(600, props.getInvite().getPerIpPerMinute());
        assertEquals(30, props.getInvite().getPerIpFailuresPerMinute());
    }

    @Test
    @DisplayName("enumeration: after 30 'no such code' (404) answers the 31st call from that address is a 429 with Retry-After - even with a valid code")
    void guessingIsThrottled() throws Exception {
        AuthRateLimitFilter filter = filterWith(600, 30);
        for (int i = 0; i < 30; i++) {
            assertEquals(404, run(filter, preview("203.0.113.5", "guess-" + i + "-xxxxxxxxxxxxxxxx"), status(404)).getStatus(), "guess " + i);
        }
        MockHttpServletResponse blocked = run(filter, preview("203.0.113.5", CODE), status(200));
        assertEquals(429, blocked.getStatus(), "the valid code is refused too while the address is locked out");
        assertTrue(Long.parseLong(blocked.getHeader("Retry-After")) >= 1);
        JsonNode error = MAPPER.readTree(blocked.getContentAsString()).path("error");
        assertEquals("RATE_LIMITED", error.path("code").asText());

        // join shares the address bucket
        assertEquals(429, run(filter, join("203.0.113.5", CODE), status(200)).getStatus());
        // another address is untouched
        assertEquals(200, run(filter, preview("203.0.113.6", CODE), status(200)).getStatus());
        // a minute later the address is free again
        clock.advance(61);
        assertEquals(200, run(filter, preview("203.0.113.5", CODE), status(200)).getStatus());
    }

    @Test
    @DisplayName("valid answers never count toward the enumeration ceiling: a class joining from one address (400 calls) is not throttled")
    void aClassBehindOneAddress() throws Exception {
        AuthRateLimitFilter filter = filterWith(600, 30);
        for (int i = 0; i < 200; i++) {
            assertEquals(200, run(filter, preview("198.51.100.9", CODE), status(200)).getStatus(), "preview " + i);
            assertEquals(200, run(filter, join("198.51.100.9", CODE), status(200)).getStatus(), "join " + i);
        }
        // 402 (payment needed) and 403 (blocked) are real, known codes: not enumeration either
        assertEquals(402, run(filter, preview("198.51.100.9", CODE), status(402)).getStatus());
        assertEquals(403, run(filter, join("198.51.100.9", CODE), status(403)).getStatus());
    }

    @Test
    @DisplayName("the overall ceiling bounds database work: the call after perIpPerMinute is a 429 whatever its outcome, and a window later it works again")
    void overallCeiling() throws Exception {
        AuthRateLimitFilter filter = filterWith(5, 30);
        for (int i = 0; i < 5; i++) {
            assertEquals(200, run(filter, preview("192.0.2.1", CODE), status(200)).getStatus());
        }
        assertEquals(429, run(filter, preview("192.0.2.1", CODE), status(200)).getStatus());
        assertEquals(200, run(filter, preview("192.0.2.2", CODE), status(200)).getStatus());
        clock.advance(60);
        assertEquals(200, run(filter, preview("192.0.2.1", CODE), status(200)).getStatus());
    }

    @Test
    @DisplayName("a limit <= 0 disables that single check")
    void limitsCanBeDisabled() throws Exception {
        AuthRateLimitFilter off = filterWith(0, 0);
        for (int i = 0; i < 100; i++) {
            assertEquals(404, run(off, preview("192.0.2.77", "nope-" + i + "-xxxxxxxxxxxxxxxxxx"), status(404)).getStatus());
        }
        AuthRateLimitFilter noFailureCeiling = filterWith(1000, 0);
        for (int i = 0; i < 100; i++) {
            assertEquals(404, run(noFailureCeiling, preview("192.0.2.78", "nope-" + i + "-xxxxxxxxxxxxxxxxxx"), status(404)).getStatus());
        }
    }

    @Test
    @DisplayName("behind a trusted proxy each real client (X-Real-IP) has its own budget; an untrusted socket cannot forge one")
    void clientAddressResolution() throws Exception {
        AuthRateLimitFilter trusted = filter("203.0.113.7", withFailures(3), clock);
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest r = preview("203.0.113.7", "miss-" + i + "-xxxxxxxxxxxxxxxxxxxx");
            r.addHeader("X-Real-IP", "10.0.0.1");
            assertEquals(404, run(trusted, r, status(404)).getStatus());
        }
        MockHttpServletRequest sameClient = preview("203.0.113.7", CODE);
        sameClient.addHeader("X-Real-IP", "10.0.0.1");
        assertEquals(429, run(trusted, sameClient, status(200)).getStatus());
        MockHttpServletRequest otherClient = preview("203.0.113.7", CODE);
        otherClient.addHeader("X-Real-IP", "10.0.0.2");
        assertEquals(200, run(trusted, otherClient, status(200)).getStatus());

        AuthRateLimitFilter untrusted = filter("", withFailures(3), clock);
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest r = preview("198.51.100.50", "miss-" + i + "-xxxxxxxxxxxxxxxxxxxx");
            r.addHeader("X-Real-IP", "9.9.9." + i); // forged, must be ignored
            assertEquals(404, run(untrusted, r, status(404)).getStatus());
        }
        MockHttpServletRequest forged = preview("198.51.100.50", CODE);
        forged.addHeader("X-Real-IP", "9.9.9.99");
        assertEquals(429, run(untrusted, forged, status(200)).getStatus());
    }

    private AuthRateLimitProperties withFailures(int failures) {
        AuthRateLimitProperties props = new AuthRateLimitProperties();
        props.getInvite().setPerIpFailuresPerMinute(failures);
        return props;
    }

    @Test
    @DisplayName("only the two code endpoints are throttled: other methods / paths under the prefix and the rest of /classes pass untouched")
    void scopeOfTheFilter() throws Exception {
        AuthRateLimitFilter filter = filterWith(1, 1);
        assertTrue(AuthRateLimitFilter.isInviteEndpoint("GET", PREVIEW + CODE));
        assertTrue(AuthRateLimitFilter.isInviteEndpoint("POST", PREVIEW + CODE + "/join"));
        assertFalse(AuthRateLimitFilter.isInviteEndpoint("POST", PREVIEW + CODE), "POST on the preview path is not an invite call");
        assertFalse(AuthRateLimitFilter.isInviteEndpoint("GET", PREVIEW + CODE + "/join"));
        assertFalse(AuthRateLimitFilter.isInviteEndpoint("GET", PREVIEW + CODE + "/other"));
        assertFalse(AuthRateLimitFilter.isInviteEndpoint("GET", PREVIEW));
        assertFalse(AuthRateLimitFilter.isInviteEndpoint("GET", "/api/v1/classes/abc"));
        assertFalse(AuthRateLimitFilter.isInviteEndpoint("GET", "/api/v1/classes/abc/invites"));

        // the management endpoints (/classes/{id}/invites) are authenticated owner calls, not code lookups: never throttled here
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest list = new MockHttpServletRequest("GET", "/api/v1/classes/abc/invites");
            list.setServletPath("/api/v1/classes/abc/invites");
            list.setRemoteAddr("192.0.2.9");
            assertEquals(200, run(filter, list, status(200)).getStatus());
        }
        // OPTIONS (CORS preflight) under the prefix passes
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest options = new MockHttpServletRequest("OPTIONS", PREVIEW + CODE);
            options.setServletPath(PREVIEW + CODE);
            options.setRemoteAddr("192.0.2.9");
            assertEquals(200, run(filter, options, status(200)).getStatus());
        }
        assertNull(run(filter, preview("192.0.2.10", CODE), status(200)).getHeader("Retry-After"));
    }

    @Test
    @DisplayName("memory stays bounded: tracked keys never exceed the store cap however many distinct addresses call")
    void boundedMemory() throws Exception {
        AuthRateLimitProperties props = new AuthRateLimitProperties();
        props.setMaxTrackedKeys(100);
        AuthRateLimitFilter filter = filter("", props, clock);
        for (int i = 0; i < 2000; i++) {
            run(filter, preview("10." + (i / 250) + "." + ((i / 25) % 10) + "." + (i % 250), "miss-" + i + "-xxxxxxxxxxxxxxxxxxxx"), status(404));
        }
        assertTrue(filter.trackedKeys() <= AuthThrottle.stores() * 2 * 100, "tracked=" + filter.trackedKeys());
    }
}
