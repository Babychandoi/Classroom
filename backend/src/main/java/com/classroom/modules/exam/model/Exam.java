package com.classroom.modules.exam.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "exams")
public class Exam {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "schedule_start")
    private Instant scheduleStart;

    @Column(name = "schedule_end")
    private Instant scheduleEnd;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes = 60;

    @Column(name = "attempt_limit", nullable = false)
    private int attemptLimit = 1;

    @Column(name = "audience_scope", nullable = false, length = 32)
    private String audienceScope = "ALL"; // ALL, PRO, COURSE, SEGMENT, COURSE_SEGMENT

    @Column(name = "audience_rule_version", nullable = false)
    private int audienceRuleVersion = 1;

    @Column(name = "audience_operator", nullable = false, length = 8)
    private String audienceOperator = "AND";

    @Column(name = "target_course_id", length = 36)
    private String targetCourseId;

    @Column(name = "target_segment_id", length = 36)
    private String targetSegmentId;

    @Column(nullable = false, length = 32)
    private String status = "PUBLISHED"; // DRAFT, PUBLISHED, OPEN, CLOSED, ARCHIVED

    @Column(name = "pass_score", nullable = false)
    private int passScore = 50;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    public Exam() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Exam(String classId, String title, String audienceScope, int durationMinutes) {
        this.id = UUID.randomUUID().toString();
        this.classId = classId;
        this.title = title;
        this.audienceScope = (audienceScope != null) ? audienceScope : "ALL";
        this.durationMinutes = durationMinutes;
        this.status = "PUBLISHED";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getClassId() {
        return classId;
    }

    public void setClassId(String classId) {
        this.classId = classId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getScheduleStart() {
        return scheduleStart;
    }

    public void setScheduleStart(Instant scheduleStart) {
        this.scheduleStart = scheduleStart;
    }

    public Instant getScheduleEnd() {
        return scheduleEnd;
    }

    public void setScheduleEnd(Instant scheduleEnd) {
        this.scheduleEnd = scheduleEnd;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public int getAttemptLimit() {
        return attemptLimit;
    }

    public void setAttemptLimit(int attemptLimit) {
        this.attemptLimit = attemptLimit;
    }

    public String getAudienceScope() {
        return audienceScope;
    }

    public void setAudienceScope(String audienceScope) {
        this.audienceScope = audienceScope;
    }

    public int getAudienceRuleVersion() { return audienceRuleVersion; }
    public void setAudienceRuleVersion(int audienceRuleVersion) { this.audienceRuleVersion = audienceRuleVersion; }
    public String getAudienceOperator() { return audienceOperator; }
    public void setAudienceOperator(String audienceOperator) { this.audienceOperator = audienceOperator; }

    public String getTargetCourseId() {
        return targetCourseId;
    }

    public void setTargetCourseId(String targetCourseId) {
        this.targetCourseId = targetCourseId;
    }

    public String getTargetSegmentId() {
        return targetSegmentId;
    }

    public void setTargetSegmentId(String targetSegmentId) {
        this.targetSegmentId = targetSegmentId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getPassScore() {
        return passScore;
    }

    public void setPassScore(int passScore) {
        this.passScore = passScore;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }
}
