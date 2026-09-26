package com.classroom.modules.exam.dto;

import java.math.BigDecimal;
import java.util.Map;

public class SubmitAttemptRequest {
    // Map of questionId -> studentAnswer
    private Map<String, String> answers;

    public SubmitAttemptRequest() {}

    public Map<String, String> getAnswers() {
        return answers;
    }

    public void setAnswers(Map<String, String> answers) {
        this.answers = answers;
    }
}
