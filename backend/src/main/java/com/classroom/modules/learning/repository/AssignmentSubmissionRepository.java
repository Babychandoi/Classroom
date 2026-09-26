package com.classroom.modules.learning.repository;

import com.classroom.modules.learning.model.AssignmentSubmission;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface AssignmentSubmissionRepository extends JpaRepository<AssignmentSubmission, String> {
    List<AssignmentSubmission> findByLessonIdAndUserIdOrderByAttemptNumberDesc(String lessonId, String userId);
    List<AssignmentSubmission> findByLessonIdOrderBySubmittedAtAsc(String lessonId);
    List<AssignmentSubmission> findByClassIdOrderBySubmittedAtAsc(String classId);
    long countByLessonIdAndUserId(String lessonId, String userId);

    @Query("SELECT COALESCE(MAX(s.attemptNumber), 0) FROM AssignmentSubmission s WHERE s.lessonId = :lessonId AND s.userId = :userId")
    int findMaxAttemptNumber(@Param("lessonId") String lessonId, @Param("userId") String userId);

    /** Pessimistic row lock so two graders cannot read-modify-write the same submission. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AssignmentSubmission s WHERE s.id = :id")
    Optional<AssignmentSubmission> findByIdForUpdate(@Param("id") String id);
}
