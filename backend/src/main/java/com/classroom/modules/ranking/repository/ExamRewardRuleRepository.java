package com.classroom.modules.ranking.repository;

import com.classroom.modules.ranking.model.ExamRewardRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExamRewardRuleRepository extends JpaRepository<ExamRewardRule, String> {
    List<ExamRewardRule> findByClassIdAndExamIdOrderByMinExamScoreDesc(String classId, String examId);
    List<ExamRewardRule> findByClassId(String classId);
    void deleteByClassId(String classId);
}
