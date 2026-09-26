package com.classroom.modules.media.dto;

import java.time.Instant;

public class DownloadUrlResponse {
    private String assetId;
    private String downloadUrl;
    private Instant expiresAt;

    public DownloadUrlResponse() {}

    public DownloadUrlResponse(String assetId, String downloadUrl, Instant expiresAt) {
        this.assetId = assetId;
        this.downloadUrl = downloadUrl;
        this.expiresAt = expiresAt;
    }

    public String getAssetId() {
        return assetId;
    }

    public void setAssetId(String assetId) {
        this.assetId = assetId;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
