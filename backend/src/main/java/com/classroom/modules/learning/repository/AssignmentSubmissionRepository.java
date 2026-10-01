package com.classroom.modules.learning.repository;

import com.classroom.modules.learning.model.AssignmentSubmission;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface AssignmentSubmissionRepository extends JpaRepository<AssignmentSubmission, String> {
    List<AssignmentSubmission> findByLessonIdAndUserIdOrderByAttemptNumberDesc(String lessonId, String userId);
    List<AssignmentSubmission> findByLessonIdOrderBySubmittedAtAsc(String lessonId);
    long countByLessonIdAndUserId(String lessonId, String userId);

    /**
     * R14-04: any submission (graded or not) recorded against the lesson. assignment_submissions.lesson_id
     * is ON DELETE CASCADE, so a lesson/section/course delete must be refused while this is true.
     */
    boolean existsByLessonId(String lessonId);

    @Query("SELECT COALESCE(MAX(s.attemptNumber), 0) FROM AssignmentSubmission s WHERE s.lessonId = :lessonId AND s.userId = :userId")
    int findMaxAttemptNumber(@Param("lessonId") String lessonId, @Param("userId") String userId);

    /** Pessimistic row lock so two graders cannot read-modify-write the same submission. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AssignmentSubmission s WHERE s.id = :id")
    Optional<AssignmentSubmission> findByIdForUpdate(@Param("id") String id);

    /**
     * R12-02: the class-wide grading queue for an OWNER (unrestricted) - pending-only ('SUBMITTED';
     * 'GRADED' rows have nothing left for a grader to act on), ordered oldest-first, capped so a
     * large backlog cannot return an unbounded result set. Uses the (class_id, status, submitted_at)
     * index added by V26.
     */
    @Query("SELECT s FROM AssignmentSubmission s WHERE s.classId = :classId AND s.status = 'SUBMITTED' "
            + "ORDER BY s.submittedAt ASC")
    List<AssignmentSubmission> findPendingByClassId(@Param("classId") String classId, Pageable pageable);

    /**
     * R12-02: the class-wide grading queue for a course-scoped grader - same pending-only ordering,
     * additionally filtered to the specific courses the caller holds COURSE:GRADE on, in SQL rather
     * than a per-row canManage() check in Java. Still uses the (class_id, status, submitted_at)
     * index (course_id is filtered via the IN list on the already-narrowed class_id rows).
     */
    @Query("SELECT s FROM AssignmentSubmission s WHERE s.classId = :classId AND s.status = 'SUBMITTED' "
            + "AND s.courseId IN :courseIds ORDER BY s.submittedAt ASC")
    List<AssignmentSubmission> findPendingByClassIdAndCourseIdIn(
            @Param("classId") String classId, @Param("courseIds") List<String> courseIds, Pageable pageable);
}
