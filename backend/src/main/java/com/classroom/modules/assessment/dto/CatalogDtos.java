package com.classroom.modules.assessment.dto;

import java.util.List;

/** D-34: catalog DTOs (API.md §14, TS {@code CatalogDto} / {@code SubjectDto} / {@code TypeMeta} / {@code Rubric}). */
public final class CatalogDtos {
    private CatalogDtos() {}

    public record TypeMeta(String code, String key, String name, String description, String family, String phase,
                           String defaultMode, boolean container, boolean available) {}

    public record SubjectDto(String code, String name, String kind, boolean integrated, String groupLabel, String parentCode,
                             List<String> componentCodes, List<Integer> grades, boolean languageRequired, String defaultLanguageCode,
                             List<String> suggestedTypes, int sortOrder) {}

    public record RubricCriterion(String id, String title, double weight) {}

    public record RubricDto(String id, String title, List<String> levels, List<RubricCriterion> criteria, boolean system) {}

    public record LevelDto(String code, String name, List<Integer> grades) {}

    public record LanguageDto(String code, String name, List<String> voices) {}

    public record CatalogDto(List<LevelDto> levels, List<SubjectDto> subjects, List<LanguageDto> languages, List<TypeMeta> types,
                             List<RubricDto> rubrics) {}
}
