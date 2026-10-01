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
    private String state = "ACTIVE"; // ACTIVE, EXPIRED, REMOVED, BLOCKED (the legacy BANNED is read as BLOCKED)

    @Column(nullable = false, length = 32)
    private String role = "STUDENT"; // STUDENT, STAFF, OWNER

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    /**
     * D-19: end of the paid access of this member (the end of the class-access entitlement chain). {@code null} = no expiry - free
     * classes, the owner and staff, members grandfathered when a free class became paid, lifetime purchases. An ACTIVE row whose date has
     * passed is already an EXPIRED member to every check ({@link #isActiveAt}) even before the sweeper flips the stored state.
     */
    @Column(name = "access_expires_at")
    private Instant accessExpiresAt;

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

    public Instant getAccessExpiresAt() {
        return accessExpiresAt;
    }

    public void setAccessExpiresAt(Instant accessExpiresAt) {
        this.accessExpiresAt = accessExpiresAt;
    }

    /** The one definition of "this row currently grants membership": stored state ACTIVE and the paid access has not lapsed. */
    public boolean isActiveAt(Instant now) {
        return "ACTIVE".equalsIgnoreCase(state) && (accessExpiresAt == null || accessExpiresAt.isAfter(now));
    }

    /** The state to show: a stored ACTIVE whose paid access has lapsed is EXPIRED whether or not the sweeper has flipped it yet. */
    public String effectiveState(Instant now) {
        if ("ACTIVE".equalsIgnoreCase(state) && accessExpiresAt != null && !accessExpiresAt.isAfter(now)) {
            return "EXPIRED";
        }
        return state;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }
}
