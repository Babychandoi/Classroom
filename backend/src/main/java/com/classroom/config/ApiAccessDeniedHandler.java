package com.classroom.config;

import com.classroom.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * R12-05: companion to {@link ApiAuthenticationEntryPoint} for the 403 (authenticated but
 * forbidden) case - @EnableMethodSecurity's @PreAuthorize denials and any AccessDeniedException
 * thrown before a controller method runs used to fall back to Spring Security's default handling
 * (an empty body), unlike GlobalExceptionHandler's handleAccessDenied(...) which already wraps the
 * same exception type in the standard ApiResponse envelope for anything reaching a controller.
 * Writing the identical envelope here means the frontend's ApiException parsing works the same way
 * regardless of which layer produced the 403.
 */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ApiAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        // R14-09: envelope + UTF-8 handling is shared with the rate-limit filters.
        ApiErrorResponseWriter.write(objectMapper, request, response,
                ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getDefaultMessage());
    }
}
