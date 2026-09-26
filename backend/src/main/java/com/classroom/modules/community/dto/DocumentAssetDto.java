package com.classroom.modules.community.dto;

import java.time.Instant;

public class DocumentAssetDto {
    private String id;
    private String classId;
    private String title;
    private String description;
    private String mediaAssetId;
    private String filename;
    private String mimeType;
    private long sizeBytes;
    private String visibility;
    private String targetProductId;
    private String targetCourseId;
    private String downloadUrl;
    private Instant createdAt;

    public DocumentAssetDto() {}

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

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
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

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
