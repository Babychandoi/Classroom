package com.classroom.modules.learning.dto;

import java.time.Instant;
import java.util.List;

public class CourseDto {
    private String id;
    private String classId;
    private String productId;
    private String title;
    private String description;
    private String coverImageUrl;
    private String accessMode;
    private String status;
    private int position;
    private boolean canLearn;
    private int totalLessons;
    private int completedLessons;
    private double progressPercentage;
    private Instant createdAt;
    private boolean canEdit;
    private List<SectionDto> sections;
    // R13-09 (Learn "hết hạn"): why the viewer does/doesn't have access, and — for a PURCHASE_REQUIRED
    // course — when their access expires (null when access does not come from a time-boxed entitlement).
    private String accessReason;
    private Instant expiresAt;
    // R14-12: for accessReason=OWNED_UPCOMING (paid, entitlement not started yet) when it begins.
    private Instant accessStartsAt;
    // R19-12: whether the course can be bought in the store RIGHT NOW (PURCHASE_REQUIRED and its product is
    // PUBLISHED), and the linked product's status. An ARCHIVED product ("gỡ bán") is not in the store any more, so
    // the Learn tab must not send a learner there ("Mua khóa học tại Cửa hàng") nor offer a renewal.
    private boolean canPurchase;
    private String productStatus;

    public CourseDto() {}

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

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
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

    public String getCoverImageUrl() {
        return coverImageUrl;
    }

    public void setCoverImageUrl(String coverImageUrl) {
        this.coverImageUrl = coverImageUrl;
    }

    public String getAccessMode() {
        return accessMode;
    }

    public void setAccessMode(String accessMode) {
        this.accessMode = accessMode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public boolean isCanLearn() {
        return canLearn;
    }

    public void setCanLearn(boolean canLearn) {
        this.canLearn = canLearn;
    }

    public int getTotalLessons() {
        return totalLessons;
    }

    public void setTotalLessons(int totalLessons) {
        this.totalLessons = totalLessons;
    }

    public int getCompletedLessons() {
        return completedLessons;
    }

    public void setCompletedLessons(int completedLessons) {
        this.completedLessons = completedLessons;
    }

    public double getProgressPercentage() {
        return progressPercentage;
    }

    public void setProgressPercentage(double progressPercentage) {
        this.progressPercentage = progressPercentage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<SectionDto> getSections() {
        return sections;
    }

    public void setSections(List<SectionDto> sections) {
        this.sections = sections;
    }

    public boolean isCanEdit() {
        return canEdit;
    }

    public void setCanEdit(boolean canEdit) {
        this.canEdit = canEdit;
    }

    public String getAccessReason() {
        return accessReason;
    }

    public void setAccessReason(String accessReason) {
        this.accessReason = accessReason;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getAccessStartsAt() {
        return accessStartsAt;
    }

    public void setAccessStartsAt(Instant accessStartsAt) {
        this.accessStartsAt = accessStartsAt;
    }

    public boolean isCanPurchase() {
        return canPurchase;
    }

    public void setCanPurchase(boolean canPurchase) {
        this.canPurchase = canPurchase;
    }

    public String getProductStatus() {
        return productStatus;
    }

    public void setProductStatus(String productStatus) {
        this.productStatus = productStatus;
    }
}
