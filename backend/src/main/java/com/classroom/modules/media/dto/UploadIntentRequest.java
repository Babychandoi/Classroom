package com.classroom.modules.media.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public class UploadIntentRequest {

    /**
     * What the uploaded file will be attached to. The upload is authorized against the authoring
     * action that the caller is about to perform, so a staff member granted DOCUMENT:CREATE can
     * upload the document's own file without also needing a blanket MEDIA:CREATE grant.
     * Omitted or unknown values fall back to the generic MEDIA:CREATE permission.
     */
    private String purpose;

    /** Course the upload belongs to, for purposes whose permission may be scoped to one course. */
    private String scopeCourseId;

    @NotBlank(message = "Tên tệp không được để trống")
    private String filename;

    @NotBlank(message = "MIME type không được để trống")
    private String mimeType;

    @Positive(message = "Kích thước tệp phải lớn hơn 0")
    private long sizeBytes;

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public String getScopeCourseId() {
        return scopeCourseId;
    }

    public void setScopeCourseId(String scopeCourseId) {
        this.scopeCourseId = scopeCourseId;
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
}
