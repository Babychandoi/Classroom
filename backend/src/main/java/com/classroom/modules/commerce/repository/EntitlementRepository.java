package com.classroom.modules.commerce.repository;

import com.classroom.modules.commerce.model.Entitlement;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Repository
public interface EntitlementRepository extends JpaRepository<Entitlement, String> {

    /** D-19: JPQL fragment excluding entitlements of a CLASS_ACCESS product (membership, not PRO); the entitlement alias must be {@code e}. */
    String NOT_CLASS_ACCESS = "NOT EXISTS (SELECT 1 FROM Product p WHERE p.id = e.productId AND p.kind = 'CLASS_ACCESS')";

    List<Entitlement> findByUserIdAndClassId(String userId, String classId);

    @Query("SELECT e FROM Entitlement e WHERE e.userId = :userId AND e.classId = :classId AND e.state = 'ACTIVE' AND e.startsAt <= :now AND e.expiresAt > :now")
    List<Entitlement> findActiveEntitlements(
            @Param("userId") String userId,
            @Param("classId") String classId,
            @Param("now") Instant now
    );

    /**
     * PRO = an active entitlement in the class. D-19: the class-access product (kind CLASS_ACCESS) sells MEMBERSHIP, not PRO, so its
     * entitlements never count here (nor in the two batch forms below).
     */
    @Query("SELECT COUNT(e) > 0 FROM Entitlement e WHERE e.userId = :userId AND e.classId = :classId AND e.state = 'ACTIVE' AND e.startsAt <= :now AND e.expiresAt > :now AND " + NOT_CLASS_ACCESS)
    boolean hasActiveProEntitlement(
            @Param("userId") String userId,
            @Param("classId") String classId,
            @Param("now") Instant now
    );

    @Query("SELECT COUNT(e) > 0 FROM Entitlement e WHERE e.userId = :userId AND e.classId = :classId AND e.targetCourseId = :courseId AND e.productId = :productId AND e.state = 'ACTIVE' AND e.startsAt <= :now AND e.expiresAt > :now")
    boolean hasCourseAccess(
            @Param("userId") String userId,
            @Param("classId") String classId,
            @Param("courseId") String courseId,
            @Param("productId") String productId,
            @Param("now") Instant now
    );

    @Query("SELECT COUNT(e) > 0 FROM Entitlement e WHERE e.userId = :userId AND e.classId = :classId AND e.productId = :productId AND e.state = 'ACTIVE' AND e.startsAt <= :now AND e.expiresAt > :now")
    boolean hasProductAccess(
            @Param("userId") String userId,
            @Param("classId") String classId,
            @Param("productId") String productId,
            @Param("now") Instant now
    );

    @Query("SELECT e FROM Entitlement e WHERE e.userId = :userId AND e.classId = :classId AND e.productId = :productId AND e.state = 'ACTIVE' AND e.expiresAt > :now ORDER BY e.expiresAt DESC")
    List<Entitlement> findLatestActiveByProduct(
            @Param("userId") String userId,
            @Param("classId") String classId,
            @Param("productId") String productId,
            @Param("now") Instant now
    );

    @Query("SELECT e FROM Entitlement e WHERE e.userId = :userId AND e.classId = :classId AND e.productId = :productId AND e.state = 'ACTIVE' AND e.expiresAt > :now ORDER BY e.startsAt ASC")
    List<Entitlement> findFutureActiveByProductAsc(
            @Param("userId") String userId,
            @Param("classId") String classId,
            @Param("productId") String productId,
            @Param("now") Instant now
    );

    List<Entitlement> findByOrderId(String orderId);

    /*
     * R19-01: LOCKING (current-read) variants of the stacking / reconcile queries above. MySQL runs
     * at REPEATABLE READ, where a plain SELECT reads from a snapshot fixed by the transaction's first
     * consistent read - so a webhook that had to WAIT for the product lock would still see the chain
     * as it was before the transaction it waited for committed (two parallel 30-day purchases both
     * saw "no active entitlement" and were granted the SAME period). A `FOR UPDATE` read always
     * returns the latest committed rows, regardless of isolation level or of what was read before, and
     * locks the chain rows. Callers take the order row, then the product rows (sorted), then use these.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Entitlement e WHERE e.userId = :userId AND e.classId = :classId AND e.productId = :productId AND e.state = 'ACTIVE' AND e.expiresAt > :now ORDER BY e.expiresAt DESC")
    List<Entitlement> findLatestActiveByProductForUpdate(
            @Param("userId") String userId,
            @Param("classId") String classId,
            @Param("productId") String productId,
            @Param("now") Instant now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Entitlement e WHERE e.userId = :userId AND e.classId = :classId AND e.productId = :productId AND e.state = 'ACTIVE' AND e.expiresAt > :now ORDER BY e.startsAt ASC")
    List<Entitlement> findFutureActiveByProductAscForUpdate(
            @Param("userId") String userId,
            @Param("classId") String classId,
            @Param("productId") String productId,
            @Param("now") Instant now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Entitlement e WHERE e.orderId = :orderId")
    List<Entitlement> findByOrderIdForUpdate(@Param("orderId") String orderId);

    /**
     * R16-08: of the given classes, the ones where the user holds an active entitlement (the same
     * condition as {@link #hasActiveProEntitlement}) - one query for a whole class listing.
     */
    @Query("SELECT DISTINCT e.classId FROM Entitlement e WHERE e.userId = :userId AND e.classId IN :classIds AND e.state = 'ACTIVE' AND e.startsAt <= :now AND e.expiresAt > :now AND " + NOT_CLASS_ACCESS)
    List<String> findClassIdsWithActiveEntitlement(
            @Param("userId") String userId,
            @Param("classIds") Collection<String> classIds,
            @Param("now") Instant now
    );

    /**
     * R20-03: of {@code userIds}, the ones holding an active entitlement in the class (the same condition as
     * {@link #hasActiveProEntitlement}) - one query for a whole member page instead of one per member.
     */
    @Query("SELECT DISTINCT e.userId FROM Entitlement e WHERE e.classId = :classId AND e.userId IN :userIds AND e.state = 'ACTIVE' AND e.startsAt <= :now AND e.expiresAt > :now AND " + NOT_CLASS_ACCESS)
    List<String> findUserIdsWithActiveEntitlement(
            @Param("classId") String classId,
            @Param("userIds") Collection<String> userIds,
            @Param("now") Instant now
    );
}
