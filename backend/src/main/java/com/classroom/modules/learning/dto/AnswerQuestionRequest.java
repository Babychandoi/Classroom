package com.classroom.modules.learning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AnswerQuestionRequest {
    @NotBlank(message = "Nội dung câu trả lời không được để trống")
    @Size(max = 5000, message = "Nội dung câu trả lời tối đa 5000 ký tự")
    private String answerText;

    public String getAnswerText() {
        return answerText;
    }

    public void setAnswerText(String answerText) {
        this.answerText = answerText;
    }
}
