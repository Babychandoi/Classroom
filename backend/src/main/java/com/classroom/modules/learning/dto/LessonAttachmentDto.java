package com.classroom.modules.learning.dto;

/** D-32: a lesson document. Only returned to callers who may learn / manage the course. */
public record LessonAttachmentDto(String id, String mediaAssetId, String title, String fileName, String mimeType, long sizeBytes, int position) {}
