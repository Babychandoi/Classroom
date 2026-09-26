package com.classroom.modules.exam.dto;

import java.math.BigDecimal;
import java.util.Map;

public class GradeAttemptRequest {
    // Map of questionId -> pointsAwarded
    private Map<String, BigDecimal> scores;
    // Map of questionId -> teacherFeedback
    private Map<String, String> feedback;

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
}
