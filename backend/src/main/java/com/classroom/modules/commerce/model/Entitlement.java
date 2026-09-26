package com.classroom.modules.commerce.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "entitlements")
public class Entitlement {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(name = "product_id", nullable = false, length = 36)
    private String productId;

    @Column(name = "target_course_id", length = 36)
    private String targetCourseId;

    @Column(name = "order_id", length = 36)
    private String orderId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false, length = 32)
    private String state = "ACTIVE"; // ACTIVE, EXPIRED, REVOKED

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Entitlement() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }

    public Entitlement(String userId, String classId, String productId, String targetCourseId, Instant startsAt, Instant expiresAt) {
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.classId = classId;
        this.productId = productId;
        this.targetCourseId = targetCourseId;
        this.startsAt = startsAt;
        this.expiresAt = expiresAt;
        this.state = "ACTIVE";
        this.createdAt = Instant.now();
    }

    public boolean isCurrentlyActive(Instant now) {
        return "ACTIVE".equalsIgnoreCase(state)
                && !startsAt.isAfter(now)
                && expiresAt.isAfter(now);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getClassId() {
        return classId;
    }

    public void setClassId(String classId) {
        this.classId = classId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getTargetCourseId() {
        return targetCourseId;
    }

    public void setTargetCourseId(String targetCourseId) {
        this.targetCourseId = targetCourseId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(Instant startsAt) {
        this.startsAt = startsAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
