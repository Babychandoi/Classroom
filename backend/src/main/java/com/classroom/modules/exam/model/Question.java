package com.classroom.modules.exam.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "questions")
public class Question {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "exam_id", nullable = false, length = 36)
    private String examId;

    @Column(name = "question_text", columnDefinition = "TEXT", nullable = false)
    private String questionText;

    @Column(nullable = false, length = 32)
    private String type = "MULTIPLE_CHOICE"; // MULTIPLE_CHOICE, ESSAY, TRUE_FALSE

    @Column(nullable = false)
    private int points = 10;

    @Column(nullable = false)
    private int position = 0;

    @Column(name = "answer_key", columnDefinition = "TEXT")
    private String answerKey; // Correct option key (e.g., 'A') or rubric

    public Question() {
        this.id = UUID.randomUUID().toString();
    }

    public Question(String examId, String questionText, String type, int points, int position, String answerKey) {
        this.id = UUID.randomUUID().toString();
        this.examId = examId;
        this.questionText = questionText;
        this.type = (type != null) ? type : "MULTIPLE_CHOICE";
        this.points = points;
        this.position = position;
        this.answerKey = answerKey;
    }

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

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = points;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getAnswerKey() {
        return answerKey;
    }

    public void setAnswerKey(String answerKey) {
        this.answerKey = answerKey;
    }
}
