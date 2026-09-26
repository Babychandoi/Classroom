package com.classroom.modules.exam.repository;

import com.classroom.modules.exam.model.ExamAttempt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamAttemptRepository extends JpaRepository<ExamAttempt, String> {
    List<ExamAttempt> findByExamIdAndUserIdOrderByStartedAtDesc(String examId, String userId);
    Optional<ExamAttempt> findFirstByExamIdAndUserIdAndStatusOrderByStartedAtDesc(String examId, String userId, String status);
    long countByExamIdAndUserIdAndIsPreviewFalse(String examId, String userId);
    List<ExamAttempt> findByExamIdOrderByScoreDesc(String examId);
    List<ExamAttempt> findByClassIdAndUserIdAndStatus(String classId, String userId, String status);
    List<ExamAttempt> findByClassIdAndUserIdAndStatusAndIsPreviewFalse(String classId, String userId, String status);
    List<ExamAttempt> findByStatusAndIsPreviewFalse(String status);
    List<ExamAttempt> findByClassIdAndStatusInAndIsPreviewFalseOrderBySubmittedAtAsc(String classId, List<String> statuses);

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
}
