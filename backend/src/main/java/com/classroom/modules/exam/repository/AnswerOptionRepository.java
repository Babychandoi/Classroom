package com.classroom.modules.exam.repository;

import com.classroom.modules.exam.model.AnswerOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnswerOptionRepository extends JpaRepository<AnswerOption, String> {
    List<AnswerOption> findByQuestionIdOrderByPositionAsc(String questionId);

    /** R20-06: options of many questions in one query (position order within each question is preserved). */
    List<AnswerOption> findByQuestionIdInOrderByPositionAsc(java.util.Collection<String> questionIds);
}
