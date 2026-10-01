package com.classroom.common;

import java.util.Map;

public class AppException extends RuntimeException {
    private final ErrorCode errorCode;
    /** Seconds the client should wait before retrying (sent as {@code Retry-After}); 0 when there is no such hint. */
    private final long retryAfterSeconds;
    /** D-19: optional structured payload for the client (sent as {@code error.details}); {@code null} for almost every error. */
    private final Map<String, Object> details;

    public AppException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.retryAfterSeconds = 0;
        this.details = null;
    }

    public AppException(ErrorCode errorCode, String customMessage) {
        super(customMessage);
        this.errorCode = errorCode;
        this.retryAfterSeconds = 0;
        this.details = null;
    }

    /** R20-12: a retryable failure (503 while an object store is unreachable) that tells the client when to try again. */
    public AppException(ErrorCode errorCode, String customMessage, long retryAfterSeconds) {
        super(customMessage);
        this.errorCode = errorCode;
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
        this.details = null;
    }

    /**
     * D-19: a business error that carries data the client needs to act on it - PAYMENT_REQUIRED hands the UI the class-access product so
     * it can start checkout without a second round trip. The map is copied; it must only hold data the caller is allowed to see.
     */
    public AppException(ErrorCode errorCode, String customMessage, Map<String, Object> details) {
        super(customMessage);
        this.errorCode = errorCode;
        this.retryAfterSeconds = 0;
        this.details = details == null ? null : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details));
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
