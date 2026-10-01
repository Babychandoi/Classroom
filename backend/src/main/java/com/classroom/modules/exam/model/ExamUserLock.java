package com.classroom.modules.exam.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * R20-06: one row per (exam, user) whose only purpose is to be locked. {@code ExamAttemptRepository#lockUserScope} inserts it
 * (or finds it) with {@code ON DUPLICATE KEY UPDATE}, which takes an exclusive record lock held until the transaction ends;
 * every "may this learner start / resume an attempt" decision runs under it. The entity exists so the schema (Flyway V33 on
 * MySQL, Hibernate DDL on the in-memory test database) has the table; application code never loads it.
 */
@Entity
@Table(name = "exam_user_locks")
@IdClass(ExamUserLock.Key.class)
public class ExamUserLock {

    @Id
    @Column(name = "exam_id", length = 36, nullable = false)
    private String examId;

    @Id
    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    protected ExamUserLock() {
    }

    public ExamUserLock(String examId, String userId) {
        this.examId = examId;
        this.userId = userId;
    }

    public String getExamId() {
        return examId;
    }

    public String getUserId() {
        return userId;
    }

    public static class Key implements Serializable {
        private String examId;
        private String userId;

        public Key() {
        }

        public Key(String examId, String userId) {
            this.examId = examId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && Objects.equals(examId, other.examId) && Objects.equals(userId, other.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(examId, userId);
        }
    }
}
