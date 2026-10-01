package com.classroom.modules.exam.repository;

import com.classroom.modules.exam.model.Exam;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamRepository extends JpaRepository<Exam, String> {
    List<Exam> findByClassIdOrderByCreatedAtDesc(String classId);
    List<Exam> findByClassIdAndStatusOrderByCreatedAtDesc(String classId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Exam e WHERE e.id = :id")
    Optional<Exam> findByIdForUpdate(@Param("id") String id);

    /**
     * R20-06: shared (`FOR SHARE`) read used by "start attempt". Many learners can hold it at once, so simultaneous starts
     * no longer queue behind each other, while an author's exclusive lock (close / edit / publish, {@link #findByIdForUpdate})
     * still waits for in-flight starts and is seen by the next one.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT e FROM Exam e WHERE e.id = :id")
    Optional<Exam> findByIdForShare(@Param("id") String id);
}
