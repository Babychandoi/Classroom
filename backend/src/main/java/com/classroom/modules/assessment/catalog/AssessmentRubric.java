package com.classroom.modules.assessment.catalog;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * D-34: a grading rubric - a system preset ({@code system_preset}, class_id NULL) or a class rubric. levels_json is a JSON array of level
 * labels (lowest first), criteria_json a JSON array of {@code {id, title, weight}} whose weights sum to 100. Table {@code assessment_rubrics}.
 */
@Entity
@Table(name = "assessment_rubrics")
public class AssessmentRubric {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", length = 36)
    private String classId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "levels_json", nullable = false, columnDefinition = "TEXT")
    private String levelsJson;

    @Column(name = "criteria_json", nullable = false, columnDefinition = "TEXT")
    private String criteriaJson;

    @Column(name = "system_preset", nullable = false)
    private boolean systemPreset;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AssessmentRubric() {
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public AssessmentRubric(String id, String classId, String title, String levelsJson, String criteriaJson, boolean systemPreset, String createdBy) {
        this();
        this.id = id;
        this.classId = classId;
        this.title = title;
        this.levelsJson = levelsJson;
        this.criteriaJson = criteriaJson;
        this.systemPreset = systemPreset;
        this.createdBy = createdBy;
    }

    public String getId() { return id; }
    public String getClassId() { return classId; }
    public String getTitle() { return title; }
    public String getLevelsJson() { return levelsJson; }
    public String getCriteriaJson() { return criteriaJson; }
    public boolean isSystemPreset() { return systemPreset; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
