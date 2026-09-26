package com.classroom.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleAppException(AppException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        log.warn("Application exception [{}]: {} (Request ID: {})",
                ex.getErrorCode().getCode(), ex.getMessage(), requestId);
        return ResponseEntity
                .status(ex.getErrorCode().getHttpStatus())
                .body(ApiResponse.error(ex.getErrorCode().getCode(), ex.getMessage(), requestId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("Validation error: {} (Request ID: {})", details, requestId);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST.getCode(), details, requestId));
    }

    /**
     * Malformed / unreadable request bodies are a client error (400), never a 500.
     * The parser message is not echoed back: it can leak internal type names.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        log.warn("Malformed request body on {} {} (Request ID: {}): {}",
                request.getMethod(), request.getRequestURI(), requestId, ex.getMostSpecificCause().getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST.getCode(),
                        "Nội dung yêu cầu không hợp lệ hoặc sai định dạng JSON.", requestId));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        log.warn("Missing request parameter '{}' (Request ID: {})", ex.getParameterName(), requestId);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST.getCode(),
                        "Thiếu tham số bắt buộc: " + ex.getParameterName(), requestId));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        log.warn("Parameter type mismatch for '{}' (Request ID: {})", ex.getName(), requestId);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST.getCode(),
                        "Giá trị tham số không hợp lệ: " + ex.getName(), requestId));
    }

    /** Unsupported HTTP method must answer 405 with an Allow header, not 500. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        log.warn("Unsupported method {} on {} (Request ID: {})", ex.getMethod(), request.getRequestURI(), requestId);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            builder.allow(ex.getSupportedHttpMethods().toArray(new org.springframework.http.HttpMethod[0]));
        }
        return builder.body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED.getCode(),
                ErrorCode.METHOD_NOT_ALLOWED.getDefaultMessage(), requestId));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        log.warn("Unsupported media type {} on {} (Request ID: {})", ex.getContentType(), request.getRequestURI(), requestId);
        return ResponseEntity
                .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.error(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getCode(),
                        ErrorCode.UNSUPPORTED_MEDIA_TYPE.getDefaultMessage(), requestId));
    }

    /**
     * A constraint violation reaching the persistence layer means the request body did not satisfy
     * the data contract. That is a client error (400), not a 500. The driver message is never
     * echoed back: it carries table and column names.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(
            org.springframework.dao.DataIntegrityViolationException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        log.warn("Data integrity violation on {} {} (Request ID: {})",
                request.getMethod(), request.getRequestURI(), requestId, ex);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST.getCode(),
                        "Dữ liệu gửi lên không hợp lệ hoặc thiếu trường bắt buộc.", requestId));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        log.warn("Access denied: {} (Request ID: {})", ex.getMessage(), requestId);
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.FORBIDDEN.getCode(), ErrorCode.FORBIDDEN.getDefaultMessage(), requestId));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        log.warn("Authentication failed: {} (Request ID: {})", ex.getMessage(), requestId);
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getDefaultMessage(), requestId));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(org.springframework.web.servlet.resource.NoResourceFoundException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        log.warn("Resource not found: {} (Request ID: {})", ex.getResourcePath(), requestId);
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ErrorCode.NOT_FOUND.getCode(), ErrorCode.NOT_FOUND.getDefaultMessage(), requestId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        log.error("Unhandled internal server error (Request ID: {})", requestId, ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
                        "Đã xảy ra lỗi hệ thống, vui lòng thử lại sau.", requestId));
    }

    private String getRequestId(HttpServletRequest request) {
        String headerId = request.getHeader("X-Request-Id");
        return (headerId != null && !headerId.isBlank()) ? headerId : UUID.randomUUID().toString();
    }
}
