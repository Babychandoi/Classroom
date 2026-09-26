package com.classroom.modules.ranking.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "exam_reward_rules")
public class ExamRewardRule {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(name = "exam_id", nullable = false, length = 36)
    private String examId;

    @Column(name = "min_exam_score", precision = 6, scale = 2, nullable = false)
    private BigDecimal minExamScore;

    @Column(name = "reward_points", nullable = false)
    private int rewardPoints;

    public ExamRewardRule() {
        this.id = UUID.randomUUID().toString();
    }

    public ExamRewardRule(String classId, String examId, BigDecimal minExamScore, int rewardPoints) {
        this.id = UUID.randomUUID().toString();
        this.classId = classId;
        this.examId = examId;
        this.minExamScore = minExamScore;
        this.rewardPoints = rewardPoints;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getClassId() {
        return classId;
    }

    public void setClassId(String classId) {
        this.classId = classId;
    }

    public String getExamId() {
        return examId;
    }

    public void setExamId(String examId) {
        this.examId = examId;
    }

    public BigDecimal getMinExamScore() {
        return minExamScore;
    }

    public void setMinExamScore(BigDecimal minExamScore) {
        this.minExamScore = minExamScore;
    }

    public int getRewardPoints() {
        return rewardPoints;
    }

    public void setRewardPoints(int rewardPoints) {
        this.rewardPoints = rewardPoints;
    }
}
