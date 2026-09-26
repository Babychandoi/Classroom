package com.classroom.modules.learning.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lesson_questions")
public class LessonQuestion {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "lesson_id", nullable = false, length = 36)
    private String lessonId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "author_name", nullable = false)
    private String authorName;

    @Column(name = "question_text", columnDefinition = "TEXT", nullable = false)
    private String questionText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public LessonQuestion() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }

    public LessonQuestion(String lessonId, String userId, String authorName, String questionText) {
        this.id = UUID.randomUUID().toString();
        this.lessonId = lessonId;
        this.userId = userId;
        this.authorName = authorName;
        this.questionText = questionText;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLessonId() {
        return lessonId;
    }

    public void setLessonId(String lessonId) {
        this.lessonId = lessonId;
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

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
