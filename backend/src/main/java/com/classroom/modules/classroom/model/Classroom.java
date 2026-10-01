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
