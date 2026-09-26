package com.classroom.modules.learning.repository;

import com.classroom.modules.learning.model.LessonAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LessonAnswerRepository extends JpaRepository<LessonAnswer, String> {
    List<LessonAnswer> findByQuestionIdOrderByCreatedAtAsc(String questionId);
}
