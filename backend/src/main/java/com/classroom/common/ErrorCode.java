package com.classroom.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    BAD_REQUEST("BAD_REQUEST", "Yêu cầu không hợp lệ", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("UNAUTHORIZED", "Chưa đăng nhập hoặc phiên đã hết hạn", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("FORBIDDEN", "Bạn không có quyền thực hiện thao tác này", HttpStatus.FORBIDDEN),
    NOT_FOUND("NOT_FOUND", "Không tìm thấy tài nguyên yêu cầu", HttpStatus.NOT_FOUND),
    CONFLICT("CONFLICT", "Dữ liệu bị trùng lặp hoặc xung đột", HttpStatus.CONFLICT),
    UNPROCESSABLE_ENTITY("UNPROCESSABLE_ENTITY", "Vi phạm ràng buộc nghiệp vụ", HttpStatus.UNPROCESSABLE_ENTITY),
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", "Phương thức HTTP không được hỗ trợ cho tài nguyên này", HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE("UNSUPPORTED_MEDIA_TYPE", "Định dạng dữ liệu gửi lên không được hỗ trợ", HttpStatus.UNSUPPORTED_MEDIA_TYPE),

    // Specific domain errors
    COURSE_ACCESS_REQUIRED("COURSE_ACCESS_REQUIRED", "Cần mua khóa học để truy cập nội dung này", HttpStatus.FORBIDDEN),
    PRO_MEMBERSHIP_REQUIRED("PRO_MEMBERSHIP_REQUIRED", "Yêu cầu quyền PRO để truy cập nội dung này", HttpStatus.FORBIDDEN),
    EXAM_AUDIENCE_REJECTED("EXAM_AUDIENCE_REJECTED", "Bạn không thuộc đối tượng được tham gia kỳ thi này", HttpStatus.FORBIDDEN),
    EXAM_ATTEMPT_LIMIT_REACHED("EXAM_ATTEMPT_LIMIT_REACHED", "Bạn đã hết số lượt làm bài cho phép", HttpStatus.UNPROCESSABLE_ENTITY),
    EXAM_NOT_OPEN("EXAM_NOT_OPEN", "Kỳ thi chưa mở hoặc đã kết thúc", HttpStatus.UNPROCESSABLE_ENTITY),
    STAFF_PERMISSION_DENIED("STAFF_PERMISSION_DENIED", "Nhân sự không có quyền thực hiện trên tài nguyên này", HttpStatus.FORBIDDEN),
    INVALID_WEBHOOK_SIGNATURE("INVALID_WEBHOOK_SIGNATURE", "Chữ ký webhook không hợp lệ", HttpStatus.BAD_REQUEST),
    ORDER_ALREADY_PROCESSED("ORDER_ALREADY_PROCESSED", "Đơn hàng đã được xử lý", HttpStatus.CONFLICT),
    INTERNAL_SERVER_ERROR("INTERNAL_SERVER_ERROR", "Lỗi máy chủ nội bộ", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String defaultMessage, HttpStatus httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
