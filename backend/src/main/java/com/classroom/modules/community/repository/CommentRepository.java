package com.classroom.modules.community.repository;

import com.classroom.modules.community.model.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<Comment, String> {
    List<Comment> findByPostIdOrderByCreatedAtAsc(String postId);
    long countByPostId(String postId);
}
