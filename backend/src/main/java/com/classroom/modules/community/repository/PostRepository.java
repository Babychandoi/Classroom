package com.classroom.modules.community.repository;

import com.classroom.modules.community.model.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PostRepository extends JpaRepository<Post, String> {
    List<Post> findByClassIdAndStatusOrderByPinnedDescCreatedAtDesc(String classId, String status);
}
