package com.classroom.modules.learning.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * R12-03: replaces exposing the raw AssignmentSubmission JPA entity from AssignmentController.
 * The entity only carries lessonId/courseId/userId - StudioGrading.tsx rendered those raw UUIDs
 * directly because there was nothing else to show. This DTO resolves the lesson/course titles and
 * the learner's display name (respecting ProfileVisibilityPolicy, the same policy the exam grading
 * queue and profile/leaderboard endpoints use) once per batch in AssignmentService, rather than one
 * extra query per row.
 */
public class AssignmentSubmissionDto {
    private String submissionId;
    private String lessonId;
    private String lessonTitle;
    private String courseId;
    private String courseTitle;
    private Learner learner;
    private String submissionText;
    private int attemptNumber;
    private String status;
    private Instant submittedAt;
    private BigDecimal score;
    private String feedback;

    public static class Learner {
        private String userId;
        private String displayName;

        public Learner() {}
        public Learner(String userId, String displayName) {
            this.userId = userId;
            this.displayName = displayName;
        }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
    }

    public String getSubmissionId() { return submissionId; }
    public void setSubmissionId(String submissionId) { this.submissionId = submissionId; }
    public String getLessonId() { return lessonId; }
    public void setLessonId(String lessonId) { this.lessonId = lessonId; }
    public String getLessonTitle() { return lessonTitle; }
    public void setLessonTitle(String lessonTitle) { this.lessonTitle = lessonTitle; }
    public String getCourseId() { return courseId; }
    public void setCourseId(String courseId) { this.courseId = courseId; }
    public String getCourseTitle() { return courseTitle; }
    public void setCourseTitle(String courseTitle) { this.courseTitle = courseTitle; }
    public Learner getLearner() { return learner; }
    public void setLearner(Learner learner) { this.learner = learner; }
    public String getSubmissionText() { return submissionText; }
    public void setSubmissionText(String submissionText) { this.submissionText = submissionText; }
    public int getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(int attemptNumber) { this.attemptNumber = attemptNumber; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
}
