package com.classroom.modules.learning.dto;

import java.time.Instant;

/**
 * D-30: one row of GET /me/courses - a course the caller can learn, with their own progress. Counts are over learner-visible lessons only
 * (not archived, section not archived), exactly like the course page. {@code lastActivityAt} is the latest lesson-progress update by the
 * caller; {@code nextLessonId} the resume point.
 */
public record MyCourseDto(
        String id,
        String classId,
        String classTitle,
        String classSlug,
        String classAvatarUrl,
        String title,
        String description,
        String coverImageUrl,
        String accessMode,
        int totalLessons,
        int completedLessons,
        int progressPercent,
        Instant lastActivityAt,
        String nextLessonId,
        boolean started) {}
