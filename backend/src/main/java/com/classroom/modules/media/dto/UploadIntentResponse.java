package com.classroom.modules.media.dto;

public class UploadIntentResponse {
    private String assetId;
    private String uploadUrl;
    private String objectKey;
    private String method;

    public UploadIntentResponse() {}

    public UploadIntentResponse(String assetId, String uploadUrl, String objectKey, String method) {
        this.assetId = assetId;
        this.uploadUrl = uploadUrl;
        this.objectKey = objectKey;
        this.method = method;
    }

    public String getAssetId() {
        return assetId;
    }

    public void setAssetId(String assetId) {
        this.assetId = assetId;
    }

    public String getUploadUrl() {
        return uploadUrl;
    }

    public void setUploadUrl(String uploadUrl) {
        this.uploadUrl = uploadUrl;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }
}
