package com.classroom.modules.exam.repository;

import com.classroom.modules.exam.model.ExamAttempt;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamAttemptRepository extends JpaRepository<ExamAttempt, String> {
    List<ExamAttempt> findByExamIdAndUserIdOrderByStartedAtDesc(String examId, String userId);
    Optional<ExamAttempt> findFirstByExamIdAndUserIdAndStatusOrderByStartedAtDesc(String examId, String userId, String status);
    // R2-04: the student resume path (and the duplicate-attempt-conflict fallback) must never
    // resume a staff preview attempt - e.g. a former staff member demoted to student would
    // otherwise resume their own old preview and be rejected by the audience policy for it.
    Optional<ExamAttempt> findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc(String examId, String userId, String status);
    // R14-14: the learner's attempt limit counts only real (non-preview) attempts that were not
    // CANCELLED. A staff-cancelled attempt (started in error, invalidated, or cancelled when the
    // member was removed) must not consume one of the learner's allowed tries.
    @Query("SELECT COUNT(a) FROM ExamAttempt a WHERE a.examId = :examId AND a.userId = :userId "
            + "AND a.isPreview = false AND a.status <> 'CANCELLED'")
    long countAttemptsTowardLimit(@Param("examId") String examId, @Param("userId") String userId);

    interface LimitCount { String getExamId(); long getAttempts(); }
    @Query("SELECT a.examId AS examId, COUNT(a) AS attempts FROM ExamAttempt a "
            + "WHERE a.classId = :classId AND a.userId = :userId AND a.isPreview = false "
            + "AND a.status <> 'CANCELLED' GROUP BY a.examId")
    List<LimitCount> countAttemptsTowardLimitByClass(@Param("classId") String classId, @Param("userId") String userId);

    // R14-14: attempt_number stays unique per (exam, user) via uq_ea_exam_user_attempt, and
    // CANCELLED attempts keep their number, so the next number is derived from the highest number
    // ever used - not from countAttemptsTowardLimit, which now skips cancelled rows.
    @Query("SELECT COALESCE(MAX(a.attemptNumber), 0) FROM ExamAttempt a WHERE a.examId = :examId "
            + "AND a.userId = :userId AND a.isPreview = false")
    int findMaxAttemptNumber(@Param("examId") String examId, @Param("userId") String userId);

    // R14-15: the staff preview path resumes an existing IN_PROGRESS preview attempt instead of
    // creating a second one for the same user and exam.
    Optional<ExamAttempt> findFirstByExamIdAndUserIdAndStatusAndIsPreviewTrueOrderByStartedAtDesc(String examId, String userId, String status);
    List<ExamAttempt> findByExamIdOrderByScoreDesc(String examId);
    List<ExamAttempt> findByClassIdAndUserIdAndStatus(String classId, String userId, String status);

    /**
     * R19-01(c): locking (current-read) variant for callers that then CHANGE the attempts' status. A plain
     * read followed by an unconditional save let a member removal overwrite an attempt the learner had just
     * submitted (the stale IN_PROGRESS copy was written back as CANCELLED); this reads the latest committed
     * status and holds the row lock like submitAttempt/cancelAttempt do.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ExamAttempt a WHERE a.classId = :classId AND a.userId = :userId AND a.status = :status")
    List<ExamAttempt> findByClassIdAndUserIdAndStatusForUpdate(@Param("classId") String classId,
                                                               @Param("userId") String userId,
                                                               @Param("status") String status);

    List<ExamAttempt> findByClassIdAndUserIdAndStatusAndIsPreviewFalse(String classId, String userId, String status);
    List<ExamAttempt> findByStatusAndIsPreviewFalse(String status);
    List<ExamAttempt> findByClassIdAndStatusInAndIsPreviewFalseOrderBySubmittedAtAsc(String classId, List<String> statuses);
    // R13-07: paged, most-recent-first PUBLISHED results for one exam, backing StudioGrading's
    // "Kết quả đã công bố" section (score-correction entry point).
    List<ExamAttempt> findByExamIdAndStatusAndIsPreviewFalseOrderBySubmittedAtDesc(String examId, String status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ExamAttempt a WHERE a.id = :id")
    Optional<ExamAttempt> findByIdForUpdate(@Param("id") String id);

    @Query("SELECT a FROM ExamAttempt a WHERE a.examId = :examId AND a.userId = :userId AND a.status = 'PUBLISHED' AND a.isPreview = false ORDER BY a.score DESC")
    List<ExamAttempt> findPublishedAttemptsOrderByScoreDesc(@Param("examId") String examId, @Param("userId") String userId);

    /**
     * Locking read of every published, non-preview attempt of one learner in one class.
     *
     * <p>Leaderboard recalculation must observe the committed results of any concurrently
     * publishing transaction for the same learner. A plain (snapshot) read would miss an attempt
     * that another transaction has written but not yet committed, so the last writer could persist
     * an incomplete total. Taking a pessimistic write lock over this predicate serialises
     * recalculation per (classId, userId) and forces the second transaction to re-read the latest
     * committed rows.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ExamAttempt a WHERE a.classId = :classId AND a.userId = :userId AND a.status = 'PUBLISHED' AND a.isPreview = false")
    List<ExamAttempt> lockPublishedAttemptsForRecalculation(@Param("classId") String classId, @Param("userId") String userId);

    @Query("SELECT a FROM ExamAttempt a WHERE a.classId = :classId AND a.status = 'PUBLISHED' AND a.isPreview = false")
    List<ExamAttempt> findAllPublishedAttemptsByClass(@Param("classId") String classId);

    /**
     * R20-06: takes the per-(exam, user) start lock. `INSERT ... ON DUPLICATE KEY UPDATE` on the one-row-per-pair table
     * {@code exam_user_locks} (V33) acquires an exclusive record lock whether the row is new or already exists, and holds it
     * until the calling transaction ends. Everything that decides "may this learner start / resume an attempt of this exam"
     * runs under it, so it is exact per learner but never blocks other learners.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO exam_user_locks (exam_id, user_id) VALUES (:examId, :userId) "
            + "ON DUPLICATE KEY UPDATE user_id = user_id", nativeQuery = true)
    int lockUserScope(@Param("examId") String examId, @Param("userId") String userId);
}
