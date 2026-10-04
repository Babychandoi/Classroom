package com.classroom.modules.blog.dto;

import com.classroom.modules.classroom.dto.PersonSummaryDto;

import java.time.Instant;

/**
 * D-27: a blog post as returned by the API. {@code contentMarkdown} is null in list responses and whenever {@code locked} is true (a MEMBERS
 * post seen by someone who is not a member). {@code coverUrl} is a short-lived presigned GET of the cover image, generated per response.
 */
public record BlogPostDto(
        String id,
        String classId,
        String title,
        String excerpt,
        String category,
        String contentMarkdown,
        String coverMediaId,
        String coverUrl,
        String audience,
        String status,
        boolean locked,
        int readingMinutes,
        PersonSummaryDto author,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt) {}
