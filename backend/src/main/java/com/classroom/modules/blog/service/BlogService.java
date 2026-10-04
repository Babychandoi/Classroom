package com.classroom.modules.blog.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.blog.dto.BlogPostDto;
import com.classroom.modules.blog.dto.BlogPostPageDto;
import com.classroom.modules.blog.dto.CreateBlogPostRequest;
import com.classroom.modules.blog.dto.UpdateBlogPostRequest;
import com.classroom.modules.blog.model.BlogPost;
import com.classroom.modules.blog.repository.BlogPostRepository;
import com.classroom.modules.classroom.dto.PersonSummaryDto;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.media.service.MediaService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * D-27: the class blog. Reads follow the class-visibility rule of {@link AccessPolicy#requireVisibleClass} (guests included for a PUBLIC
 * ACTIVE class, a hidden PRIVATE class is a 404); a MEMBERS post is listed for everybody who can see the class but its content is only
 * returned to active members, the owner and staff holding a BLOG grant ({@code locked=true} otherwise). Drafts exist only for BLOG editors.
 * Writes are gated on the BLOG module of the staff permissions (owner always passes).
 */
@Service
public class BlogService {

    public static final int DEFAULT_PAGE_SIZE = 12;
    public static final int MAX_PAGE_SIZE = 50;
    public static final String MEDIA_PURPOSE = "BLOG";
    static final String CLASS_NOT_FOUND = "Không tìm thấy lớp học";
    static final String POST_NOT_FOUND = "Không tìm thấy bài viết";
    /** Any BLOG grant makes the caller an editor who may see drafts and locked content. */
    private static final String[] EDITOR_ACTIONS = {"VIEW", "CREATE", "EDIT", "PUBLISH", "DELETE"};

