package com.classroom.modules.exam.repository;

import com.classroom.modules.exam.model.AnswerOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnswerOptionRepository extends JpaRepository<AnswerOption, String> {
    List<AnswerOption> findByQuestionIdOrderByPositionAsc(String questionId);
}
