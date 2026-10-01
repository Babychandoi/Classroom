package com.classroom.modules.exam.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "exam_publication_snapshots")
public class ExamPublicationSnapshot {
    @Id @Column(name = "exam_id", length = 36) private String examId;
    @JsonIgnore @Column(name = "learner_json", columnDefinition = "MEDIUMTEXT", nullable = false) private String learnerJson;
    @JsonIgnore @Column(name = "grading_json", columnDefinition = "MEDIUMTEXT", nullable = false) private String gradingJson;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected ExamPublicationSnapshot() {}
    public ExamPublicationSnapshot(String examId, String learnerJson, String gradingJson) {
        this.examId = examId; this.learnerJson = learnerJson; this.gradingJson = gradingJson; this.createdAt = Instant.now();
    }
    public String getExamId() { return examId; }
    @JsonIgnore public String getLearnerJson() { return learnerJson; }
    @JsonIgnore public String getGradingJson() { return gradingJson; }
    public Instant getCreatedAt() { return createdAt; }
}
