package com.classroom.modules.classroom.repository;

import com.classroom.modules.classroom.model.ClassMember;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Membership rows. D-19: "ACTIVE" is defined ONCE - stored state ACTIVE <em>and</em> {@code access_expires_at} null or in the future
 * ({@link ClassMember#isActiveAt}). Every read that means "current members" goes through {@link #ACTIVE_AT} (the SQL form of that rule), so
 * a member whose paid access has lapsed drops out of counts, boards and rosters immediately - not only after the sweeper flips the state.
 */
@Repository
public interface ClassMemberRepository extends JpaRepository<ClassMember, String> {

    /** SQL form of {@link ClassMember#isActiveAt}; bind {@code :now}. Keep every "active member" query on this fragment. */
    String ACTIVE_AT = "m.state = 'ACTIVE' AND (m.accessExpiresAt IS NULL OR m.accessExpiresAt > :now)";

    Optional<ClassMember> findByClassIdAndUserId(String classId, String userId);
    List<ClassMember> findByClassId(String classId);
    List<ClassMember> findByUserId(String userId);
    boolean existsByClassIdAndUserId(String classId, String userId);
    long countByClassId(String classId);

    /**
     * D-19: the member row FOR UPDATE. Settle / refund / invite-join / sweeper paths take it last (after the order, product and entitlement
     * locks, or the invite lock) so they agree on the order in which rows are locked.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM ClassMember m WHERE m.classId = :classId AND m.userId = :userId")
    Optional<ClassMember> findByClassIdAndUserIdForUpdate(@Param("classId") String classId, @Param("userId") String userId);

    /** Exact-state count (no expiry logic). {@link #countByClassIdAndState} is the one that understands "ACTIVE". */
    @Query("SELECT COUNT(m) FROM ClassMember m WHERE m.classId = :classId AND m.state = :state")
    long countRawByClassIdAndState(@Param("classId") String classId, @Param("state") String state);

    @Query("SELECT COUNT(m) FROM ClassMember m WHERE m.classId = :classId AND " + ACTIVE_AT)
    long countActiveAt(@Param("classId") String classId, @Param("now") Instant now);

    /**
     * R15-04 / D-19: number of members in a given lifecycle state. Public read models (class card, about page) show ACTIVE members only, so
     * a REMOVED/BLOCKED/EXPIRED member never inflates the headline count. Asking for "ACTIVE" means <em>currently active</em>: a member
     * whose paid access has lapsed but who has not been swept yet is not counted.
     */
    default long countByClassIdAndState(String classId, String state) {
        if ("ACTIVE".equalsIgnoreCase(state)) {
            return countActiveAt(classId, Instant.now());
        }
        return countRawByClassIdAndState(classId, state);
    }

    /**
     * R16-08: the current user's membership rows (any state) across a page of classes - one query for
     * the whole class listing instead of one per card.
     */
    List<ClassMember> findByUserIdAndClassIdIn(String userId, Collection<String> classIds);

    @Query("SELECT m.classId, COUNT(m) FROM ClassMember m WHERE m.classId IN :classIds AND " + ACTIVE_AT + " GROUP BY m.classId")
    List<Object[]> countActiveByClassIdsAt(@Param("classIds") Collection<String> classIds, @Param("now") Instant now);

    /**
     * R16-08 / D-19: currently-active member headcount per class for a page of classes in one grouped query; each row is
     * {@code [classId, count]}. Classes without an active member are simply absent.
     */
    default List<Object[]> countActiveByClassIds(Collection<String> classIds) {
        return countActiveByClassIdsAt(classIds, Instant.now());
    }

    @Query("SELECT m.userId FROM ClassMember m WHERE m.classId = :classId AND " + ACTIVE_AT)
    List<String> findActiveUserIdsByClassIdAt(@Param("classId") String classId, @Param("now") Instant now);

    /**
     * R14-11 / D-19: ids of the users who currently belong to the class (ACTIVE and not lapsed, owner included as it has its own ACTIVE
     * row). One query, so read models such as the leaderboards can drop REMOVED/BLOCKED/EXPIRED members without a per-row membership lookup.
     */
    default List<String> findActiveUserIdsByClassId(String classId) {
        return findActiveUserIdsByClassIdAt(classId, Instant.now());
    }

    /** R20-03: of {@code userIds}, the ones with a roster row (any state) in the class - one query for a whole listing. */
    @Query("SELECT m.userId FROM ClassMember m WHERE m.classId = :classId AND m.userId IN :userIds")
    List<String> findUserIdsByClassIdAndUserIdIn(@Param("classId") String classId, @Param("userIds") Collection<String> userIds);

    /**
     * D-19 sweeper: the next batch of ACTIVE rows whose paid access has lapsed, locked FOR UPDATE (READ_COMMITTED current read: a settle that
     * extended a row a moment ago is seen and the row no longer matches). Served by {@code ix_class_members_expiry_sweep}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM ClassMember m WHERE m.state = 'ACTIVE' AND m.accessExpiresAt IS NOT NULL AND m.accessExpiresAt <= :now ORDER BY m.accessExpiresAt ASC, m.id ASC")
    List<ClassMember> findLapsedActiveForUpdate(@Param("now") Instant now, Pageable pageable);

    /** D-19: PAID -> FREE. Members keep what they have: every ACTIVE row loses its expiry date. EXPIRED rows stay EXPIRED (they rejoin). */
    @Modifying
    @Query("UPDATE ClassMember m SET m.accessExpiresAt = NULL WHERE m.classId = :classId AND m.state = 'ACTIVE' AND m.accessExpiresAt IS NOT NULL")
    int clearAccessExpiryOfActiveMembers(@Param("classId") String classId);

    /**
     * R20-03 / D-19: one page of the Studio roster, filtered on the server. {@code state} / {@code role} are exact matches and
     * {@code pattern} a lower-cased SQL LIKE pattern applied to the member's name or e-mail; an empty {@code state} /
     * {@code role} means "any" and {@code pattern} is "%" for "no search". Newest members first. The state filter compares the
     * <em>effective</em> state: a stored ACTIVE whose paid access has lapsed counts as EXPIRED.
     */
    @Query(value = "SELECT m FROM ClassMember m, User u WHERE u.id = m.userId AND m.classId = :classId "
            + "AND (:state = '' OR (CASE WHEN m.state = 'ACTIVE' AND m.accessExpiresAt IS NOT NULL AND m.accessExpiresAt <= :now THEN 'EXPIRED' ELSE m.state END) = :state) "
            + "AND (:role = '' OR m.role = :role) "
            + "AND (lower(u.fullName) LIKE :pattern OR lower(u.email) LIKE :pattern) "
            + "ORDER BY m.joinedAt DESC, m.id DESC",
            countQuery = "SELECT COUNT(m) FROM ClassMember m, User u WHERE u.id = m.userId AND m.classId = :classId "
            + "AND (:state = '' OR (CASE WHEN m.state = 'ACTIVE' AND m.accessExpiresAt IS NOT NULL AND m.accessExpiresAt <= :now THEN 'EXPIRED' ELSE m.state END) = :state) "
            + "AND (:role = '' OR m.role = :role) "
            + "AND (lower(u.fullName) LIKE :pattern OR lower(u.email) LIKE :pattern)")
    Page<ClassMember> searchStudioRosterAt(@Param("classId") String classId, @Param("state") String state,
                                           @Param("role") String role, @Param("pattern") String pattern,
                                           @Param("now") Instant now, Pageable pageable);

    default Page<ClassMember> searchStudioRoster(String classId, String state, String role, String pattern, Pageable pageable) {
        return searchStudioRosterAt(classId, state, role, pattern, Instant.now(), pageable);
    }
}
