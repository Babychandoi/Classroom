package com.classroom.modules.blog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** D-27: POST /classes/{classId}/blog-posts. The post is always created as a DRAFT authored by the caller. */
public record CreateBlogPostRequest(
        @NotBlank(message = "Tiêu đề bài viết không được để trống")
        @Size(max = 200, message = "Tiêu đề bài viết tối đa 200 ký tự")
        String title,
        @Size(max = 300, message = "Tóm tắt tối đa 300 ký tự")
        String excerpt,
        @Size(max = 60, message = "Chuyên mục tối đa 60 ký tự")
        String category,
        @Size(max = 100_000, message = "Nội dung bài viết tối đa 100.000 ký tự")
        String contentMarkdown,
        @Size(max = 36, message = "Mã ảnh bìa không hợp lệ")
        String coverMediaId,
        @NotNull(message = "Đối tượng xem bài viết không được để trống")
        @Pattern(regexp = "^(?i)(PUBLIC|MEMBERS)$", message = "Đối tượng xem chỉ có thể là PUBLIC hoặc MEMBERS")
        String audience) {}
