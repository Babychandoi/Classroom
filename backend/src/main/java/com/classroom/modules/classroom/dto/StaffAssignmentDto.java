package com.classroom.modules.classroom.dto;

import java.time.Instant;
import java.util.List;

public class StaffAssignmentDto {
    private String id;
    private String classId;
    private String userId;
    private String userEmail;
    private String userFullName;
    private String status;
    private Instant assignedAt;
    private List<StaffPermissionDto> permissions;

    public StaffAssignmentDto() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getClassId() {
        return classId;
    }

    public void setClassId(String classId) {
        this.classId = classId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public String getUserFullName() {
        return userFullName;
    }

    public void setUserFullName(String userFullName) {
        this.userFullName = userFullName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(Instant assignedAt) {
        this.assignedAt = assignedAt;
    }

    public List<StaffPermissionDto> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<StaffPermissionDto> permissions) {
        this.permissions = permissions;
    }
}
