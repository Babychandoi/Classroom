package com.classroom.modules.learning.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lesson_progress")
public class LessonProgress {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "lesson_id", nullable = false, length = 36)
    private String lessonId;

    @Column(name = "course_id", nullable = false, length = 36)
    private String courseId;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(nullable = false)
    private boolean completed = true;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    public LessonProgress() {
        this.id = UUID.randomUUID().toString();
        this.completedAt = Instant.now();
    }

    public LessonProgress(String userId, String lessonId, String courseId, String classId) {
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.lessonId = lessonId;
        this.courseId = courseId;
        this.classId = classId;
        this.completed = true;
        this.completedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getLessonId() {
        return lessonId;
    }

    public void setLessonId(String lessonId) {
        this.lessonId = lessonId;
    }

    public String getCourseId() {
        return courseId;
    }

    public void setCourseId(String courseId) {
        this.courseId = courseId;
    }

    public String getClassId() {
        return classId;
    }

    public void setClassId(String classId) {
        this.classId = classId;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
