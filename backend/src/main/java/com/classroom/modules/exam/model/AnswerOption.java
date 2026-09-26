package com.classroom.modules.exam.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "answer_options")
public class AnswerOption {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "question_id", nullable = false, length = 36)
    private String questionId;

    @Column(name = "option_key", nullable = false, length = 32)
    private String optionKey; // A, B, C, D

    @Column(name = "option_text", columnDefinition = "TEXT", nullable = false)
    private String optionText;

    @Column(nullable = false)
    private int position = 0;

    public AnswerOption() {
        this.id = UUID.randomUUID().toString();
    }

    public AnswerOption(String questionId, String optionKey, String optionText, int position) {
        this.id = UUID.randomUUID().toString();
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
