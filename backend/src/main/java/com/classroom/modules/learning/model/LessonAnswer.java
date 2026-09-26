package com.classroom.modules.learning.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lesson_answers")
public class LessonAnswer {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "question_id", nullable = false, length = 36)
    private String questionId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "author_name", nullable = false)
    private String authorName;

    @Column(name = "answer_text", columnDefinition = "TEXT", nullable = false)
    private String answerText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public LessonAnswer() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }

    public LessonAnswer(String questionId, String userId, String authorName, String answerText) {
        this.id = UUID.randomUUID().toString();
        this.questionId = questionId;
        this.userId = userId;
        this.authorName = authorName;
        this.answerText = answerText;
        this.createdAt = Instant.now();
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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public String getAnswerText() {
        return answerText;
    }

    public void setAnswerText(String answerText) {
        this.answerText = answerText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
