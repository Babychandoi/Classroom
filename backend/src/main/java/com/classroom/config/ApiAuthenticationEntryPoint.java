package com.classroom.config;

import com.classroom.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * R12-05: SecurityConfig previously wired a plain HttpStatusEntryPoint(UNAUTHORIZED), which writes
 * a 401 with an empty body - every other error path in the app (GlobalExceptionHandler) returns the
 * standard ApiResponse envelope, so an unauthenticated request landing here instead got a body-less
 * 401 the frontend's ApiException parsing (which expects {"error": {code, message, requestId}})
 * could not make sense of. This writes the same envelope GlobalExceptionHandler uses, so every 401
 * in the app - whether raised by a controller/service (AppException -> GlobalExceptionHandler) or
 * by Spring Security itself before a controller is even reached - has an identical, parseable shape.
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public ApiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        // R14-09: envelope + UTF-8 handling is shared with the rate-limit filters.
        ApiErrorResponseWriter.write(objectMapper, request, response,
                ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.getDefaultMessage());
    }
}
