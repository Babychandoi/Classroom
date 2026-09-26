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
        ApiResponse<T> res = new ApiResponse<>();
        res.success = false;
        res.requestId = (requestId != null) ? requestId : UUID.randomUUID().toString();
        res.error = new ApiError(code, message, res.requestId);
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

    public static class ApiError {
        private String code;
        private String message;
        private String requestId;

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

        public String getRequestId() {
            return requestId;
        }

        public void setRequestId(String requestId) {
            this.requestId = requestId;
        }
    }
}
