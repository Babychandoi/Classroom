package com.classroom.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R12-05: SecurityConfig previously wired a bare HttpStatusEntryPoint(UNAUTHORIZED) (empty-body
 * 401) and left 403 to Spring Security's default handling - neither matched the standard
 * ApiResponse envelope every other error path in the app uses, so the frontend's ApiException
 * parsing (which expects {"error": {code, message, requestId}}) could not make sense of them.
 * These are unit-level (no Spring context needed for two small, dependency-free handlers), but
 * cover the exact contract SecurityConfig now wires them under.
 */
class ApiAuthenticationEntryPointTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("R12-05: ApiAuthenticationEntryPoint writes a 401 with the standard ApiResponse envelope, UTF-8 JSON")
    void writesStandardEnvelopeOn401() throws Exception {
        ApiAuthenticationEntryPoint entryPoint = new ApiAuthenticationEntryPoint(objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new InsufficientAuthenticationException("no auth"));

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(response.getCharacterEncoding().equalsIgnoreCase("UTF-8"));
        var json = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals(false, json.get("success").asBoolean());
        assertEquals("UNAUTHORIZED", json.get("error").get("code").asText());
        assertTrue(json.get("error").has("requestId"));
        assertTrue(json.get("error").get("message").asText().length() > 0);
    }

    @Test
    @DisplayName("R12-05: ApiAuthenticationEntryPoint echoes the caller's X-Request-Id when present")
    void echoesIncomingRequestId() throws Exception {
        ApiAuthenticationEntryPoint entryPoint = new ApiAuthenticationEntryPoint(objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "req-abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new InsufficientAuthenticationException("no auth"));

        var json = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals("req-abc-123", json.get("error").get("requestId").asText());
    }

    @Test
    @DisplayName("R12-05: ApiAccessDeniedHandler writes a 403 with the standard ApiResponse envelope, UTF-8 JSON")
    void writesStandardEnvelopeOn403() throws Exception {
        ApiAccessDeniedHandler handler = new ApiAccessDeniedHandler(objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("denied"));

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(response.getCharacterEncoding().equalsIgnoreCase("UTF-8"));
        var json = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals(false, json.get("success").asBoolean());
        assertEquals("FORBIDDEN", json.get("error").get("code").asText());
        assertTrue(json.get("error").has("requestId"));
    }
}
