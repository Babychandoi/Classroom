package com.classroom.modules.classroom.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CreateClassroomRequest {
    @NotBlank(message = "Tên lớp học không được để trống")
    @Size(max = 255, message = "Tên lớp học tối đa 255 ký tự")
    private String title;

    /** D-28: optional - absent / blank lets the server derive one from the title (see SlugGenerator). An explicit slug is validated as before. */
    @Pattern(regexp = "^(\\s*|[a-z0-9-]+)$", message = "Slug chỉ chứa chữ thường, số và dấu gạch ngang")
    @Size(max = 100, message = "Slug từ 3 đến 100 ký tự")
    private String slug;

    private String description;
    private String coverImageUrl;

    /** D-19: PUBLIC or PRIVATE. Optional - absent keeps the current value (create: PUBLIC). Case-insensitive. */
    @Pattern(regexp = "^(?i)(PUBLIC|PRIVATE)$", message = "Chế độ hiển thị lớp chỉ có thể là PUBLIC hoặc PRIVATE")
    private String visibility;

    /** D-28: optional, one of ClassCategories.ALL. */
    @Size(max = 40, message = "Danh mục lớp học không hợp lệ")
    private String category;
    /** D-28: optional, default false. */
    private Boolean requireApproval;
    /** D-28: optional "X% Y%" (0..100). */
    @Size(max = 16, message = "Vị trí ảnh bìa không hợp lệ")
    private String coverPosition;
    @Size(max = 16, message = "Vị trí ảnh đại diện không hợp lệ")
    private String avatarPosition;

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Boolean getRequireApproval() { return requireApproval; }
    public void setRequireApproval(Boolean requireApproval) { this.requireApproval = requireApproval; }
    public String getCoverPosition() { return coverPosition; }
    public void setCoverPosition(String coverPosition) { this.coverPosition = coverPosition; }
    public String getAvatarPosition() { return avatarPosition; }
    public void setAvatarPosition(String avatarPosition) { this.avatarPosition = avatarPosition; }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
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

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }
}
