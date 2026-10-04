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
     * D-29: a SUSPENDED class is listed for its owner only - staff and members lose it until the platform admin restores it.
     */
    @Query("SELECT c FROM Classroom c WHERE (UPPER(c.status) = 'ACTIVE' AND UPPER(c.visibility) <> 'PRIVATE') OR c.ownerId = :userId"
            + " OR (UPPER(c.status) <> 'SUSPENDED' AND (EXISTS (SELECT 1 FROM StaffAssignment s WHERE s.classId = c.id AND s.userId = :userId AND UPPER(s.status) = 'ACTIVE')"
            + " OR EXISTS (SELECT 1 FROM ClassMember m WHERE m.classId = c.id AND m.userId = :userId AND UPPER(m.state) IN ('ACTIVE', 'EXPIRED'))))")
    List<Classroom> findVisibleToUser(@Param("userId") String userId, Pageable pageable);

    /**
     * D-27: {@link #findPubliclyVisible} narrowed by a search - {@code pattern} is a lower-cased LIKE pattern matched against the title or
     * the description ("%" = no search). Order comes from the pageable (newest first).
     */
    @Query("SELECT c FROM Classroom c WHERE UPPER(c.status) = 'ACTIVE' AND UPPER(c.visibility) <> 'PRIVATE'"
            + " AND (:pattern = '%' OR LOWER(c.title) LIKE :pattern OR LOWER(c.description) LIKE :pattern)"
            + " AND (:category = '' OR c.category = :category)")
    List<Classroom> searchPubliclyVisible(@Param("pattern") String pattern, @Param("category") String category, Pageable pageable);

    /** D-27: {@link #findVisibleToUser} narrowed by a search (see {@link #searchPubliclyVisible}). */
    @Query("SELECT c FROM Classroom c WHERE ((UPPER(c.status) = 'ACTIVE' AND UPPER(c.visibility) <> 'PRIVATE') OR c.ownerId = :userId"
            + " OR (UPPER(c.status) <> 'SUSPENDED' AND (EXISTS (SELECT 1 FROM StaffAssignment s WHERE s.classId = c.id AND s.userId = :userId AND UPPER(s.status) = 'ACTIVE')"
            + " OR EXISTS (SELECT 1 FROM ClassMember m WHERE m.classId = c.id AND m.userId = :userId AND UPPER(m.state) IN ('ACTIVE', 'EXPIRED')))))"
            + " AND (:pattern = '%' OR LOWER(c.title) LIKE :pattern OR LOWER(c.description) LIKE :pattern)"
            + " AND (:category = '' OR c.category = :category)")
    List<Classroom> searchVisibleToUser(@Param("userId") String userId, @Param("pattern") String pattern, @Param("category") String category,
                                        Pageable pageable);

    /**
     * D-27: {@code sort=popular} for a guest - the publicly visible classes ordered by their CURRENT active member count (the same rule as
     * {@code ClassMemberRepository#ACTIVE_AT}), newest first on a tie. Native SQL because the order is a correlated count; pass an
     * unsorted pageable (only offset / limit are applied).
     */
    @Query(nativeQuery = true, value = "SELECT c.* FROM classrooms c WHERE UPPER(c.status) = 'ACTIVE' AND UPPER(c.visibility) <> 'PRIVATE'"
            + " AND (:pattern = '%' OR LOWER(c.title) LIKE :pattern OR LOWER(c.description) LIKE :pattern)"
            + " AND (:category = '' OR c.category = :category)"
            + " ORDER BY (SELECT COUNT(*) FROM class_members m WHERE m.class_id = c.id AND m.state = 'ACTIVE'"
            + " AND (m.access_expires_at IS NULL OR m.access_expires_at > :now)) DESC, c.created_at DESC, c.id ASC")
    List<Classroom> searchPubliclyVisibleByPopularity(@Param("pattern") String pattern, @Param("category") String category,
                                                      @Param("now") java.time.Instant now, Pageable pageable);

    /** D-27: {@code sort=popular} for a signed-in user (visibility as in {@link #findVisibleToUser}). */
    @Query(nativeQuery = true, value = "SELECT c.* FROM classrooms c WHERE ((UPPER(c.status) = 'ACTIVE' AND UPPER(c.visibility) <> 'PRIVATE') OR c.owner_id = :userId"
            + " OR (UPPER(c.status) <> 'SUSPENDED' AND (EXISTS (SELECT 1 FROM staff_assignments s WHERE s.class_id = c.id AND s.user_id = :userId AND UPPER(s.status) = 'ACTIVE')"
            + " OR EXISTS (SELECT 1 FROM class_members m2 WHERE m2.class_id = c.id AND m2.user_id = :userId AND UPPER(m2.state) IN ('ACTIVE', 'EXPIRED')))))"
            + " AND (:pattern = '%' OR LOWER(c.title) LIKE :pattern OR LOWER(c.description) LIKE :pattern)"
            + " AND (:category = '' OR c.category = :category)"
            + " ORDER BY (SELECT COUNT(*) FROM class_members m WHERE m.class_id = c.id AND m.state = 'ACTIVE'"
            + " AND (m.access_expires_at IS NULL OR m.access_expires_at > :now)) DESC, c.created_at DESC, c.id ASC")
    List<Classroom> searchVisibleToUserByPopularity(@Param("userId") String userId, @Param("pattern") String pattern,
                                                    @Param("category") String category,
                                                    @Param("now") java.time.Instant now, Pageable pageable);
}
