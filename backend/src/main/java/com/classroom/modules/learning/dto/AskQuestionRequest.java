package com.classroom.modules.learning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AskQuestionRequest {
    @NotBlank(message = "Nội dung câu hỏi không được để trống")
    @Size(max = 5000, message = "Nội dung câu hỏi tối đa 5000 ký tự")
    private String questionText;

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }
}
