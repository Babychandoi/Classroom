package com.classroom.modules.classroom.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * R13-02: archive/unarchive transition for Studio "Cài đặt lớp". Only ACTIVE <-> ARCHIVED is a
 * valid target here (see ClassroomService#updateStatus for the full state machine and its
 * audit/authorization rules); any other value is rejected by the pattern below before it ever
 * reaches the service.
 */
public class UpdateClassroomStatusRequest {
    @NotBlank(message = "Trạng thái không được để trống")
    @Pattern(regexp = "^(ACTIVE|ARCHIVED)$", message = "Trạng thái chỉ có thể là ACTIVE hoặc ARCHIVED")
    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
