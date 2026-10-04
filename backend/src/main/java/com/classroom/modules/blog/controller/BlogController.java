package com.classroom.modules.blog.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.blog.dto.BlogPostDto;
import com.classroom.modules.blog.dto.BlogPostPageDto;
import com.classroom.modules.blog.dto.CreateBlogPostRequest;
import com.classroom.modules.blog.dto.UpdateBlogPostRequest;
import com.classroom.modules.blog.service.BlogService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** D-27: the class blog. The three GETs are open to guests for PUBLIC classes (SecurityConfig); every write needs a BLOG grant. */
@RestController
@RequestMapping("/api/v1")
public class BlogController {

    private final BlogService blogService;

    public BlogController(BlogService blogService) {
        this.blogService = blogService;
    }

    private static String idOf(UserPrincipal principal) {
        return principal != null ? principal.getId() : null;
    }

    @GetMapping("/classes/{classId}/blog-posts")
    public ResponseEntity<ApiResponse<BlogPostPageDto>> list(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(ApiResponse.ok(blogService.list(classId, idOf(principal), category, status, cursor, size)));
    }

    @GetMapping("/classes/{classId}/blog-categories")
    public ResponseEntity<ApiResponse<List<String>>> categories(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(blogService.categories(classId, idOf(principal))));
    }

    @GetMapping("/blog-posts/{id}")
    public ResponseEntity<ApiResponse<BlogPostDto>> get(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(blogService.get(id, idOf(principal))));
    }

    @PostMapping("/classes/{classId}/blog-posts")
    public ResponseEntity<ApiResponse<BlogPostDto>> create(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody CreateBlogPostRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(blogService.create(classId, principal.getId(), request)));
    }

    @PutMapping("/blog-posts/{id}")
    public ResponseEntity<ApiResponse<BlogPostDto>> update(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody UpdateBlogPostRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(blogService.update(id, principal.getId(), request)));
    }

    @PostMapping("/blog-posts/{id}/publish")
    public ResponseEntity<ApiResponse<BlogPostDto>> publish(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(blogService.publish(id, principal.getId())));
    }

    @PostMapping("/blog-posts/{id}/unpublish")
    public ResponseEntity<ApiResponse<BlogPostDto>> unpublish(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(blogService.unpublish(id, principal.getId())));
    }

    @DeleteMapping("/blog-posts/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        blogService.delete(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
