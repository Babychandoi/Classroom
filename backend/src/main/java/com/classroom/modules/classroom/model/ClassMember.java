package com.classroom.modules.classroom.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "class_members")
public class ClassMember {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, length = 32)
    private String state = "ACTIVE"; // ACTIVE, BANNED

    @Column(nullable = false, length = 32)
    private String role = "STUDENT"; // STUDENT, STAFF, OWNER

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    public ClassMember() {
        this.id = UUID.randomUUID().toString();
        this.joinedAt = Instant.now();
    }

    public ClassMember(String classId, String userId, String role) {
        this.id = UUID.randomUUID().toString();
        this.classId = classId;
        this.userId = userId;
        this.role = (role != null) ? role : "STUDENT";
        this.state = "ACTIVE";
        this.joinedAt = Instant.now();
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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }
}
