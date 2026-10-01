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
    /** D-19: STANDARD (PRO package / course product) or CLASS_ACCESS (the product that sells membership of a PAID class). */
    private String kind = "STANDARD";
    private BigDecimal price;
    private String currency;
    private int durationDays;
    private Instant accessStartsAt;
    private boolean userHasActiveEntitlement;
    // R19-06: the viewer already paid for this product but every entitlement starts in the future (pre-sale
    // purchase / product accessStartsAt ahead of now). userHasActiveEntitlement stays false (no access yet);
    // entitlementStartsAt is when the earliest one begins, entitlementExpiresAt the end of the paid chain.
    private boolean userOwnsUpcoming;
    private Instant entitlementStartsAt;
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

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
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

    public boolean isUserOwnsUpcoming() {
        return userOwnsUpcoming;
    }

    public void setUserOwnsUpcoming(boolean userOwnsUpcoming) {
        this.userOwnsUpcoming = userOwnsUpcoming;
    }

    public Instant getEntitlementStartsAt() {
        return entitlementStartsAt;
    }

    public void setEntitlementStartsAt(Instant entitlementStartsAt) {
        this.entitlementStartsAt = entitlementStartsAt;
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
