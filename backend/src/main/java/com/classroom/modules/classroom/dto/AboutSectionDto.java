package com.classroom.modules.classroom.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AboutSectionDto(
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Size(max = 20000) String contentMarkdown,
        @Size(max = 2048) String imageUrl,
        @Size(max = 300) String imageAlt,
        @Size(max = 36) String mediaAssetId) {
    public AboutSectionDto(String title, String contentMarkdown, String imageUrl, String imageAlt) {
        this(title, contentMarkdown, imageUrl, imageAlt, null);
    }
}
