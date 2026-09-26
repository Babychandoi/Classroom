package com.classroom.modules.exam.dto;

public class AnswerOptionDto {
    private String id;
    private String questionId;
    private String optionKey;
    private String optionText;
    private int position;

    public AnswerOptionDto() {}

    public AnswerOptionDto(String id, String questionId, String optionKey, String optionText, int position) {
        this.id = id;
        this.questionId = questionId;
        this.optionKey = optionKey;
        this.optionText = optionText;
        this.position = position;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
    }

    public String getOptionKey() {
        return optionKey;
    }

    public void setOptionKey(String optionKey) {
        this.optionKey = optionKey;
    }

    public String getOptionText() {
        return optionText;
    }

    public void setOptionText(String optionText) {
        this.optionText = optionText;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }
}
