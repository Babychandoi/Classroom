package com.classroom.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * R20-13 through the REAL servlet chain: MockMvc leaves {@code getServletPath()} empty, which is exactly the condition under which the
 * exam throttle (ExamRateLimitFilter) used to match nothing and let every request through. A signed-in student hammering the start-attempt
 * endpoint must reach the 429 after the per-window budget.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExamRateLimitChainTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int START_LIMIT = 20;

    @Autowired
    private MockMvc mockMvc;

    private String registerAndGetToken() throws Exception {
        String email = "chain." + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String body = JSON.writeValueAsString(Map.of("email", email, "password", "Passw0rd!x", "fullName", "Hoc Vien"));
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        })
                        .header("X-Real-IP", "198.18." + (Math.abs(UUID.randomUUID().hashCode()) % 250) + "." + (1 + Math.abs(email.hashCode()) % 250))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        String token = JSON.readTree(response).path("data").path("token").asText();
        assertNotNull(token);
        return token;
    }

    @Test
    @DisplayName("R20-13: start-attempt requests through MockMvc (empty servlet path) are throttled after the per-window budget")
    void startAttemptIsThrottledThroughTheRealChain() throws Exception {
        String token = registerAndGetToken();
        for (int i = 0; i < START_LIMIT; i++) {
            int status = mockMvc.perform(post("/api/v1/exams/" + UUID.randomUUID() + "/attempts")
                            .header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus();
            assertEquals(404, status, "within budget the request reaches the controller (unknown exam -> 404), start #" + (i + 1));
        }
        var blocked = mockMvc.perform(post("/api/v1/exams/" + UUID.randomUUID() + "/attempts")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertEquals(429, blocked.getStatus(), "the 21st start in the window must be throttled, not fail open");
        assertNotNull(blocked.getHeader("Retry-After"));
    }
}
