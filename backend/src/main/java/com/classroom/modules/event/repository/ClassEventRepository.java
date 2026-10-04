package com.classroom.modules.event.repository;

import com.classroom.modules.event.model.ClassEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClassEventRepository extends JpaRepository<ClassEvent, String> {

    /** D-27: the event row FOR UPDATE - every registration / unregistration / capacity change serialises on it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ClassEvent e WHERE e.id = :id")
    Optional<ClassEvent> findByIdForUpdate(@Param("id") String id);

    /** Upcoming = not ended yet (cancelled ones included, the UI shows a badge), soonest first. */
    @Query("SELECT e FROM ClassEvent e WHERE e.classId = :classId AND e.endsAt >= :now ORDER BY e.startsAt ASC, e.id ASC")
    List<ClassEvent> findUpcomingByClass(@Param("classId") String classId, @Param("now") Instant now, Pageable pageable);

    @Query("SELECT e FROM ClassEvent e WHERE e.classId = :classId AND e.endsAt < :now ORDER BY e.startsAt DESC, e.id DESC")
    List<ClassEvent> findPastByClass(@Param("classId") String classId, @Param("now") Instant now, Pageable pageable);

    @Query("SELECT e FROM ClassEvent e WHERE e.classId = :classId ORDER BY e.startsAt DESC, e.id DESC")
    List<ClassEvent> findAllByClass(@Param("classId") String classId, Pageable pageable);

    /**
     * D-27: the cross-class rail - SCHEDULED, not ended, of an ACTIVE and PUBLIC class only (the same "open to everyone" rule as
     * {@code ClassroomRepository#findPubliclyVisible}), soonest first.
     */
    @Query("SELECT e FROM ClassEvent e, Classroom c WHERE c.id = e.classId"
            + " AND UPPER(c.status) = 'ACTIVE' AND UPPER(c.visibility) <> 'PRIVATE'"
            + " AND e.status = 'SCHEDULED' AND e.endsAt >= :now ORDER BY e.startsAt ASC, e.id ASC")
    List<ClassEvent> findUpcomingInPublicClasses(@Param("now") Instant now, Pageable pageable);

    /** D-27: {@code upcomingEventCount} of a page of class cards, one grouped query; rows are {@code [classId, count]}. */
    @Query("SELECT e.classId, COUNT(e) FROM ClassEvent e WHERE e.classId IN :classIds AND e.status = 'SCHEDULED' AND e.endsAt >= :now"
            + " GROUP BY e.classId")
    List<Object[]> countUpcomingByClassIds(@Param("classIds") Collection<String> classIds, @Param("now") Instant now);

    @Query("SELECT COUNT(e) FROM ClassEvent e WHERE e.classId = :classId AND e.status = 'SCHEDULED' AND e.endsAt >= :now")
    long countUpcomingByClassId(@Param("classId") String classId, @Param("now") Instant now);

    boolean existsByClassId(String classId);
}
