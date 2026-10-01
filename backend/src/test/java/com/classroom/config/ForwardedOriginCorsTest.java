package com.classroom.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R19-02: a same-origin browser POST carries an Origin header. Spring only treats it as same-origin when
 * Origin equals request.getScheme()/getServerName()/getServerPort(); behind nginx the backend used to see
 * "Host: localhost" (port dropped) so every origin other than the hardcoded localhost:3000 was rejected with
 * 403 "Invalid CORS request" (drill stack :13000, custom FRONTEND_PORT, LAN IP, real domain).
 *
 * <p>These tests run the full servlet filter chain (TrustedForwardedHeaderFilter -> Spring Security CORS filter ->
 * AuthController) and emulate exactly what the backend receives from the bundled nginx: a request whose own Host
 * is the internal hop ("localhost", no port) plus X-Forwarded-Host/Proto/Port set to the browser's origin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ForwardedOriginCorsTest {

    private static final java.util.concurrent.atomic.AtomicInteger UNIQUE = new java.util.concurrent.atomic.AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    /** A request as the backend sees it after nginx: internal Host (no port), peer = loopback (a trusted proxy). */
    private static MockHttpServletRequestBuilder loginFromProxy(String origin) {
        return post("/api/v1/auth/login")
                .with(request -> {
                    request.setServerName("localhost");
                    request.setServerPort(80);
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                })
                .header("Origin", origin)
                .contentType(MediaType.APPLICATION_JSON)
                // a distinct e-mail per request: repeated failures for one (IP, e-mail) pair are (rightly) throttled since R20-02
                .content("{\"email\":\"nobody" + UNIQUE.incrementAndGet() + "@example.com\",\"password\":\"wrong-password\"}");
    }

    @Test
    @DisplayName("R19-02: without forwarded headers the drill origin is cross-origin and rejected (the original bug)")
    void originOutsideAllowListIsRejectedWithoutForwardedHeaders() throws Exception {
        mockMvc.perform(loginFromProxy("http://localhost:13000"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("R19-02: same-origin POST through the proxy on a non-default port is no longer treated as cross-origin")
    void sameOriginPostOnCustomPortReachesTheController() throws Exception {
        mockMvc.perform(loginFromProxy("http://localhost:13000")
                        .header("X-Forwarded-Host", "localhost:13000")
                        .header("X-Forwarded-Proto", "http")
                        .header("X-Forwarded-Port", "13000"))
                .andExpect(status().isUnauthorized()); // wrong credentials: past CORS, rejected by the login itself
    }

    @Test
    @DisplayName("R19-02: LAN IP and a host without a port work through X-Forwarded-Host alone")
    void lanIpAndPortlessHostAreSameOrigin() throws Exception {
        mockMvc.perform(loginFromProxy("http://192.168.1.20:3000").header("X-Forwarded-Host", "192.168.1.20:3000"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(loginFromProxy("http://classroom.internal").header("X-Forwarded-Host", "classroom.internal"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("R19-02: an https public origin (TLS terminated in front of the stack) is same-origin when the proxy says so")
    void httpsPublicOriginIsSameOrigin() throws Exception {
        mockMvc.perform(loginFromProxy("https://classroom.example.com")
                        .header("X-Forwarded-Host", "classroom.example.com")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isUnauthorized());
        // ... but not when only the scheme differs from what the proxy reported
        mockMvc.perform(loginFromProxy("http://classroom.example.com")
                        .header("X-Forwarded-Host", "classroom.example.com")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("R19-02: a foreign Origin is still rejected, whatever the forwarded host says about the real one")
    void foreignOriginStillRejected() throws Exception {
        mockMvc.perform(loginFromProxy("https://evil.example")
                        .header("X-Forwarded-Host", "localhost:13000")
                        .header("X-Forwarded-Proto", "http")
                        .header("X-Forwarded-Port", "13000"))
                .andExpect(status().isForbidden());
        mockMvc.perform(loginFromProxy("http://localhost:13001")
                        .header("X-Forwarded-Host", "localhost:13000"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("R19-02: X-Forwarded-* from a peer that is NOT a trusted proxy is ignored (direct hits on the backend port cannot spoof the host)")
    void forwardedHeadersFromUntrustedPeerAreIgnored() throws Exception {
        mockMvc.perform(loginFromProxy("https://evil.example")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        })
                        .header("X-Forwarded-Host", "evil.example")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isForbidden());
        mockMvc.perform(loginFromProxy("http://localhost:13000")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        })
                        .header("X-Forwarded-Host", "localhost:13000"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("R19-02: the configured allow-list keeps working for genuinely cross-origin callers (e.g. the Vite dev server)")
    void allowListedCrossOriginStillAllowed() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .with(request -> {
                            request.setServerName("localhost");
                            request.setServerPort(8080);
                            return request;
                        })
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        mockMvc.perform(options("/api/v1/auth/login")
                        .with(request -> {
                            request.setServerName("localhost");
                            request.setServerPort(8080);
                            return request;
                        })
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
