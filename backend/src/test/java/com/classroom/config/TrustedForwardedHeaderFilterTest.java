package com.classroom.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R19-02: parsing and trust rules of {@link TrustedForwardedHeaderFilter}. */
class TrustedForwardedHeaderFilterTest {

    private static final String PROXY = "172.20.0.5";

    private final TrustedForwardedHeaderFilter filter =
            new TrustedForwardedHeaderFilter(new AuthRateLimitFilter("127.0.0.1," + PROXY));

    /** Runs the filter and returns the request the downstream chain (the CORS filter) actually sees. */
    private HttpServletRequest seenBy(MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }

    private static MockHttpServletRequest fromPeer(String peer) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setServerName("localhost");
        request.setServerPort(80);
        request.setRemoteAddr(peer);
        return request;
    }

    @Test
    @DisplayName("R19-02: host:port, scheme and port from a trusted proxy replace the server name/port/scheme")
    void trustedProxyOverridesSchemeHostAndPort() throws Exception {
        MockHttpServletRequest request = fromPeer(PROXY);
        request.addHeader("X-Forwarded-Host", "Localhost:13000");
        request.addHeader("X-Forwarded-Proto", "HTTPS");
        request.addHeader("X-Forwarded-Port", "13000");

        HttpServletRequest seen = seenBy(request);

        assertEquals("localhost", seen.getServerName());
        assertEquals(13000, seen.getServerPort());
        assertEquals("https", seen.getScheme());
        assertTrue(seen.isSecure());
    }

    @Test
    @DisplayName("R19-02: only scheme/host/port change - the remote address and every header stay as received (X-Real-IP trust unaffected)")
    void remoteAddressAndHeadersAreUntouched() throws Exception {
        MockHttpServletRequest request = fromPeer(PROXY);
        request.addHeader("X-Forwarded-Host", "classroom.example.com");
        request.addHeader("X-Forwarded-For", "9.9.9.9");
        request.addHeader("X-Real-IP", "10.1.1.1");

        HttpServletRequest seen = seenBy(request);

        assertEquals(PROXY, seen.getRemoteAddr(), "X-Forwarded-For must never replace the socket address");
        assertEquals("10.1.1.1", seen.getHeader("X-Real-IP"));
        assertEquals("9.9.9.9", seen.getHeader("X-Forwarded-For"));
        assertEquals("classroom.example.com", seen.getServerName());
        assertEquals(80, seen.getServerPort());
    }

    @Test
    @DisplayName("R19-02: headers from an untrusted peer are ignored and the request is passed through untouched")
    void untrustedPeerIsIgnored() throws Exception {
        MockHttpServletRequest request = fromPeer("203.0.113.9");
        request.addHeader("X-Forwarded-Host", "evil.example");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Port", "443");

        assertSame(request, seenBy(request));
    }

    @Test
    @DisplayName("R19-02: no forwarded headers -> request passes through unwrapped")
    void noForwardedHeaders() throws Exception {
        MockHttpServletRequest request = fromPeer(PROXY);
        assertSame(request, seenBy(request));
    }

    @Test
    @DisplayName("R19-02: a bare X-Forwarded-Host means the default port of the scheme; X-Forwarded-Port fills a missing port")
    void portResolution() throws Exception {
        MockHttpServletRequest bareHost = fromPeer(PROXY);
        bareHost.setServerPort(8080);
        bareHost.addHeader("X-Forwarded-Host", "classroom.example.com");
        assertEquals(80, seenBy(bareHost).getServerPort());

        MockHttpServletRequest bareHttpsHost = fromPeer(PROXY);
        bareHttpsHost.addHeader("X-Forwarded-Host", "classroom.example.com");
        bareHttpsHost.addHeader("X-Forwarded-Proto", "https");
        assertEquals(443, seenBy(bareHttpsHost).getServerPort());

        MockHttpServletRequest portHeader = fromPeer(PROXY);
        portHeader.addHeader("X-Forwarded-Host", "classroom.example.com");
        portHeader.addHeader("X-Forwarded-Port", "8443");
        assertEquals(8443, seenBy(portHeader).getServerPort());

        MockHttpServletRequest hostPortWins = fromPeer(PROXY);
        hostPortWins.addHeader("X-Forwarded-Host", "classroom.example.com:9000");
        hostPortWins.addHeader("X-Forwarded-Port", "8443");
        assertEquals(9000, seenBy(hostPortWins).getServerPort());

        MockHttpServletRequest onlyProto = fromPeer(PROXY);
        onlyProto.addHeader("X-Forwarded-Proto", "https");
        HttpServletRequest seen = seenBy(onlyProto);
        assertEquals("localhost", seen.getServerName());
        assertEquals(443, seen.getServerPort());
    }

    @Test
    @DisplayName("R19-02: comma-separated chains use the first element; IPv6 literals keep their brackets")
    void firstElementAndIpv6() throws Exception {
        MockHttpServletRequest chain = fromPeer(PROXY);
        chain.addHeader("X-Forwarded-Host", "classroom.example.com, internal-lb:8080");
        chain.addHeader("X-Forwarded-Proto", "https, http");
        HttpServletRequest seenChain = seenBy(chain);
        assertEquals("classroom.example.com", seenChain.getServerName());
        assertEquals("https", seenChain.getScheme());

        MockHttpServletRequest ipv6 = fromPeer(PROXY);
        ipv6.addHeader("X-Forwarded-Host", "[::1]:3000");
        HttpServletRequest seenIpv6 = seenBy(ipv6);
        assertEquals("[::1]", seenIpv6.getServerName());
        assertEquals(3000, seenIpv6.getServerPort());
    }

    @Test
    @DisplayName("R19-02: malformed values are ignored, never half-applied")
    void malformedValuesAreIgnored() throws Exception {
        for (String badHost : new String[]{"evil.example/path", "bad host", "a@b.example", "host:notaport", "host:99999", "[::1", "host:0", "-", "http://evil.example"}) {
            MockHttpServletRequest request = fromPeer(PROXY);
            request.addHeader("X-Forwarded-Host", badHost);
            assertSame(request, seenBy(request), "should ignore X-Forwarded-Host=" + badHost);
        }
        assertNull(TrustedForwardedHeaderFilter.parseScheme("ftp"));
        assertNull(TrustedForwardedHeaderFilter.parseScheme("javascript"));
        assertEquals(-1, TrustedForwardedHeaderFilter.parsePort("-1"));
        assertEquals(-1, TrustedForwardedHeaderFilter.parsePort("65536"));
        assertEquals(-1, TrustedForwardedHeaderFilter.parsePort("80a"));
        assertNotNull(TrustedForwardedHeaderFilter.parseHost("classroom.example.com:80"));
    }
}
