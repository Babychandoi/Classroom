package com.classroom.modules.exam.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "attempt_answers", uniqueConstraints = {
        @UniqueConstraint(name = "uq_aa_attempt_question", columnNames = {"attempt_id", "question_id"})
})
public class AttemptAnswer implements Persistable<String> {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "attempt_id", nullable = false, length = 36)
    private String attemptId;

    @Column(name = "question_id", nullable = false, length = 36)
    private String questionId;

    @Column(name = "student_answer", columnDefinition = "TEXT")
    private String studentAnswer;

    @Column(name = "points_awarded", precision = 10, scale = 2)
    private BigDecimal pointsAwarded;

    @Column(name = "teacher_feedback", columnDefinition = "TEXT")
    private String teacherFeedback;

    @Column(name = "graded_by", length = 36)
    private String gradedBy;

    /**
     * R20-06: the id is assigned in the constructor, so without this Spring Data would treat every new answer as a detached
     * entity and {@code save()} would issue a {@code SELECT} by id (merge) before the INSERT - one wasted statement per answer.
     */
    @Transient
    private boolean newEntity = true;

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }

    @Override
    @JsonIgnore
    public boolean isNew() {
        return newEntity;
    }

    public AttemptAnswer() {
        this.id = UUID.randomUUID().toString();
    }

    public AttemptAnswer(String attemptId, String questionId, String studentAnswer) {
        this.id = UUID.randomUUID().toString();
        this.attemptId = attemptId;
        this.questionId = questionId;
        this.studentAnswer = studentAnswer;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(String attemptId) {
        this.attemptId = attemptId;
    }

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
    }

    public String getStudentAnswer() {
        return studentAnswer;
    }

    public void setStudentAnswer(String studentAnswer) {
        this.studentAnswer = studentAnswer;
    }

    public BigDecimal getPointsAwarded() {
        return pointsAwarded;
    }

    public void setPointsAwarded(BigDecimal pointsAwarded) {
        this.pointsAwarded = pointsAwarded;
    }

    public String getTeacherFeedback() {
        return teacherFeedback;
    }

    public void setTeacherFeedback(String teacherFeedback) {
        this.teacherFeedback = teacherFeedback;
    }

    public String getGradedBy() {
        return gradedBy;
    }

    public void setGradedBy(String gradedBy) {
        this.gradedBy = gradedBy;
    }
}
