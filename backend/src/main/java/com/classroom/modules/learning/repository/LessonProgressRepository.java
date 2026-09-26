package com.classroom.modules.learning.repository;

import com.classroom.modules.learning.model.LessonProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LessonProgressRepository extends JpaRepository<LessonProgress, String> {
    Optional<LessonProgress> findByUserIdAndLessonId(String userId, String lessonId);
    List<LessonProgress> findByUserIdAndCourseId(String userId, String courseId);
    long countByUserIdAndCourseIdAndCompletedTrue(String userId, String courseId);
    long countByUserIdAndClassIdAndCompletedTrue(String userId, String classId);
}
