package com.classroom.modules.classroom.dto;

import java.time.Instant;
import java.util.List;

public record ClassAboutDto(String id, String classId, String contentMarkdown, String rulesMarkdown,
                            int publishedVersion, Instant updatedAt, List<AboutSectionDto> sections) {}
