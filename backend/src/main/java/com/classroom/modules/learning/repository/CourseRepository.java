package com.classroom.modules.learning.repository;

import com.classroom.modules.learning.model.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.util.List;

@Repository
public interface CourseRepository extends JpaRepository<Course, String> {
    /**
     * R18-04: ordered by position, then creation time, then id. Positions are not unique (every course
     * created before R18-04 has position 0, and two concurrent creates can pick the same value), so
     * position alone left ties in arbitrary database order and the course list could reshuffle between
     * requests. The explicit query keeps the historical method name so callers and mocks are unchanged.
     */
    @Query("select c from Course c where c.classId = :classId order by c.position asc, c.createdAt asc, c.id asc")
    List<Course> findByClassIdOrderByPositionAsc(@Param("classId") String classId);

    /** Same deterministic tie-break as {@link #findByClassIdOrderByPositionAsc}. */
    @Query("select c from Course c where c.classId = :classId and c.status = :status order by c.position asc, c.createdAt asc, c.id asc")
    List<Course> findByClassIdAndStatusOrderByPositionAsc(@Param("classId") String classId, @Param("status") String status);

    /** R18-04: highest position in the class, or -1 when it has no course yet (a new course goes at max + 1). */
    @Query("select coalesce(max(c.position), -1) from Course c where c.classId = :classId")
    int findMaxPositionByClassId(@Param("classId") String classId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Course c where c.id = :id")
    java.util.Optional<Course> findByIdForUpdate(@Param("id") String id);

    /**
     * D-30: PUBLISHED courses of the classes {@code userId} currently belongs to - the owner, or a member whose row is ACTIVE and not lapsed
     * (staff are members too; D-19 {@code ClassMemberRepository#ACTIVE_AT}). A SUSPENDED class counts for its owner only (D-29). This is the
     * candidate set of "my courses": the caller still has to pass {@code LearningPolicy#canLearn} (purchase-required entitlement).
     */
    @Query("SELECT c FROM Course c, Classroom k WHERE k.id = c.classId AND UPPER(c.status) = 'PUBLISHED' AND (k.ownerId = :userId"
            + " OR (UPPER(k.status) <> 'SUSPENDED' AND EXISTS (SELECT 1 FROM ClassMember m WHERE m.classId = k.id AND m.userId = :userId"
            + " AND m.state = 'ACTIVE' AND (m.accessExpiresAt IS NULL OR m.accessExpiresAt > :now))))"
            + " ORDER BY LOWER(k.title) ASC, k.id ASC, c.position ASC, c.createdAt ASC, c.id ASC")
    List<Course> findLearnerCandidates(@Param("userId") String userId, @Param("now") java.time.Instant now);
}
