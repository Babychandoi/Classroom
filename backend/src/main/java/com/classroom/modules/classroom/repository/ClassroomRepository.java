package com.classroom.modules.classroom.repository;

import com.classroom.modules.classroom.model.Classroom;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClassroomRepository extends JpaRepository<Classroom, String> {
    Optional<Classroom> findBySlug(String slug);
    List<Classroom> findByOwnerId(String ownerId);
    boolean existsBySlug(String slug);

    /**
     * D-19: the class row FOR UPDATE, taken first by the paths that change how a class is accessed (PUT /classes/{id}/access) so two
     * concurrent changes - and a change racing a conversion clean-up - serialise on it. Nothing locks a product and then this row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Classroom c where c.id = :id")
    Optional<Classroom> findByIdForUpdate(@Param("id") String id);

    /**
     * R16-08 / D-19: one page of the classes an anonymous visitor may see: ACTIVE and PUBLIC only. Visibility is part of the query - not
     * filtered after paging - so every page is full and a short page really means "last page". A PRIVATE class never appears here.
     */
    @Query("SELECT c FROM Classroom c WHERE UPPER(c.status) = 'ACTIVE' AND UPPER(c.visibility) <> 'PRIVATE'")
    List<Classroom> findPubliclyVisible(Pageable pageable);

    /**
     * R16-08 / D-19: one page of the classes {@code userId} may see: every ACTIVE PUBLIC class plus any other one (archived, or PRIVATE) they
     * own, staff (ACTIVE assignment) or belong to (ACTIVE or EXPIRED membership - a lapsed paid member still sees the class, to renew) - the
     * same rule as {@code AccessPolicy.isClassVisibleToUser}, evaluated in SQL. The two are pinned together by AccessPolicySqlParityTest.
     */
    @Query("SELECT c FROM Classroom c WHERE (UPPER(c.status) = 'ACTIVE' AND UPPER(c.visibility) <> 'PRIVATE') OR c.ownerId = :userId"
            + " OR EXISTS (SELECT 1 FROM StaffAssignment s WHERE s.classId = c.id AND s.userId = :userId AND UPPER(s.status) = 'ACTIVE')"
            + " OR EXISTS (SELECT 1 FROM ClassMember m WHERE m.classId = c.id AND m.userId = :userId AND UPPER(m.state) IN ('ACTIVE', 'EXPIRED'))")
    List<Classroom> findVisibleToUser(@Param("userId") String userId, Pageable pageable);
}
