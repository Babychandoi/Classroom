package com.classroom.modules.community.repository;

import com.classroom.modules.community.model.Comment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<Comment, String> {
    List<Comment> findByPostIdOrderByCreatedAtAsc(String postId);
    long countByPostId(String postId);

    /** Batched comment counts for a page of posts (R3-08): one query instead of one per post. */
    @Query("select c.postId as postId, count(c) as total from Comment c where c.postId in :postIds group by c.postId")
    List<PostCommentCount> countByPostIdIn(@Param("postIds") Collection<String> postIds);

    interface PostCommentCount {
        String getPostId();
        long getTotal();
    }

    /**
     * R20-03: the latest {@code limit} comments of EACH post of a page, in one query (a window function over the page's post ids,
     * served by {@code ix_comments_post_created}), returned oldest-first within a post. The feed used to load every comment of
     * every post - one query per post - and then one user lookup per comment.
     */
    @Query(value = """
            SELECT c.id, c.post_id, c.author_id, c.content, c.created_at
            FROM (SELECT id, post_id, author_id, content, created_at,
                         ROW_NUMBER() OVER (PARTITION BY post_id ORDER BY created_at DESC, id DESC) AS rn
                  FROM comments WHERE post_id IN (:postIds)) c
            WHERE c.rn <= :limit
            ORDER BY c.post_id ASC, c.created_at ASC, c.id ASC
            """, nativeQuery = true)
    List<Comment> findLatestByPostIds(@Param("postIds") Collection<String> postIds, @Param("limit") int limit);

    /** R20-03: the page of comments immediately older than a given one (`before` paging of "Xem thêm bình luận"), newest first. */
    @Query("select c from Comment c where c.postId = :postId and (c.createdAt < :createdAt "
            + "or (c.createdAt = :createdAt and c.id < :id)) order by c.createdAt desc, c.id desc")
    List<Comment> findOlderThan(@Param("postId") String postId, @Param("createdAt") java.time.Instant createdAt,
                                @Param("id") String id, Pageable pageable);
}
