package com.classroom.modules.media.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "media_assets")
public class MediaAsset {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(name = "uploader_id", nullable = false, length = 36)
    private String uploaderId;

    @Column(name = "object_key", nullable = false, unique = true, length = 512)
    private String objectKey;

    @Column(name = "upload_object_key", nullable = false, unique = true, length = 512)
    private String uploadObjectKey;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "mime_type", nullable = false, length = 128)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "upload_purpose", nullable = false, length = 32)
    private String uploadPurpose = "MEDIA";

    @Column(name = "scope_course_id", length = 36)
    private String scopeCourseId;

    public String getUploadPurpose() { return uploadPurpose; }
    public void setUploadPurpose(String uploadPurpose) { this.uploadPurpose = uploadPurpose; }
    public String getScopeCourseId() { return scopeCourseId; }
    public void setScopeCourseId(String scopeCourseId) { this.scopeCourseId = scopeCourseId; }

    @Column(nullable = false, length = 32)
    private String status = "PENDING"; // PENDING, UPLOADED, FAILED

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public MediaAsset() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }

    public MediaAsset(String classId, String uploaderId, String objectKey, String originalFilename, String mimeType, long sizeBytes) {
        this.id = UUID.randomUUID().toString();
        this.classId = classId;
        this.uploaderId = uploaderId;
        this.objectKey = objectKey;
        this.uploadObjectKey = "pending/" + UUID.randomUUID();
        this.originalFilename = originalFilename;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.status = "PENDING";
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

    public String getUploaderId() {
        return uploaderId;
    }

    public void setUploaderId(String uploaderId) {
        this.uploaderId = uploaderId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getUploadObjectKey() { return uploadObjectKey; }
    public void setUploadObjectKey(String uploadObjectKey) { this.uploadObjectKey = uploadObjectKey; }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
