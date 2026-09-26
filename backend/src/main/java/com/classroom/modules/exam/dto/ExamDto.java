package com.classroom.modules.exam.dto;

import java.time.Instant;
import java.util.List;

public class ExamDto {
    private String id;
    private String classId;
    private String title;
    private String description;
    private Instant scheduleStart;
    private Instant scheduleEnd;
    private int durationMinutes;
    private int attemptLimit;
    private String audienceScope;
    private String targetCourseId;
    private String targetSegmentId;
    private String status;
    private int passScore;
    private boolean canEnter;
    private long userAttemptsCount;
    private int questionCount;
    private Instant createdAt;
    private List<QuestionDto> questions;

    public ExamDto() {}

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

    public boolean isCanEnter() {
        return canEnter;
    }

    public void setCanEnter(boolean canEnter) {
        this.canEnter = canEnter;
    }

    public long getUserAttemptsCount() {
        return userAttemptsCount;
    }

    public void setUserAttemptsCount(long userAttemptsCount) {
        this.userAttemptsCount = userAttemptsCount;
    }

    public int getQuestionCount() {
        return questionCount;
    }

    public void setQuestionCount(int questionCount) {
        this.questionCount = questionCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<QuestionDto> getQuestions() {
        return questions;
    }

    public void setQuestions(List<QuestionDto> questions) {
        this.questions = questions;
    }
}
