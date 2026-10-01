package com.classroom.modules.learning.repository;

import com.classroom.modules.learning.model.Lesson;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LessonRepository extends JpaRepository<Lesson, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Lesson l WHERE l.id = :id")
    java.util.Optional<Lesson> findByIdForUpdate(@Param("id") String id);
    List<Lesson> findByCourseIdOrderByPositionAsc(String courseId);
    List<Lesson> findBySectionIdOrderByPositionAsc(String sectionId);
    long countByCourseId(String courseId);
    long countBySectionId(String sectionId);
    List<Lesson> findByMediaAssetId(String mediaAssetId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Lesson l WHERE l.sectionId = :sectionId")
    List<Lesson> findBySectionIdForUpdate(@Param("sectionId") String sectionId);

    /**
     * R13-05 (FR-12): batch total-lesson counts per course for the member journey view, so
     * rendering N courses costs one query instead of N (countByCourseId per course).
     */
    @Query("SELECT l.courseId AS courseId, COUNT(l) AS total FROM Lesson l WHERE l.courseId IN :courseIds GROUP BY l.courseId")
    List<CourseLessonCount> countByCourseIdIn(@Param("courseIds") List<String> courseIds);

    /**
     * R14-03: like {@link #countByCourseIdIn} but counts only learner-visible lessons - a lesson that
     * is not archived and whose section is not archived - so an archived lesson/section can no longer
     * inflate the progress denominator (which made 100% unreachable). One grouped query per call.
     */
    @Query("SELECT l.courseId AS courseId, COUNT(l) AS total FROM Lesson l "
            + "WHERE l.courseId IN :courseIds AND l.archived = false "
            + "AND NOT EXISTS (SELECT s.id FROM Section s WHERE s.id = l.sectionId AND s.archived = true) "
            + "GROUP BY l.courseId")
    List<CourseLessonCount> countVisibleByCourseIdIn(@Param("courseIds") List<String> courseIds);

    interface CourseLessonCount {
        String getCourseId();
        long getTotal();
    }
}
