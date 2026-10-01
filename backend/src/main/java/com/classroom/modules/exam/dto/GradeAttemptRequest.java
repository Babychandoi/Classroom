package com.classroom.modules.exam.dto;

import java.math.BigDecimal;
import java.util.Map;

public class GradeAttemptRequest {
    // Map of questionId -> pointsAwarded
    private Map<String, BigDecimal> scores;
    // Map of questionId -> teacherFeedback
    private Map<String, String> feedback;
    // R13-07: mandatory, non-blank when correcting a PUBLISHED attempt's score (score correction
    // audit trail). Optional otherwise (first-time grading of a SUBMITTED/GRADING attempt).
    private String reason;

    public GradeAttemptRequest() {}

    public Map<String, BigDecimal> getScores() {
        return scores;
    }

    public void setScores(Map<String, BigDecimal> scores) {
        this.scores = scores;
    }

    public Map<String, String> getFeedback() {
        return feedback;
    }

    public void setFeedback(Map<String, String> feedback) {
        this.feedback = feedback;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
