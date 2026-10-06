package com.classroom.modules.learning.repository;

import com.classroom.modules.learning.model.LessonAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LessonAttachmentRepository extends JpaRepository<LessonAttachment, String> {

    @Query("SELECT a FROM LessonAttachment a WHERE a.lessonId = :lessonId ORDER BY a.position ASC, a.createdAt ASC, a.id ASC")
    List<LessonAttachment> findByLesson(@Param("lessonId") String lessonId);

    /** One query for a whole page of lessons. */
    @Query("SELECT a FROM LessonAttachment a WHERE a.lessonId IN :lessonIds ORDER BY a.position ASC, a.createdAt ASC, a.id ASC")
    List<LessonAttachment> findByLessonIds(@Param("lessonIds") Collection<String> lessonIds);

    long countByLessonId(String lessonId);

    boolean existsByMediaAssetId(String mediaAssetId);

    Optional<LessonAttachment> findByIdAndLessonId(String id, String lessonId);

    /** Attachment counts per lesson of a course, rows are {@code [lessonId, count]}. */
    @Query("SELECT a.lessonId, COUNT(a) FROM LessonAttachment a WHERE a.lessonId IN :lessonIds GROUP BY a.lessonId")
    List<Object[]> countByLessonIds(@Param("lessonIds") Collection<String> lessonIds);
}
