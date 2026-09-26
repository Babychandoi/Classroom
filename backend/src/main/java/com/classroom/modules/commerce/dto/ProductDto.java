package com.classroom.modules.commerce.dto;

import java.math.BigDecimal;
import java.time.Instant;

public class ProductDto {
    private String id;
    private String classId;
    private String targetCourseId;
    private String targetCourseTitle;
    private String title;
    private String description;
    private String status;
    private BigDecimal price;
    private String currency;
    private int durationDays;
    private Instant accessStartsAt;
    private boolean userHasActiveEntitlement;
    private Instant entitlementExpiresAt;
    private Instant createdAt;

    public ProductDto() {}

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

    public String getTargetCourseId() {
        return targetCourseId;
    }

    public void setTargetCourseId(String targetCourseId) {
        this.targetCourseId = targetCourseId;
    }

    public String getTargetCourseTitle() {
        return targetCourseTitle;
    }

    public void setTargetCourseTitle(String targetCourseTitle) {
        this.targetCourseTitle = targetCourseTitle;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public int getDurationDays() {
        return durationDays;
    }

    public void setDurationDays(int durationDays) {
        this.durationDays = durationDays;
    }

    public Instant getAccessStartsAt() { return accessStartsAt; }

    public void setAccessStartsAt(Instant accessStartsAt) { this.accessStartsAt = accessStartsAt; }

    public boolean isUserHasActiveEntitlement() {
        return userHasActiveEntitlement;
    }

    public void setUserHasActiveEntitlement(boolean userHasActiveEntitlement) {
        this.userHasActiveEntitlement = userHasActiveEntitlement;
    }

    public Instant getEntitlementExpiresAt() {
        return entitlementExpiresAt;
    }

    public void setEntitlementExpiresAt(Instant entitlementExpiresAt) {
        this.entitlementExpiresAt = entitlementExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
