package com.classroom.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private boolean success;
    private T data;
    private ApiError error;
    private String requestId;
    private String timestamp;

    public ApiResponse() {
        this.timestamp = Instant.now().toString();
        this.requestId = UUID.randomUUID().toString();
    }

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> res = new ApiResponse<>();
        res.success = true;
        res.data = data;
        return res;
    }

    public static <T> ApiResponse<T> ok() {
        ApiResponse<T> res = new ApiResponse<>();
        res.success = true;
        return res;
    }

    public static <T> ApiResponse<T> error(String code, String message, String requestId) {
        return error(code, message, requestId, null);
    }

    /** D-19: an error that also carries structured {@code details} (e.g. PAYMENT_REQUIRED hands the UI the class-access product). */
    public static <T> ApiResponse<T> error(String code, String message, String requestId, java.util.Map<String, Object> details) {
        ApiResponse<T> res = new ApiResponse<>();
        res.success = false;
        res.requestId = (requestId != null) ? requestId : UUID.randomUUID().toString();
        res.error = new ApiError(code, message, res.requestId);
        res.error.setDetails(details);
        return res;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public ApiError getError() {
        return error;
    }

    public void setError(ApiError error) {
        this.error = error;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ApiError {
        private String code;
        private String message;
        private String requestId;
        /** D-19: optional structured payload (only PAYMENT_REQUIRED sets it today); omitted from the JSON when null. */
        private java.util.Map<String, Object> details;

        public ApiError(String code, String message, String requestId) {
            this.code = code;
            this.message = message;
            this.requestId = requestId;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public java.util.Map<String, Object> getDetails() {
            return details;
        }

        public void setDetails(java.util.Map<String, Object> details) {
            this.details = details;
        }

        public String getRequestId() {
            return requestId;
        }

        public void setRequestId(String requestId) {
            this.requestId = requestId;
        }
    }
}
