package com.classroom.config;

import com.classroom.common.ApiResponse;
import com.classroom.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * R14-09: single place that writes the standard {@link ApiResponse} error envelope from servlet-layer
 * code that runs before (or outside) a controller - {@link ApiAuthenticationEntryPoint},
 * {@link ApiAccessDeniedHandler}, {@link AuthRateLimitFilter} and {@link ExamRateLimitFilter}.
 *
 * <p>The content type is always {@code application/json} with an explicit {@code UTF-8} charset. The
 * rate-limit filters previously set {@code application/json} only, which the servlet container
 * serves as ISO-8859-1 and garbled the Vietnamese message text. Every layer now produces the same
 * {@code {success:false, error:{code,message,requestId}, requestId, timestamp}} shape the frontend's
 * ApiException parsing expects.
 */
final class ApiErrorResponseWriter {

    /** ApiResponse only holds Strings/booleans, so a plain mapper (no extra modules) is enough. */
    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

    private ApiErrorResponseWriter() {
    }

    /** Writes {@code errorCode}'s status and default message. */
    static void write(HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode) throws IOException {
        write(DEFAULT_MAPPER, request, response, errorCode, errorCode.getDefaultMessage());
    }

    /** Writes {@code errorCode}'s status with a caller-specific message. */
    static void write(HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode, String message)
            throws IOException {
        write(DEFAULT_MAPPER, request, response, errorCode, message);
    }

    /** Variant for callers that already hold the application's configured {@link ObjectMapper}. */
    static void write(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
                      ErrorCode errorCode, String message) throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiResponse<Void> body = ApiResponse.error(errorCode.getCode(), message, requestId(request));
        mapper.writeValue(response.getWriter(), body);
    }

    static String requestId(HttpServletRequest request) {
        String headerId = request.getHeader("X-Request-Id");
        return (headerId != null && !headerId.isBlank()) ? headerId : UUID.randomUUID().toString();
    }
}
