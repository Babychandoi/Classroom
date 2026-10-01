package com.classroom.modules.exam.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "exam_attempts", uniqueConstraints = {
        @UniqueConstraint(name = "uq_ea_exam_user_attempt", columnNames = {"exam_id", "user_id", "attempt_number"})
})
public class ExamAttempt implements org.springframework.data.domain.Persistable<String> {

    @Transient
    private boolean newEntity = true;

    @Override
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isNew() { return newEntity; }

    @PostPersist
    @PostLoad
    void markExisting() { newEntity = false; }

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "exam_id", nullable = false, length = 36)
    private String examId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(precision = 6, scale = 2)
    private BigDecimal score;

    @Column(name = "total_points", nullable = false)
    private int totalPoints = 0;

    @Column(nullable = false, length = 32)
    private String status = "IN_PROGRESS"; // IN_PROGRESS, SUBMITTED, GRADING, GRADED, PUBLISHED, CANCELLED

    @Column(name = "is_preview", nullable = false)
    private boolean isPreview = false;

    @Column(name = "audience_eligible_at_start", nullable = false)
    private boolean audienceEligibleAtStart;

    @Column(name = "attempt_number")
    private Integer attemptNumber;

    @Column(name = "question_snapshot_json", columnDefinition = "MEDIUMTEXT")
    private String questionSnapshotJson;

    @Column(name = "grading_snapshot_json", columnDefinition = "MEDIUMTEXT")
    private String gradingSnapshotJson;

    @Column(name = "reward_points_snapshot")
    private Integer rewardPointsSnapshot;

    @Column(name = "reward_score_snapshot", precision = 6, scale = 2)
    private BigDecimal rewardScoreSnapshot;

    @Column(name = "reward_rule_snapshot", length = 2000)
    private String rewardRuleSnapshot;

    @Column(name = "cancel_reason", length = 512)
    private String cancelReason;

    public ExamAttempt() {
        this.id = UUID.randomUUID().toString();
        this.startedAt = Instant.now();
    }

    public ExamAttempt(String examId, String userId, String classId, Instant endsAt, boolean isPreview) {
        this.id = UUID.randomUUID().toString();
        this.examId = examId;
        this.userId = userId;
        this.classId = classId;
        this.startedAt = Instant.now();
        this.endsAt = endsAt;
        this.isPreview = isPreview;
        this.status = "IN_PROGRESS";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getExamId() {
        return examId;
    }

    public void setExamId(String examId) {
        this.examId = examId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getClassId() {
        return classId;
    }

    public void setClassId(String classId) {
        this.classId = classId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(Instant endsAt) {
        this.endsAt = endsAt;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public int getTotalPoints() {
        return totalPoints;
    }

    public void setTotalPoints(int totalPoints) {
        this.totalPoints = totalPoints;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isPreview() {
        return isPreview;
    }

    public void setPreview(boolean preview) {
        isPreview = preview;
    }

    public boolean isAudienceEligibleAtStart() {
        return audienceEligibleAtStart;
    }

    public void setAudienceEligibleAtStart(boolean audienceEligibleAtStart) {
        this.audienceEligibleAtStart = audienceEligibleAtStart;
    }

    public Integer getAttemptNumber() {
        return attemptNumber;
    }

    public void setAttemptNumber(Integer attemptNumber) {
        this.attemptNumber = attemptNumber;
    }

    public String getQuestionSnapshotJson() {
        return questionSnapshotJson;
    }

    public void setQuestionSnapshotJson(String questionSnapshotJson) {
        this.questionSnapshotJson = questionSnapshotJson;
    }

    public String getGradingSnapshotJson() { return gradingSnapshotJson; }
    public void setGradingSnapshotJson(String gradingSnapshotJson) { this.gradingSnapshotJson = gradingSnapshotJson; }
    public Integer getRewardPointsSnapshot() { return rewardPointsSnapshot; }
    public void setRewardPointsSnapshot(Integer rewardPointsSnapshot) { this.rewardPointsSnapshot = rewardPointsSnapshot; }
    public BigDecimal getRewardScoreSnapshot() { return rewardScoreSnapshot; }
    public void setRewardScoreSnapshot(BigDecimal rewardScoreSnapshot) { this.rewardScoreSnapshot = rewardScoreSnapshot; }
    public String getRewardRuleSnapshot() { return rewardRuleSnapshot; }
    public void setRewardRuleSnapshot(String rewardRuleSnapshot) { this.rewardRuleSnapshot = rewardRuleSnapshot; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }

    @PrePersist
    @PreUpdate
    public void ensureLearnerAttemptNumber() {
        if (!isPreview && attemptNumber == null) {
            attemptNumber = 1;
        }
    }
}
