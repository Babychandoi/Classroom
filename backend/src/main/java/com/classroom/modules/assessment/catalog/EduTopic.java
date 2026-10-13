package com.classroom.modules.assessment.catalog;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** D-34: an optional topic of a subject in a grade (shared when class_id is NULL), table {@code edu_topics} (V50). */
@Entity
@Table(name = "edu_topics")
public class EduTopic {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", length = 36)
    private String classId;

    @Column(name = "subject_code", nullable = false, length = 48)
    private String subjectCode;

    @Column(nullable = false)
    private int grade;

    @Column(name = "parent_id", length = 36)
    private String parentId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public EduTopic() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public String getId() { return id; }
    public String getClassId() { return classId; }
    public void setClassId(String classId) { this.classId = classId; }
    public String getSubjectCode() { return subjectCode; }
    public void setSubjectCode(String subjectCode) { this.subjectCode = subjectCode; }
    public int getGrade() { return grade; }
    public void setGrade(int grade) { this.grade = grade; }
    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
