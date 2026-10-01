package com.classroom.modules.classroom.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateClassAboutRequest(
        @Size(max = 100000) String contentMarkdown,
        @Size(max = 100000) String rulesMarkdown,
        @Size(max = 12) List<@jakarta.validation.constraints.NotNull @Valid AboutSectionDto> sections,
        @Min(1) Integer publishedVersion) {}
