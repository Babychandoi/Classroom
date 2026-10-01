package com.classroom.modules.learning.repository;

import com.classroom.modules.learning.model.LessonQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LessonQuestionRepository extends JpaRepository<LessonQuestion, String> {
    List<LessonQuestion> findByLessonIdOrderByCreatedAtDesc(String lessonId);
    boolean existsByLessonId(String lessonId);
}
