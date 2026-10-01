package com.classroom.modules.community.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.community.dto.CommentDto;
import com.classroom.modules.community.dto.CommentPageDto;
import com.classroom.modules.community.dto.FeedPageDto;
import com.classroom.modules.community.dto.PostDto;
import com.classroom.modules.community.dto.CreatePostRequest;
import com.classroom.modules.community.dto.UpdatePostRequest;
import com.classroom.modules.community.dto.UpdateCommentRequest;
import com.classroom.modules.community.model.Post;
import com.classroom.modules.community.service.FeedService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    /**
     * R5-01: keyset (cursor) paged listing. Always returns the {@link FeedPageDto} envelope
     * ({@code posts} + {@code nextCursor} + {@code hasNext}) — the only consumer of this endpoint,
     * FeedTab.tsx, already reads that shape, and the legacy unbounded-array path (offset paging,
     * no cap) has been removed. {@code size} defaults to 10 and is capped at 100; {@code cursor}
     * is the opaque token from a previous page's {@code nextCursor}, omitted for the first page.
     * A legacy {@code page} param, if still sent by a stale client, is accepted and ignored rather
     * than rejected.
     */
    @GetMapping("/classes/{classId}/posts")
    public ResponseEntity<ApiResponse<FeedPageDto>> getFeedPosts(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer page) {
        String userId = (principal != null) ? principal.getId() : null;
        FeedPageDto pageDto = feedService.getFeedPage(classId, userId, cursor, size);
        return ResponseEntity.ok(ApiResponse.ok(pageDto));
    }

    @PostMapping("/classes/{classId}/posts")
    public ResponseEntity<ApiResponse<PostDto>> createPost(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal,
            @RequestBody CreatePostRequest request) {
        PostDto created = feedService.createPost(classId, principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.ok(created));
    }

    /**
     * R20-03: older comments of a post, `size` (default 20, max 50) at a time, immediately before the comment `before` (the oldest one
     * the client already shows; omit for the newest page). The feed itself embeds only the latest few comments per post.
     */
    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<ApiResponse<CommentPageDto>> listComments(
            @PathVariable String postId,
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) String before,
            @RequestParam(required = false) Integer size) {
        String userId = (principal != null) ? principal.getId() : null;
        return ResponseEntity.ok(ApiResponse.ok(feedService.listComments(postId, userId, before, size)));
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

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<ApiResponse<Map<String, String>>> deleteComment(
            @PathVariable String commentId,
            @CurrentUser UserPrincipal principal) {
        feedService.deleteComment(commentId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "Đã xóa bình luận thành công")));
    }
}
