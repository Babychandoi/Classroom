package com.classroom.modules.exam.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class ExamAttemptDto {
    private String id;
    private String examId;
    private String examTitle;
    private String userId;
    private String classId;
    private Instant startedAt;
    private Instant submittedAt;
    private Instant endsAt;
    private BigDecimal score;
    private int totalPoints;
    private String status;
    private boolean isPreview;
    private List<QuestionDto> questions;
    private List<AttemptAnswerDto> answers;

    public ExamAttemptDto() {}

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

    public String getExamTitle() {
        return examTitle;
    }

    public void setExamTitle(String examTitle) {
        this.examTitle = examTitle;
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

    @JsonProperty("isPreview")
    public boolean isPreview() {
        return isPreview;
    }

    @JsonProperty("isPreview")
    public void setPreview(boolean preview) {
        isPreview = preview;
    }

    public List<QuestionDto> getQuestions() {
        return questions;
    }

    public void setQuestions(List<QuestionDto> questions) {
        this.questions = questions;
    }

    public List<AttemptAnswerDto> getAnswers() {
        return answers;
    }

    public void setAnswers(List<AttemptAnswerDto> answers) {
        this.answers = answers;
    }

    public static class AttemptAnswerDto {
        private String questionId;
        private String studentAnswer;
        private BigDecimal pointsAwarded;
        private String teacherFeedback;

        public AttemptAnswerDto() {}

        public AttemptAnswerDto(String questionId, String studentAnswer, BigDecimal pointsAwarded, String teacherFeedback) {
            this.questionId = questionId;
            this.studentAnswer = studentAnswer;
            this.pointsAwarded = pointsAwarded;
            this.teacherFeedback = teacherFeedback;
        }

        public String getQuestionId() {
            return questionId;
        }

        public void setQuestionId(String questionId) {
            this.questionId = questionId;
        }

        public String getStudentAnswer() {
            return studentAnswer;
        }

        public void setStudentAnswer(String studentAnswer) {
            this.studentAnswer = studentAnswer;
        }

        public BigDecimal getPointsAwarded() {
            return pointsAwarded;
        }

        public void setPointsAwarded(BigDecimal pointsAwarded) {
            this.pointsAwarded = pointsAwarded;
        }

        public String getTeacherFeedback() {
            return teacherFeedback;
        }

        public void setTeacherFeedback(String teacherFeedback) {
            this.teacherFeedback = teacherFeedback;
        }
    }
}
