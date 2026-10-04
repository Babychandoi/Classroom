package com.classroom.modules.classroom.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "classrooms")
public class Classroom {

    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    public static final String ACCESS_FREE = "FREE";
    public static final String ACCESS_PAID = "PAID";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    @Column(nullable = false, unique = true, length = 100)
    private String slug;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "cover_image_url", length = 512)
    private String coverImageUrl;

    /** D-27: an UPLOADED image (media purpose CLASS_COVER) of this class; served as a short-lived presigned URL ({@code coverUrl}). */
    @Column(name = "cover_media_id", length = 36)
    private String coverMediaId;

    /** D-28: one of {@link com.classroom.modules.classroom.service.ClassCategories#ALL}; null for classes created before V46. */
    @Column(length = 40)
    private String category;

    /** D-28: an UPLOADED image (media purpose CLASS_AVATAR) of this class, served as a presigned {@code avatarUrl}. */
    @Column(name = "avatar_media_id", length = 36)
    private String avatarMediaId;

    /** D-28: CSS object-position of the cover / avatar ("50% 30%"); null = centre. */
    @Column(name = "cover_position", length = 16)
    private String coverPosition;

    @Column(name = "avatar_position", length = 16)
    private String avatarPosition;

    /** D-28: joins by id wait for a Studio decision (member state PENDING). */
    @Column(name = "require_approval", nullable = false)
    private boolean requireApproval;

    @Column(nullable = false, length = 32)
    private String status = "ACTIVE";

    /** D-19: PUBLIC (listed, joinable by anyone) or PRIVATE (hidden from non-members, joinable by invite only). */
    @Column(nullable = false, length = 16)
    private String visibility = VISIBILITY_PUBLIC;

    /** D-19: FREE (joining gives an ACTIVE membership) or PAID (membership is bought through the class-access product). */
    @Column(name = "access_type", nullable = false, length = 16)
    private String accessType = ACCESS_FREE;

    /** D-19: the class-access product of a PAID class; kept (ARCHIVED) after PAID -> FREE so a later FREE -> PAID re-uses it. */
    @Column(name = "access_product_id", length = 36)
    private String accessProductId;

    /** D-29: what a platform-admin suspension interrupted (ACTIVE / ARCHIVED) - restore returns to it; NULL unless SUSPENDED. */
    @Column(name = "status_before_suspend", length = 32)
    private String statusBeforeSuspend;

    /** D-29: the admin's reason, shown to the owner only. */
    @Column(name = "suspended_reason", length = 500)
    private String suspendedReason;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Classroom() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Classroom(String id, String ownerId, String slug, String title, String description) {
        this.id = (id != null) ? id : UUID.randomUUID().toString();
        this.ownerId = ownerId;
        this.slug = slug;
        this.title = title;
        this.description = description;
        this.status = "ACTIVE";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
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

    public String getCoverMediaId() {
        return coverMediaId;
    }

    public void setCoverMediaId(String coverMediaId) {
        this.coverMediaId = coverMediaId;
    }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getAvatarMediaId() { return avatarMediaId; }
    public void setAvatarMediaId(String avatarMediaId) { this.avatarMediaId = avatarMediaId; }
    public String getCoverPosition() { return coverPosition; }
    public void setCoverPosition(String coverPosition) { this.coverPosition = coverPosition; }
    public String getAvatarPosition() { return avatarPosition; }
    public void setAvatarPosition(String avatarPosition) { this.avatarPosition = avatarPosition; }
    public boolean isRequireApproval() { return requireApproval; }
    public void setRequireApproval(boolean requireApproval) { this.requireApproval = requireApproval; }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public String getAccessType() {
        return accessType;
    }

    public void setAccessType(String accessType) {
        this.accessType = accessType;
    }

    public String getAccessProductId() {
        return accessProductId;
    }

    public void setAccessProductId(String accessProductId) {
        this.accessProductId = accessProductId;
    }

    public String getStatusBeforeSuspend() { return statusBeforeSuspend; }
    public void setStatusBeforeSuspend(String statusBeforeSuspend) { this.statusBeforeSuspend = statusBeforeSuspend; }
    public String getSuspendedReason() { return suspendedReason; }
    public void setSuspendedReason(String suspendedReason) { this.suspendedReason = suspendedReason; }
    public Instant getSuspendedAt() { return suspendedAt; }
    public void setSuspendedAt(Instant suspendedAt) { this.suspendedAt = suspendedAt; }

    /** D-19: a missing/unknown stored value is PUBLIC (the behaviour every pre-V37 class has). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isPrivate() {
        return VISIBILITY_PRIVATE.equalsIgnoreCase(visibility);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isPaid() {
        return ACCESS_PAID.equalsIgnoreCase(accessType);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
