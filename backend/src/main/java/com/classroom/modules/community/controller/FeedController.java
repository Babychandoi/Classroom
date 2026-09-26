package com.classroom.modules.community.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.community.dto.CommentDto;
import com.classroom.modules.community.dto.PostDto;
import com.classroom.modules.community.dto.CreatePostRequest;
import com.classroom.modules.community.dto.UpdatePostRequest;
import com.classroom.modules.community.dto.UpdateCommentRequest;
import com.classroom.modules.community.model.Post;
import com.classroom.modules.community.service.FeedService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping("/classes/{classId}/posts")
    public ResponseEntity<ApiResponse<List<PostDto>>> getFeedPosts(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal) {
        String userId = (principal != null) ? principal.getId() : null;
        List<PostDto> posts = feedService.getFeedPosts(classId, userId);
        return ResponseEntity.ok(ApiResponse.ok(posts));
    }

    @PostMapping("/classes/{classId}/posts")
    public ResponseEntity<ApiResponse<PostDto>> createPost(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal,
            @RequestBody CreatePostRequest request) {
        PostDto created = feedService.createPost(classId, principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.ok(created));
    }

    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<ApiResponse<CommentDto>> addComment(
            @PathVariable String postId,
            @CurrentUser UserPrincipal principal,
            @RequestBody Map<String, String> body) {
        String content = body.get("content");
        CommentDto comment = feedService.addComment(postId, principal.getId(), content);
        return ResponseEntity.ok(ApiResponse.ok(comment));
    }

    @PutMapping("/posts/{postId}")
    public ResponseEntity<ApiResponse<PostDto>> updatePost(
            @PathVariable String postId,
            @CurrentUser UserPrincipal principal,
            @RequestBody UpdatePostRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(feedService.updatePost(postId, principal.getId(), request)));
    }

    @PutMapping("/comments/{commentId}")
    public ResponseEntity<ApiResponse<CommentDto>> updateComment(
            @PathVariable String commentId,
            @CurrentUser UserPrincipal principal,
            @RequestBody UpdateCommentRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(feedService.updateComment(commentId, principal.getId(), request)));
    }

    @DeleteMapping("/posts/{postId}")
    public ResponseEntity<ApiResponse<Map<String, String>>> deletePost(
            @PathVariable String postId,
            @CurrentUser UserPrincipal principal) {
        feedService.deletePost(postId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "Đã xóa bài viết thành công")));
    }
}
