package com.classroom.modules.blog.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * D-27: PUT /blog-posts/{id} - every field optional. A field that is ABSENT from the JSON keeps its value; for the optional fields
 * ({@code excerpt}, {@code category}, {@code coverMediaId}) an explicit {@code null} or an empty string clears it. The presence flags are set
 * by the setters Jackson calls only for properties that are in the body.
 */
public class UpdateBlogPostRequest {
    @Size(min = 1, max = 200, message = "Tiêu đề bài viết từ 1 đến 200 ký tự")
    private String title;
    @Size(max = 300, message = "Tóm tắt tối đa 300 ký tự")
    private String excerpt;
    @Size(max = 60, message = "Chuyên mục tối đa 60 ký tự")
    private String category;
    @Size(max = 100_000, message = "Nội dung bài viết tối đa 100.000 ký tự")
    private String contentMarkdown;
    @Size(max = 36, message = "Mã ảnh bìa không hợp lệ")
    private String coverMediaId;
    @Pattern(regexp = "^(?i)(PUBLIC|MEMBERS)$", message = "Đối tượng xem chỉ có thể là PUBLIC hoặc MEMBERS")
    private String audience;

    private boolean excerptPresent;
    private boolean categoryPresent;
    private boolean coverMediaIdPresent;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getExcerpt() { return excerpt; }
    public void setExcerpt(String excerpt) { this.excerpt = excerpt; this.excerptPresent = true; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; this.categoryPresent = true; }
    public String getContentMarkdown() { return contentMarkdown; }
    public void setContentMarkdown(String contentMarkdown) { this.contentMarkdown = contentMarkdown; }
    public String getCoverMediaId() { return coverMediaId; }
    public void setCoverMediaId(String coverMediaId) { this.coverMediaId = coverMediaId; this.coverMediaIdPresent = true; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }

    public boolean hasExcerpt() { return excerptPresent; }
    public boolean hasCategory() { return categoryPresent; }
    public boolean hasCoverMediaId() { return coverMediaIdPresent; }
}
