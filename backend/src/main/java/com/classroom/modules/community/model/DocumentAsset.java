package com.classroom.modules.community.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_assets")
public class DocumentAsset {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "media_asset_id", nullable = false, length = 36)
    private String mediaAssetId;

    @Column(nullable = false, length = 32)
    private String visibility = "FREE"; // FREE, PRO, PRODUCT_OWNER

    @Column(name = "target_product_id", length = 36)
    private String targetProductId;

    @Column(name = "target_course_id", length = 36)
    private String targetCourseId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public DocumentAsset() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }

    public DocumentAsset(String classId, String title, String description, String mediaAssetId, String visibility) {
        this.id = UUID.randomUUID().toString();
        this.classId = classId;
        this.title = title;
        this.description = description;
        this.mediaAssetId = mediaAssetId;
        this.visibility = (visibility != null) ? visibility : "FREE";
        this.createdAt = Instant.now();
    }

    public DocumentAsset(String classId, String title, String description, String mediaAssetId, String visibility, String targetProductId, String targetCourseId) {
        this.id = UUID.randomUUID().toString();
        this.classId = classId;
        this.title = title;
        this.description = description;
        this.mediaAssetId = mediaAssetId;
        this.visibility = (visibility != null) ? visibility : "FREE";
        this.targetProductId = targetProductId;
        this.targetCourseId = targetCourseId;
        this.createdAt = Instant.now();
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

    public String getMediaAssetId() {
        return mediaAssetId;
    }

    public void setMediaAssetId(String mediaAssetId) {
        this.mediaAssetId = mediaAssetId;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public String getTargetProductId() {
        return targetProductId;
    }

    public void setTargetProductId(String targetProductId) {
        this.targetProductId = targetProductId;
    }

    public String getTargetCourseId() {
        return targetCourseId;
    }

    public void setTargetCourseId(String targetCourseId) {
        this.targetCourseId = targetCourseId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
