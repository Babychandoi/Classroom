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
import org.springframework.web.method.annotation.HandlerMethodValidationException;
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
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.getErrorCode().getHttpStatus());
        if (ex.getRetryAfterSeconds() > 0) {
            // R20-12: a retryable failure (503 while the object store is unreachable) tells the client when to come back.
            response.header(org.springframework.http.HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()));
        }
        return response.body(ApiResponse.error(ex.getErrorCode().getCode(), ex.getMessage(), requestId, ex.getDetails()));
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

    /**
     * R4-02: Spring 6.2's method-level validation (e.g. a @Valid List<@Valid Dto> @RequestBody
     * parameter) throws HandlerMethodValidationException instead of MethodArgumentNotValidException.
     * Left unmapped it falls through to the generic 500 handler; map it to 400 with the same
     * error response shape as the existing bean-validation handler.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidationException(HandlerMethodValidationException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        String details = ex.getAllValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(error -> error.getDefaultMessage() != null ? error.getDefaultMessage() : error.toString())
                .collect(Collectors.joining(", "));
        if (details.isBlank()) {
            details = "Dữ liệu gửi lên không hợp lệ.";
        }
        log.warn("Method validation error: {} (Request ID: {})", details, requestId);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST.getCode(), details, requestId));
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
    /**
     * R12-01: a bulk revocation/rotation racing another writer for the same row(s) can surface
     * Spring's OptimisticLockingFailureException (its subclass
     * ObjectOptimisticLockingFailureException is thrown by Hibernate's @Version check). Left
     * unmapped this fell through to the generic 500 handler even though it represents a legitimate,
     * transient write conflict the client can retry - map it to 409 CONFLICT with the standard
     * envelope instead.
     */
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLockingFailure(
            org.springframework.dao.OptimisticLockingFailureException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        log.warn("Optimistic locking conflict on {} {} (Request ID: {}): {}",
                request.getMethod(), request.getRequestURI(), requestId, ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ErrorCode.CONFLICT.getCode(),
                        "Dữ liệu đã được cập nhật bởi thao tác khác, vui lòng thử lại.", requestId));
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(
            org.springframework.dao.DataIntegrityViolationException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        if (isReferencedRowViolation(ex)) {
            // R18-07: a delete/update blocked by a foreign key from another table is a state conflict (409), not a
            // malformed request (400). Services should check the known references first and explain them; this is the
            // safety net for any reference they did not anticipate.
            log.warn("Referenced-row constraint violation on {} {} (Request ID: {})",
                    request.getMethod(), request.getRequestURI(), requestId, ex);
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(ErrorCode.CONFLICT.getCode(), REFERENCED_DATA_MESSAGE, requestId));
        }
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

    /**
     * D-19: {@code GET /classes/invites/{code}} and a sibling such as {@code GET /classes/{id}/members} are equally specific for the one
     * request path whose last segment spells the sibling ("/classes/invites/members"). Spring then refuses to choose and throws this
     * IllegalStateException; to the caller that path is simply not a resource (a real code is 16+ characters), so it is a 404 and not a 500.
     * Any other IllegalStateException is still the generic 500.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        if (ex.getMessage() != null && ex.getMessage().startsWith("Ambiguous handler methods mapped for")) {
            String requestId = getRequestId(request);
            log.warn("Ambiguous route for {} {} (Request ID: {}); answered 404", request.getMethod(), request.getRequestURI(), requestId);
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(ErrorCode.NOT_FOUND.getCode(), ErrorCode.NOT_FOUND.getDefaultMessage(), requestId));
        }
        return handleGenericException(ex, request);
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

    /** R18-07: shown when a foreign key from another table blocks the operation. */
    static final String REFERENCED_DATA_MESSAGE = "Thao tác bị chặn vì dữ liệu đang được tham chiếu bởi mục khác; hãy gỡ các liên kết liên quan rồi thử lại.";

    /**
     * True when the violation is "this parent row is still referenced by a child row" - MySQL error 1451, or any
     * driver text saying a foreign key constraint failed on a delete/update of the parent. A child-side failure
     * (MySQL 1452, "Cannot add or update a child row") means the request pointed at data that does not exist, which
     * stays a plain 400.
     */
    public static boolean isReferencedRowViolation(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && sql.getErrorCode() == 1451) {
                return true;
            }
            String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (message.contains("cannot delete or update a parent row")) {
                return true;
            }
            // H2 (tests): "Referential integrity constraint violation: ... FOREIGN KEY ... REFERENCES ..." on a delete.
            if (message.contains("referential integrity constraint violation") && message.contains("delete from")) {
                return true;
            }
        }
        return false;
    }

    private String getRequestId(HttpServletRequest request) {
        return resolveRequestId(request);
    }

    /**
     * R13-12: shared with other controllers (e.g. AuthController's LogoutRevocationException
     * handler) so every error response - whether it goes through this advice or a controller-local
     * @ExceptionHandler - carries the same request-id resolution instead of silently returning null.
     */
    public static String resolveRequestId(HttpServletRequest request) {
        String headerId = request.getHeader("X-Request-Id");
        return (headerId != null && !headerId.isBlank()) ? headerId : UUID.randomUUID().toString();
    }
}
