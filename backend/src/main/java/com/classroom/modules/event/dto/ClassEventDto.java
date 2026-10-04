package com.classroom.modules.event.dto;

import com.classroom.modules.classroom.dto.PersonSummaryDto;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * D-27: an event as returned by the API. {@code meetingUrl} is only filled for a registered caller and for managers (EVENT:VIEW / EVENT:EDIT,
 * owner); {@code classTitle} / {@code classSlug} only on GET /events/upcoming. {@code coverUrl} is a short-lived presigned GET of the cover.
 */
public record ClassEventDto(
        String id,
        String classId,
        String classTitle,
        String classSlug,
        String title,
        String description,
        String forWhom,
        List<String> takeaways,
        String format,
        String location,
        String meetingUrl,
        Instant startsAt,
        Instant endsAt,
        Integer capacity,
        int registeredCount,
        @JsonProperty("isRegistered") boolean isRegistered,
        @JsonProperty("isFull") boolean isFull,
        PersonSummaryDto host,
        String coverMediaId,
        String coverUrl,
        String audience,
        String status,
        Instant createdAt) {}
