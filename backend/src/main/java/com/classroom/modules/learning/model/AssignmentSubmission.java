package com.classroom.modules.learning.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "assignment_submissions", uniqueConstraints = @UniqueConstraint(name = "uk_assignment_submission_attempt", columnNames = {"lesson_id", "user_id", "attempt_number"}))
public class AssignmentSubmission {
    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();
    @Column(name = "lesson_id", nullable = false, length = 36)
    private String lessonId;
    @Column(name = "course_id", nullable = false, length = 36)
    private String courseId;
    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;
    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;
    @Column(name = "submission_text", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String submissionText;
    @Column(nullable = false, length = 24)
    private String status = "SUBMITTED";
    @Column(precision = 8, scale = 2)
    private java.math.BigDecimal score;
    @Column(columnDefinition = "TEXT")
    private String feedback;
    @Column(name = "graded_by", length = 36)
    private String gradedBy;
    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt = Instant.now();
    @Column(name = "graded_at")
    private Instant gradedAt;

    protected AssignmentSubmission() {}
    public AssignmentSubmission(String lessonId, String courseId, String classId, String userId, int attemptNumber, String submissionText) {
        this.lessonId = lessonId; this.courseId = courseId; this.classId = classId; this.userId = userId;
        this.attemptNumber = attemptNumber; this.submissionText = submissionText; this.submittedAt = Instant.now();
    }
    public String getId() { return id; }
    public String getLessonId() { return lessonId; }
    public String getCourseId() { return courseId; }
    public String getClassId() { return classId; }
    public String getUserId() { return userId; }
    public int getAttemptNumber() { return attemptNumber; }
    public String getSubmissionText() { return submissionText; }
    public String getStatus() { return status; }
    public java.math.BigDecimal getScore() { return score; }
    public String getFeedback() { return feedback; }
    public String getGradedBy() { return gradedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getGradedAt() { return gradedAt; }
    public void grade(java.math.BigDecimal score, String feedback, String grader) {
        this.score = score; this.feedback = feedback; this.gradedBy = grader; this.gradedAt = Instant.now(); this.status = "GRADED";
    }
}
