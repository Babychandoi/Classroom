package com.classroom.modules.event.dto;

import com.classroom.modules.classroom.dto.PersonSummaryDto;

import java.time.Instant;

/** D-27: one row of GET /events/{id}/registrations (managers only). */
public record EventRegistrantDto(PersonSummaryDto user, Instant registeredAt) {}
