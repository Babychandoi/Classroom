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
    List<Lesson> findByMediaAssetId(String mediaAssetId);
}
