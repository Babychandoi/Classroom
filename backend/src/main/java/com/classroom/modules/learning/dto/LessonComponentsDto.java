package com.classroom.modules.learning.dto;

/** D-32: which components a lesson has; counts only, safe for everyone who can see the lesson row. */
public record LessonComponentsDto(boolean video, String videoProvider, boolean content, int attachments, boolean assignment) {}
