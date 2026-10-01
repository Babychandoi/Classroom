package com.classroom.modules.identity.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * R13-05 (FR-12/D-05): a member's learning/exam journey in one class — per-course progress and
 * published exam results. Returned only when {@link com.classroom.modules.identity.policy.ProfileVisibilityPolicy}
 * grants the caller visibility into the target's identity/activity for that class (self, class
 * administrators, or a peer the target has opted to share with).
 */
public class UserJourneyDto {
    private String userId;
    private String classId;
    private List<CourseProgress> courses = List.of();
    private List<ExamResult> examResults = List.of();

    public UserJourneyDto() {}

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getClassId() { return classId; }
    public void setClassId(String classId) { this.classId = classId; }
    public List<CourseProgress> getCourses() { return courses; }
    public void setCourses(List<CourseProgress> courses) { this.courses = courses; }
    public List<ExamResult> getExamResults() { return examResults; }
    public void setExamResults(List<ExamResult> examResults) { this.examResults = examResults; }

    public static class CourseProgress {
        private String courseId;
        private String courseTitle;
        private int completedLessons;
        private int totalLessons;

        public CourseProgress() {}

        public CourseProgress(String courseId, String courseTitle, int completedLessons, int totalLessons) {
            this.courseId = courseId;
            this.courseTitle = courseTitle;
            this.completedLessons = completedLessons;
            this.totalLessons = totalLessons;
        }

        public String getCourseId() { return courseId; }
        public void setCourseId(String courseId) { this.courseId = courseId; }
        public String getCourseTitle() { return courseTitle; }
        public void setCourseTitle(String courseTitle) { this.courseTitle = courseTitle; }
        public int getCompletedLessons() { return completedLessons; }
        public void setCompletedLessons(int completedLessons) { this.completedLessons = completedLessons; }
        public int getTotalLessons() { return totalLessons; }
        public void setTotalLessons(int totalLessons) { this.totalLessons = totalLessons; }
    }

    public static class ExamResult {
        private String examId;
        private String examTitle;
        private BigDecimal score;
        private Instant submittedAt;

        public ExamResult() {}

        public ExamResult(String examId, String examTitle, BigDecimal score, Instant submittedAt) {
            this.examId = examId;
            this.examTitle = examTitle;
            this.score = score;
            this.submittedAt = submittedAt;
        }

        public String getExamId() { return examId; }
        public void setExamId(String examId) { this.examId = examId; }
        public String getExamTitle() { return examTitle; }
        public void setExamTitle(String examTitle) { this.examTitle = examTitle; }
        public BigDecimal getScore() { return score; }
        public void setScore(BigDecimal score) { this.score = score; }
        public Instant getSubmittedAt() { return submittedAt; }
        public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    }
}
