package com.classroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClassroomApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
        // Confirms Spring application context initializes cleanly with test profile
    }

    @Test
    @DisplayName("Finding 1: Public endpoints allow unauthenticated access")
    void testPublicEndpointsPermitted() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Finding 1: Protected API endpoints reject unauthenticated access with 401")
    void testProtectedApiRejectsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Public posts can be read anonymously, while creating a post still requires authentication")
    void publicFeedReadIsAnonymousButPostCreationIsProtected() throws Exception {
        // R5-04: the feed endpoint now 404s for a class that does not exist (matching the
        // about/products endpoints) instead of silently returning an empty list. "public-feed-test"
        // is not a seeded class id, so a 404 here — reached past Spring Security's filter chain
        // without a 401 — is exactly what proves the GET is permitted anonymously; only the POST
        // (which requires a real authenticated principal before any class lookup) must 401.
        mockMvc.perform(get("/api/v1/classes/public-feed-test/posts"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/classes/public-feed-test/posts")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Finding 1: Unknown/unmapped non-API routes reject unauthenticated access (fail-closed)")
    void testUnmappedNonApiRouteRejectsUnauthenticated() throws Exception {
        mockMvc.perform(get("/unmapped-non-api-path"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Finding 1: Unknown/unmapped non-API routes reject authenticated access by default (denyAll)")
    @WithMockUser
    void testUnmappedNonApiRouteDeniedForAuthenticated() throws Exception {
        mockMvc.perform(get("/unmapped-non-api-path"))
                .andExpect(status().isForbidden());
    }
}
