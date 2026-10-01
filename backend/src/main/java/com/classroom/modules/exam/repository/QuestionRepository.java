package com.classroom.modules.exam.repository;

import com.classroom.modules.exam.model.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuestionRepository extends JpaRepository<Question, String> {
    List<Question> findByExamIdOrderByPositionAsc(String examId);
    long countByExamId(String examId);
    interface QuestionCount { String getExamId(); long getQuestions(); }
    @org.springframework.data.jpa.repository.Query("SELECT q.examId AS examId, COUNT(q) AS questions FROM Question q WHERE q.examId IN :examIds GROUP BY q.examId")
    List<QuestionCount> countByExamIds(@org.springframework.data.repository.query.Param("examIds") List<String> examIds);
}
