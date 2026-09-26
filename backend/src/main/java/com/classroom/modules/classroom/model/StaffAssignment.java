package com.classroom.modules.classroom.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "staff_assignments")
public class StaffAssignment {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, length = 32)
    private String status = "ACTIVE"; // ACTIVE, INACTIVE

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    public StaffAssignment() {
        this.id = UUID.randomUUID().toString();
        this.assignedAt = Instant.now();
    }

    public StaffAssignment(String classId, String userId) {
        this.id = UUID.randomUUID().toString();
        this.classId = classId;
        this.userId = userId;
        this.status = "ACTIVE";
        this.assignedAt = Instant.now();
    }

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
}
