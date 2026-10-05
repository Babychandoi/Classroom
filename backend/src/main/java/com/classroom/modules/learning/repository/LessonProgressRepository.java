package com.classroom.modules.learning.repository;

import com.classroom.modules.learning.model.LessonProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LessonProgressRepository extends JpaRepository<LessonProgress, String> {
    Optional<LessonProgress> findByUserIdAndLessonId(String userId, String lessonId);
    List<LessonProgress> findByUserIdAndCourseId(String userId, String courseId);
    long countByUserIdAndCourseIdAndCompletedTrue(String userId, String courseId);
    long countByUserIdAndClassIdAndCompletedTrue(String userId, String classId);
    boolean existsByLessonId(String lessonId);

    /**
     * R13-05 (FR-12): batch completed-lesson counts per course for one learner, backing the
     * member journey view without one countByUserIdAndCourseIdAndCompletedTrue call per course.
     */
    @Query("SELECT p.courseId AS courseId, COUNT(p) AS total FROM LessonProgress p "
            + "WHERE p.userId = :userId AND p.courseId IN :courseIds AND p.completed = true GROUP BY p.courseId")
    List<CourseProgressCount> countCompletedByUserAndCourseIdIn(@Param("userId") String userId, @Param("courseIds") List<String> courseIds);

    /**
     * R14-03: completed-lesson counts per course that only count progress on learner-visible lessons
     * (lesson not archived and its section not archived) - the numerator counterpart of
     * {@code LessonRepository#countVisibleByCourseIdIn}, so archived lessons cannot push the
     * percentage above what the visible curriculum allows. One grouped query for all courses.
     */
    @Query("SELECT p.courseId AS courseId, COUNT(p) AS total FROM LessonProgress p JOIN Lesson l ON l.id = p.lessonId "
            + "WHERE p.userId = :userId AND p.courseId IN :courseIds AND p.completed = true AND l.archived = false "
            + "AND NOT EXISTS (SELECT s.id FROM Section s WHERE s.id = l.sectionId AND s.archived = true) "
            + "GROUP BY p.courseId")
    List<CourseProgressCount> countCompletedVisibleByUserAndCourseIdIn(@Param("userId") String userId, @Param("courseIds") List<String> courseIds);

    /**
     * R14-03: class-wide completed-lesson count over learner-visible lessons only, used by the
     * COMPLETED_LESSONS_COUNT segment criterion so it stays consistent with the per-course progress
     * shown to the learner.
     */
    @Query("SELECT COUNT(p) FROM LessonProgress p JOIN Lesson l ON l.id = p.lessonId "
            + "WHERE p.userId = :userId AND p.classId = :classId AND p.completed = true AND l.archived = false "
            + "AND NOT EXISTS (SELECT s.id FROM Section s WHERE s.id = l.sectionId AND s.archived = true)")
    long countCompletedVisibleByUserIdAndClassId(@Param("userId") String userId, @Param("classId") String classId);

    interface CourseProgressCount {
        String getCourseId();
        long getTotal();
    }

    /** D-30: per course the caller has any progress row in: row count and the latest update; one grouped query for all candidates. */
    @Query("SELECT p.courseId AS courseId, COUNT(p) AS total, MAX(p.completedAt) AS lastAt FROM LessonProgress p "
            + "WHERE p.userId = :userId AND p.courseId IN :courseIds GROUP BY p.courseId")
    List<CourseActivity> activityByUserAndCourseIdIn(@Param("userId") String userId, @Param("courseIds") List<String> courseIds);

    /** D-30: the lessons the caller completed in these courses (for the resume point). */
    @Query("SELECT p.lessonId FROM LessonProgress p WHERE p.userId = :userId AND p.courseId IN :courseIds AND p.completed = true")
    List<String> findCompletedLessonIds(@Param("userId") String userId, @Param("courseIds") List<String> courseIds);

    interface CourseActivity {
        String getCourseId();
        long getTotal();
        java.time.Instant getLastAt();
    }
}
