package com.classroom.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * R20-02 through the REAL servlet chain (TrustedForwardedHeaderFilter -> Spring Security -> AuthRateLimitFilter -> AuthController ->
 * AuthService -> H2): the filter reads the JSON body to find the account and must replay it to the controller, and it decides from
 * the real HTTP status the controller produced. A class behind ONE address (X-Real-IP from the trusted loopback peer, exactly what
 * nginx sends) signs in; one account under attack is throttled; the class is not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthRateLimitChainTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired private AuthRateLimitFilter authFilter;
    @Autowired private AuthRateLimitProperties rateProperties;

    private MockHttpServletResponse send(String path, String clientIp, String email, String password) throws Exception {
        String body = path.endsWith("register")
                ? JSON.writeValueAsString(java.util.Map.of("email", email, "password", password, "fullName", "Hoc Vien"))
                : JSON.writeValueAsString(java.util.Map.of("email", email, "password", password));
        return mockMvc.perform(post(path)
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1"); // the trusted proxy (nginx) ...
                            return request;
                        })
                        .header("X-Real-IP", clientIp)          // ... naming the real client
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
    }

    private static String uniqueIp() {
        int n = Math.abs(UUID.randomUUID().hashCode());
        return "198.18." + ((n >> 8) & 0xFF) + "." + (n & 0xFF);
    }

    @Test
    @DisplayName("a class behind ONE address: 25 students register and log in within the same minute - every request succeeds (was 10 per minute)")
    void classBehindOneAddress() throws Exception {
        String nat = uniqueIp();
        String run = UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 25; i++) {
            assertEquals(200, send("/api/v1/auth/register", nat, "nat" + i + "." + run + "@example.com", "Passw0rd!x").getStatus(), "register " + i);
        }
        for (int i = 0; i < 25; i++) {
            MockHttpServletResponse login = send("/api/v1/auth/login", nat, "NAT" + i + "." + run + "@example.com", "Passw0rd!x");
            assertEquals(200, login.getStatus(), "login " + i);
            assertTrue(JSON.readTree(login.getContentAsString()).path("data").path("token").asText().length() > 20, "the controller received the replayed body");
        }
    }

    @Test
    @DisplayName("brute force on one account: 5 wrong passwords are checked, the 6th onwards is a 429 with Retry-After - even with the right password - while a classmate on the same address logs in")
    void bruteForceIsThrottledWithoutHurtingClassmates() throws Exception {
        String nat = uniqueIp();
        String run = UUID.randomUUID().toString().substring(0, 8);
        String victim = "victim." + run + "@example.com";
        String classmate = "mate." + run + "@example.com";
        assertEquals(200, send("/api/v1/auth/register", nat, victim, "Passw0rd!x").getStatus());
        assertEquals(200, send("/api/v1/auth/register", nat, classmate, "Passw0rd!x").getStatus());

        for (int i = 0; i < 5; i++) {
            assertEquals(401, send("/api/v1/auth/login", nat, victim, "guess" + i).getStatus(), "guess " + i);
        }
        MockHttpServletResponse locked = send("/api/v1/auth/login", nat, victim, "guess5");
        assertEquals(429, locked.getStatus());
        // The throttle counts whole epoch seconds, so a second boundary between the 5th and the 6th request legitimately makes it 59.
        assertTrue(java.util.List.of("59", "60").contains(locked.getHeader("Retry-After")), "Retry-After=" + locked.getHeader("Retry-After"));
        JsonNode error = JSON.readTree(locked.getContentAsString()).path("error");
        assertEquals("RATE_LIMITED", error.path("code").asText());
        assertEquals(429, send("/api/v1/auth/login", nat, victim, "Passw0rd!x").getStatus(), "the right password is refused during the lock");

        assertEquals(200, send("/api/v1/auth/login", nat, classmate, "Passw0rd!x").getStatus(), "a classmate is unaffected");
        assertEquals(200, send("/api/v1/auth/login", uniqueIp(), victim, "Passw0rd!x").getStatus(), "the same account from another address is unaffected (pair lock)");
    }

    @Test
    @DisplayName("an unknown e-mail and a known one are throttled identically (no account enumeration)")
    void unknownAndKnownEmailsBehaveTheSame() throws Exception {
        // Hold the clock fixed: sequential HTTP calls can otherwise straddle a second boundary.
        Object original = org.springframework.test.util.ReflectionTestUtils.getField(authFilter, "throttle");
        org.springframework.test.util.ReflectionTestUtils.setField(authFilter, "throttle", new AuthThrottle(rateProperties, () -> 1000L));
        try {
        String ip = uniqueIp();
        String run = UUID.randomUUID().toString().substring(0, 8);
        String known = "known." + run + "@example.com";
        assertEquals(200, send("/api/v1/auth/register", ip, known, "Passw0rd!x").getStatus());
        for (int i = 0; i < 7; i++) {
            MockHttpServletResponse a = send("/api/v1/auth/login", ip, known, "bad" + i);
            MockHttpServletResponse b = send("/api/v1/auth/login", ip, "ghost." + run + "@example.com", "bad" + i);
            assertEquals(a.getStatus(), b.getStatus(), "attempt " + i);
            assertEquals(a.getHeader("Retry-After"), b.getHeader("Retry-After"));
        }
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(authFilter, "throttle", original); }
    }

    @Test
    @DisplayName("the rate limiter does not consume a request body the controller needs: a login with a malformed body still gets the controller's 400")
    void malformedBodyStillReachesTheController() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        })
                        .header("X-Real-IP", uniqueIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json"))
                .andReturn().getResponse();
        assertEquals(400, response.getStatus());
    }
}
