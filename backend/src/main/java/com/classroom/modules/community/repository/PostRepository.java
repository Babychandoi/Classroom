package com.classroom.modules.community.repository;

import com.classroom.modules.community.model.Post;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface PostRepository extends JpaRepository<Post, String> {

    /** Cheap existence check used only by seed data bootstrap (avoids fetching rows). */
    boolean existsByClassIdAndStatus(String classId, String status);

    /**
     * R5-01/02/03/05: keyset (cursor) pagination. Orders by (pinned DESC, createdAt DESC, id DESC)
     * — id is a unique tie-breaker since createdAt has only second precision — and returns rows
     * strictly "after" the given cursor position in that ordering, expressed as explicit OR
     * clauses rather than a tuple comparison (portable across JPA providers). The very first batch
     * of a request passes {@code cursorPinned=true}, a max Instant and a high-sentinel id so every
     * row sorts "before" the cursor.
     *
     * <p>No COUNT query is issued: this returns a {@code List}, and callers request a limited
     * batch via {@link Pageable#ofSize(int)} (offset is always 0 — advancing happens by moving the
     * cursor, not by growing an OFFSET).</p>
     */
    @Query("""
            SELECT p FROM Post p
            WHERE p.classId = :classId AND p.status = :status
              AND (
                   p.pinned < :cursorPinned
                OR (p.pinned = :cursorPinned AND p.createdAt < :cursorCreatedAt)
                OR (p.pinned = :cursorPinned AND p.createdAt = :cursorCreatedAt AND p.id < :cursorId)
              )
            ORDER BY p.pinned DESC, p.createdAt DESC, p.id DESC
            """)
    List<Post> findKeysetBatch(@Param("classId") String classId,
                                @Param("status") String status,
                                @Param("cursorPinned") boolean cursorPinned,
                                @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                @Param("cursorId") String cursorId,
                                Pageable pageable);
}