    private final BlogPostRepository postRepository;
    private final AccessPolicy accessPolicy;
    private final UserRepository userRepository;
    private final MediaService mediaService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public BlogService(BlogPostRepository postRepository,
                       AccessPolicy accessPolicy,
                       UserRepository userRepository,
                       @Lazy MediaService mediaService,
                       AuditService auditService,
                       ObjectMapper objectMapper) {
        this.postRepository = postRepository;
        this.accessPolicy = accessPolicy;
        this.userRepository = userRepository;
        this.mediaService = mediaService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** What one caller may see in one class, resolved once per request. */
    private record Viewer(String userId, String classId, boolean member, boolean editor) {
        boolean canRead(BlogPost post) {
            return !post.isMembersOnly() || member || editor;
        }
    }

    private Viewer viewer(String classId, String userId) {
        boolean member = userId != null && accessPolicy.isMember(userId, classId);
        boolean editor = userId != null && accessPolicy.canManageAny(userId, classId, "BLOG", EDITOR_ACTIONS);
        return new Viewer(userId, classId, member, editor);
    }

    // ------------------------------------------------------------------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public BlogPostPageDto list(String classId, String userId, String category, String status, String cursor, Integer size) {
        accessPolicy.requireVisibleClass(classId, userId, CLASS_NOT_FOUND);
        Viewer viewer = viewer(classId, userId);

        String statusFilter = status == null || status.isBlank() ? BlogPost.STATUS_PUBLISHED : status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of(BlogPost.STATUS_PUBLISHED, BlogPost.STATUS_DRAFT, "ALL").contains(statusFilter)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Trạng thái bài viết không hợp lệ: " + status);
        }
        boolean publishedOnly = BlogPost.STATUS_PUBLISHED.equals(statusFilter);
        if (!publishedOnly && !viewer.editor()) {
            throw new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "Không có quyền xem bài viết nháp của lớp học");
        }

        int pageSize = Math.min(Math.max(size != null ? size : DEFAULT_PAGE_SIZE, 1), MAX_PAGE_SIZE);
        BlogCursor position = BlogCursor.decode(cursor);
        String categoryFilter = category == null ? "" : category.trim();
        Pageable limit = Pageable.ofSize(pageSize + 1);
        List<BlogPost> rows = publishedOnly
                ? postRepository.findPublishedPage(classId, categoryFilter, position.at(), position.id(), limit)
                : postRepository.findManagedPage(classId,
                        "ALL".equals(statusFilter) ? List.of(BlogPost.STATUS_DRAFT, BlogPost.STATUS_PUBLISHED) : List.of(BlogPost.STATUS_DRAFT),
                        categoryFilter, position.at(), position.id(), limit);

        boolean hasNext = rows.size() > pageSize;
        List<BlogPost> page = hasNext ? rows.subList(0, pageSize) : rows;
        String nextCursor = null;
        if (hasNext && !page.isEmpty()) {
            BlogPost last = page.get(page.size() - 1);
            Instant at = publishedOnly || last.getPublishedAt() != null ? last.getPublishedAt() : last.getCreatedAt();
            nextCursor = new BlogCursor(at, last.getId()).encode();
        }
        return new BlogPostPageDto(toDtos(page, viewer, false), nextCursor);
    }

    @Transactional(readOnly = true)
    public List<String> categories(String classId, String userId) {
        accessPolicy.requireVisibleClass(classId, userId, CLASS_NOT_FOUND);
        return postRepository.findPublishedCategories(classId);
    }

    @Transactional(readOnly = true)
    public BlogPostDto get(String postId, String userId) {
        BlogPost post = postRepository.findById(postId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, POST_NOT_FOUND));
        accessPolicy.requireVisibleClass(post.getClassId(), userId, POST_NOT_FOUND);
        Viewer viewer = viewer(post.getClassId(), userId);
        if (!post.isPublished() && !viewer.editor()) {
            throw new AppException(ErrorCode.NOT_FOUND, POST_NOT_FOUND);
        }
        return toDtos(List.of(post), viewer, true).get(0);
    }

    // ----------------------------------------------------------------------------------------------------------------------------- writes

    @Transactional
    public BlogPostDto create(String classId, String userId, CreateBlogPostRequest request) {
        accessPolicy.enforceManage(userId, classId, "BLOG", "CREATE", null);
        requireNotArchived(classId, "Lớp học đã được lưu trữ; không thể viết bài mới");

        BlogPost post = new BlogPost();
        post.setClassId(classId);
        post.setAuthorId(userId);
        post.setTitle(request.title().trim());
        post.setExcerpt(blankToNull(request.excerpt()));
        post.setCategory(blankToNull(request.category()));
        post.setContentMarkdown(request.contentMarkdown());
        post.setAudience(request.audience().trim().toUpperCase(Locale.ROOT));
        post.setStatus(BlogPost.STATUS_DRAFT);
        String cover = blankToNull(request.coverMediaId());
        post.setCoverMediaId(cover == null ? null : mediaService.requireAttachableImage(cover, classId, MEDIA_PURPOSE));
        post.setReadingMinutes(readingMinutes(post.getContentMarkdown()));
        BlogPost saved = postRepository.save(post);

        audit(saved, userId, "BLOG_POST_CREATE", details(saved, null));
        return toDtos(List.of(saved), viewer(classId, userId), true).get(0);
    }

    @Transactional
    public BlogPostDto update(String postId, String userId, UpdateBlogPostRequest request) {
        BlogPost post = loadForWrite(postId, userId, "EDIT");
        List<String> changed = new ArrayList<>();
        if (request.getTitle() != null) {
            if (request.getTitle().isBlank()) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Tiêu đề bài viết không được để trống");
            }
            post.setTitle(request.getTitle().trim());
            changed.add("title");
        }
        if (request.hasExcerpt()) {
            post.setExcerpt(blankToNull(request.getExcerpt()));
            changed.add("excerpt");
        }
        if (request.hasCategory()) {
            post.setCategory(blankToNull(request.getCategory()));
            changed.add("category");
        }
        if (request.getContentMarkdown() != null) {
            if (post.isPublished() && request.getContentMarkdown().isBlank()) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Bài viết đã xuất bản phải có nội dung");
            }
            post.setContentMarkdown(request.getContentMarkdown());
            changed.add("contentMarkdown");
        }
        if (request.hasCoverMediaId()) {
            String cover = blankToNull(request.getCoverMediaId());
            post.setCoverMediaId(cover == null ? null : mediaService.requireAttachableImage(cover, post.getClassId(), MEDIA_PURPOSE));
            changed.add("coverMediaId");
        }
        if (request.getAudience() != null) {
            post.setAudience(request.getAudience().trim().toUpperCase(Locale.ROOT));
            changed.add("audience");
        }
        post.setReadingMinutes(readingMinutes(post.getContentMarkdown()));
        post.setUpdatedAt(now());
        // saveAndFlush: the @Version check runs here, so a concurrent change surfaces as a 409 from this call.
        BlogPost saved = postRepository.saveAndFlush(post);

        audit(saved, userId, "BLOG_POST_UPDATE", details(saved, changed));
        return toDtos(List.of(saved), viewer(saved.getClassId(), userId), true).get(0);
    }

    @Transactional
    public BlogPostDto publish(String postId, String userId) {
        BlogPost post = loadForWrite(postId, userId, "PUBLISH");
        requireNotArchived(post.getClassId(), "Lớp học đã được lưu trữ; không thể xuất bản bài viết");
        if (post.getContentMarkdown() == null || post.getContentMarkdown().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Bài viết cần có nội dung trước khi xuất bản");
        }
        if (!post.isPublished()) {
            post.setStatus(BlogPost.STATUS_PUBLISHED);
            if (post.getPublishedAt() == null) {
                post.setPublishedAt(now()); // first publish only: a republished post keeps its place in the list
            }
            post.setUpdatedAt(now());
            post = postRepository.saveAndFlush(post);
            audit(post, userId, "BLOG_POST_PUBLISH", details(post, null));
        }
        return toDtos(List.of(post), viewer(post.getClassId(), userId), true).get(0);
    }

    @Transactional
    public BlogPostDto unpublish(String postId, String userId) {
        BlogPost post = loadForWrite(postId, userId, "PUBLISH");
        if (post.isPublished()) {
            post.setStatus(BlogPost.STATUS_DRAFT);
            post.setUpdatedAt(now());
            post = postRepository.saveAndFlush(post);
            audit(post, userId, "BLOG_POST_UNPUBLISH", details(post, null));
        }
        return toDtos(List.of(post), viewer(post.getClassId(), userId), true).get(0);
    }

    @Transactional
    public void delete(String postId, String userId) {
        BlogPost post = loadForWrite(postId, userId, "DELETE");
        postRepository.delete(post);
        audit(post, userId, "BLOG_POST_DELETE", details(post, null));
    }

    /** 404 for an unknown post and for a post of a PRIVATE class hidden from the caller (indistinguishable), then the BLOG grant. */
    private BlogPost loadForWrite(String postId, String userId, String action) {
        BlogPost post = postRepository.findById(postId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, POST_NOT_FOUND));
        if (accessPolicy.isMissingOrHiddenPrivateClass(post.getClassId(), userId)) {
            throw new AppException(ErrorCode.NOT_FOUND, POST_NOT_FOUND);
        }
        accessPolicy.enforceManage(userId, post.getClassId(), "BLOG", action, null);
        return post;
    }

    /** D-11: an ARCHIVED class is read-only for new activity; D-29: so is a SUSPENDED one (not ACTIVE = frozen). */
    private void requireNotArchived(String classId, String message) {
        if (accessPolicy.isClassFrozen(classId)) {
            throw new AppException(ErrorCode.CONFLICT, (accessPolicy.isClassSuspended(classId) ? AccessPolicy.SUSPENDED_MESSAGE : message));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------- mapping

    /** A page in a fixed number of statements: one for the authors, one for the cover images (signing is local). */
    private List<BlogPostDto> toDtos(List<BlogPost> posts, Viewer viewer, boolean includeContent) {
        if (posts.isEmpty()) return List.of();
        Set<String> authorIds = new LinkedHashSet<>();
        Set<String> coverIds = new LinkedHashSet<>();
        for (BlogPost p : posts) {
            authorIds.add(p.getAuthorId());
            if (p.getCoverMediaId() != null) coverIds.add(p.getCoverMediaId());
        }
        Map<String, User> users = new HashMap<>();
        userRepository.findAllById(authorIds).forEach(u -> users.put(u.getId(), u));
        Map<String, String> coverUrls = coverIds.isEmpty() ? Map.of() : mediaService.presignedImageUrls(coverIds, MEDIA_PURPOSE);

        List<BlogPostDto> result = new ArrayList<>(posts.size());
        for (BlogPost p : posts) {
            boolean locked = !viewer.canRead(p);
            PersonSummaryDto authorDto = PersonSummaryDto.of(users.get(p.getAuthorId()), p.getAuthorId());
            result.add(new BlogPostDto(
                    p.getId(), p.getClassId(), p.getTitle(), p.getExcerpt(), p.getCategory(),
                    includeContent && !locked ? p.getContentMarkdown() : null,
                    p.getCoverMediaId(), p.getCoverMediaId() == null ? null : coverUrls.get(p.getCoverMediaId()),
                    p.getAudience(), p.getStatus(), locked, p.getReadingMinutes(), authorDto,
                    p.getPublishedAt(), p.getCreatedAt(), p.getUpdatedAt()));
        }
        return result;
    }

    /** ceil(words / 200), at least 1. A word is a whitespace-separated token containing at least one letter or digit. */
    public static int readingMinutes(String markdown) {
        if (markdown == null || markdown.isBlank()) return 1;
        int words = 0;
        for (String token : markdown.trim().split("\\s+")) {
            if (token.codePoints().anyMatch(Character::isLetterOrDigit)) words++;
        }
        return Math.max(1, (words + 199) / 200);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private Map<String, Object> details(BlogPost post, List<String> changed) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("title", post.getTitle());
        details.put("status", post.getStatus());
        details.put("audience", post.getAudience());
        if (changed != null) details.put("changed", changed);
        return details;
    }

    private void audit(BlogPost post, String actorId, String action, Map<String, Object> details) {
        String json;
        try {
            json = objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise audit details", e);
        }
        auditService.record(post.getClassId(), actorId, action, "BLOG_POST", post.getId(), json);
    }
}
