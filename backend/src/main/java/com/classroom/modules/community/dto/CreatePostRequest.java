package com.classroom.modules.community.dto;

public record CreatePostRequest(String title, String contentMarkdown, String visibility,
                                String targetProductId, String targetSegmentId, boolean pinned) {}
