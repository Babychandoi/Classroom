package com.classroom.modules.exam.repository;

import com.classroom.modules.exam.model.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuestionRepository extends JpaRepository<Question, String> {
    List<Question> findByExamIdOrderByPositionAsc(String examId);
    long countByExamId(String examId);
}
