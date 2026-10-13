package com.classroom.modules.assessment.doc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D-34: the whole editable assessment ({@code assessments.content_json}, schemaVersion 1). Identical in Java and TypeScript
 * ({@code frontend/src/features/assessment/types.ts}). {@code kind} HOMEWORK | QUIZ | REVIEW | WORKSHEET | PRACTICE.
 */
public record AssessmentDocument(
        int schemaVersion,
        String title,
        String kind,
        Integer grade,
        String subjectCode,
        String languageCode,
        String topicTitle,
        Map<String, AssetRef> assets,
        List<SectionNode> sections,
        String audioAssetKey,
        PlaybackPolicy playback
) {
    public AssessmentDocument(int schemaVersion,String title,String kind,Integer grade,String subjectCode,String languageCode,String topicTitle,Map<String,AssetRef> assets,List<SectionNode> sections){this(schemaVersion,title,kind,grade,subjectCode,languageCode,topicTitle,assets,sections,null,PlaybackPolicy.DEFAULT);}
    public static final int SCHEMA_VERSION = 1;

    public AssessmentDocument {
        title = title == null ? "" : title;
        playback=playback==null?PlaybackPolicy.DEFAULT:playback;
        kind = kind == null ? "HOMEWORK" : kind;
        assets = assets == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(assets));
        sections = sections == null ? List.of() : List.copyOf(sections);
        subjectCode = blankToNull(subjectCode);
        languageCode = blankToNull(languageCode);
        topicTitle = blankToNull(topicTitle);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public AssessmentDocument withSections(List<SectionNode> newSections) {
        return new AssessmentDocument(schemaVersion, title, kind, grade, subjectCode, languageCode, topicTitle, assets, newSections,audioAssetKey,playback);
    }

    public AssessmentDocument withMeta(String newTitle, String newKind, Integer newGrade, String newSubject, String newLanguage, String newTopic) {
        return new AssessmentDocument(schemaVersion, newTitle, newKind, newGrade, newSubject, newLanguage, newTopic, assets, sections,audioAssetKey,playback);
    }

    public static AssessmentDocument empty(String title, String kind, Integer grade, String subjectCode, String languageCode, String topicTitle) {
        return new AssessmentDocument(SCHEMA_VERSION, title, kind, grade, subjectCode, languageCode, topicTitle, Map.of(),
                List.of(new SectionNode("s1", "Phần 1", "", false, List.of())));
    }
}
