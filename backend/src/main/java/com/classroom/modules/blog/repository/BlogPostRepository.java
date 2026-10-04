package com.classroom.modules.blog.repository;

import com.classroom.modules.blog.model.BlogPost;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Repository
public interface BlogPostRepository extends JpaRepository<BlogPost, String> {

    /**
     * D-27: the public list - PUBLISHED posts of one class, newest {@code publishedAt} first, keyset paged on (publishedAt, id) like the
     * feed (R5-01): rows strictly after the cursor, explicit OR clauses instead of a tuple comparison. {@code category} '' = any.
     * Served by {@code ix_blog_posts_class_published}.
     */
    @Query("""
            SELECT p FROM BlogPost p
            WHERE p.classId = :classId AND p.status = 'PUBLISHED'
              AND (:category = '' OR p.category = :category)
              AND (p.publishedAt < :cursorAt OR (p.publishedAt = :cursorAt AND p.id < :cursorId))
            ORDER BY p.publishedAt DESC, p.id DESC
            """)
    List<BlogPost> findPublishedPage(@Param("classId") String classId,
                                     @Param("category") String category,
                                     @Param("cursorAt") Instant cursorAt,
                                     @Param("cursorId") String cursorId,
                                     Pageable pageable);

    /**
     * D-27: the editors' list ({@code status=DRAFT|ALL}): drafts have no {@code publishedAt}, so the order key is
     * {@code COALESCE(publishedAt, createdAt)} - both are immutable once set, so the keyset stays stable.
     */
    @Query("""
            SELECT p FROM BlogPost p
            WHERE p.classId = :classId AND p.status IN :statuses
              AND (:category = '' OR p.category = :category)
              AND (COALESCE(p.publishedAt, p.createdAt) < :cursorAt
                   OR (COALESCE(p.publishedAt, p.createdAt) = :cursorAt AND p.id < :cursorId))
            ORDER BY COALESCE(p.publishedAt, p.createdAt) DESC, p.id DESC
            """)
    List<BlogPost> findManagedPage(@Param("classId") String classId,
                                   @Param("statuses") Collection<String> statuses,
                                   @Param("category") String category,
                                   @Param("cursorAt") Instant cursorAt,
                                   @Param("cursorId") String cursorId,
                                   Pageable pageable);

    @Query("""
            SELECT DISTINCT p.category FROM BlogPost p
            WHERE p.classId = :classId AND p.status = 'PUBLISHED' AND p.category IS NOT NULL AND p.category <> ''
            ORDER BY p.category
            """)
    List<String> findPublishedCategories(@Param("classId") String classId);

    boolean existsByClassId(String classId);
}
