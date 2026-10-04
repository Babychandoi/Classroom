package com.classroom.modules.classroom.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * R13-02: PUT /classes/{id} (Studio "Cài đặt lớp" - FR-14 / sitemap
 * /studio/classes/:id/settings). Slug is intentionally not editable here - the spec does not sign
 * off on slug rewrites (see BRD/HLD "[CẦN CHỐT chính sách]" markers around classroom identity) and
 * a slug change would silently break every existing /classes/:slug link and bookmark; if the spec
 * is later amended to allow it, that should be its own reviewed change; status transitions
 * (archive/unarchive) go through the dedicated status endpoint instead of this
 * general-purpose update so they can be OWNER-gated and audited distinctly from a plain field edit.
 *
 * <p>D-19: {@code visibility} (PUBLIC / PRIVATE) IS editable here - it needs only CLASS:EDIT like the other
 * settings - and is audited with its before/after value. How a class is paid for (FREE / PAID, money) is NOT editable
 * here: it has its own endpoint, PUT /classes/{id}/access.</p>
 */
public class UpdateClassroomRequest {
    @NotBlank(message = "Tên lớp học không được để trống")
    @Size(max = 255, message = "Tên lớp học tối đa 255 ký tự")
    private String title;

    private String description;

    private String coverImageUrl;

    /**
     * D-27: the uploaded cover image - an UPLOADED image of this class with media purpose CLASS_COVER. Absent / null keeps the current cover,
     * an empty string clears it.
     */
    @Size(max = 36, message = "Mã ảnh bìa không hợp lệ")
    private String coverMediaId;

    /** D-19: PUBLIC or PRIVATE. Optional - absent keeps the current value (create: PUBLIC). Case-insensitive. */
    @Pattern(regexp = "^(?i)(PUBLIC|PRIVATE)$", message = "Chế độ hiển thị lớp chỉ có thể là PUBLIC hoặc PRIVATE")
    private String visibility;

    /**
     * D-28: all optional; absent = keep. {@code category}: one of ClassCategories.ALL, "" clears it. {@code coverPosition} /
     * {@code avatarPosition}: "X% Y%", "" clears (centre). {@code avatarMediaId}: like coverMediaId (purpose CLASS_AVATAR; "" clears).
     */
    @Size(max = 40, message = "Danh mục lớp học không hợp lệ")
    private String category;
    private Boolean requireApproval;
    @Size(max = 16, message = "Vị trí ảnh bìa không hợp lệ")
    private String coverPosition;
    @Size(max = 16, message = "Vị trí ảnh đại diện không hợp lệ")
    private String avatarPosition;
    @Size(max = 36, message = "Mã ảnh đại diện không hợp lệ")
    private String avatarMediaId;

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Boolean getRequireApproval() { return requireApproval; }
    public void setRequireApproval(Boolean requireApproval) { this.requireApproval = requireApproval; }
    public String getCoverPosition() { return coverPosition; }
    public void setCoverPosition(String coverPosition) { this.coverPosition = coverPosition; }
    public String getAvatarPosition() { return avatarPosition; }
    public void setAvatarPosition(String avatarPosition) { this.avatarPosition = avatarPosition; }
    public String getAvatarMediaId() { return avatarMediaId; }
    public void setAvatarMediaId(String avatarMediaId) { this.avatarMediaId = avatarMediaId; }

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

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }
}
