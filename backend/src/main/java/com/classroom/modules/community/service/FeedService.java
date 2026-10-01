package com.classroom.modules.community.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.commerce.model.Entitlement;
import com.classroom.modules.commerce.model.Product;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.community.dto.CommentDto;
import com.classroom.modules.community.dto.CommentPageDto;
import com.classroom.modules.community.dto.FeedPageDto;
import com.classroom.modules.community.dto.PostDto;
import com.classroom.modules.community.dto.CreatePostRequest;
import com.classroom.modules.community.dto.UpdatePostRequest;
import com.classroom.modules.community.dto.UpdateCommentRequest;
import com.classroom.modules.community.model.Comment;
import com.classroom.modules.community.model.Post;
import com.classroom.modules.community.repository.CommentRepository;
import com.classroom.modules.community.repository.PostRepository;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.segment.model.Segment;
import com.classroom.modules.segment.repository.SegmentRepository;
import com.classroom.modules.segment.service.SegmentService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class FeedService {

    private static final Set<String> ALLOWED_VISIBILITY = Set.of(
            "PUBLIC", "FREE", "PRO", "PRODUCT_OWNER", "SEGMENT"
    );

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final AccessPolicy accessPolicy;
    private final ProPolicy proPolicy;
    private final EntitlementRepository entitlementRepository;
    private final ProductRepository productRepository;
    private final SegmentRepository segmentRepository;
    private final SegmentService segmentService;
    private final ProfileVisibilityPolicy profileVisibilityPolicy;
    private final ClassroomRepository classroomRepository;

    public FeedService(PostRepository postRepository,
                       CommentRepository commentRepository,
                       UserRepository userRepository,
                       AccessPolicy accessPolicy,
                       ProPolicy proPolicy,
                       EntitlementRepository entitlementRepository,
                       ProductRepository productRepository,
                       SegmentRepository segmentRepository,
                       SegmentService segmentService,
                       ProfileVisibilityPolicy profileVisibilityPolicy,
                       ClassroomRepository classroomRepository) {
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.proPolicy = proPolicy;
        this.entitlementRepository = entitlementRepository;
        this.productRepository = productRepository;
        this.segmentRepository = segmentRepository;
        this.segmentService = segmentService;
        this.profileVisibilityPolicy = profileVisibilityPolicy;
        this.classroomRepository = classroomRepository;
    }

    /**
     * R20-03: a feed page embeds only the latest few comments of each post (plus the post's total {@code commentCount});
     * older ones are fetched on demand through {@link #listComments} ("Xem thêm bình luận"). A page used to carry every comment of
     * every post, so its cost and size grew with the discussion instead of with the page size.
     */
    public static final int EMBEDDED_COMMENTS_PER_POST = 3;
    public static final int COMMENT_PAGE_DEFAULT = 20;
    public static final int COMMENT_PAGE_MAX = 50;

    /** Cap on page size for the paginated listing (R3-08 / R5-01). */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 10;
    /**
     * Size of each underlying SQL keyset batch fetched from the database while filling a page.
     * Visibility rules such as SEGMENT/PRODUCT_OWNER cannot be expressed in SQL, so a plain
     * "paginate then filter" approach yields short, unpredictable pages and can make older visible
     * posts unreachable. Instead we fetch successive keyset batches of the newest posts and keep
     * filtering until the requested page is full or the class runs out of posts. There is no
     * upper bound on the number of batches scanned (R5-01/R5-05): a request keeps advancing the
     * cursor until the page is filled or the feed is exhausted, so no visible post is ever
     * silently dropped by a scan cap.
     */
    private static final int BATCH_SIZE = 200;

    /**
     * R5-01: keyset (cursor) paged listing. Returns exactly {@code size} visible posts (or fewer
     * once the feed is exhausted) plus an opaque cursor for the next page, by fetching successive
     * keyset batches ordered (pinned DESC, createdAt DESC, id DESC) — stable across concurrent
     * inserts/deletes, unlike offset paging — and filtering each batch (visibility rules like
     * SEGMENT/PRODUCT_OWNER can't be pushed into SQL) until the page is full or the class runs out
     * of posts. R5-04: applies the same class-visibility gate as the about/products endpoints,
     * 404-ing for a class that does not exist and 401/403 for one the viewer cannot see.
     */
    @Transactional(readOnly = true)
    public FeedPageDto getFeedPage(String classId, String userId, String cursor, Integer size) {
        Classroom classroom = classroomRepository.findById(classId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học"));
        if (accessPolicy.isHiddenPrivateClass(classroom, userId)) {
            // D-19: a PRIVATE class does not exist for a viewer with no relation to it - the same 404 as an unknown class id.
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học");
        }
        if (!accessPolicy.isClassVisibleToUser(classroom, userId)) {
            if (userId == null) {
                throw new AppException(ErrorCode.UNAUTHORIZED, "Yêu cầu đăng nhập để xem bảng tin của lớp học này");
            }
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn không có quyền truy cập bảng tin của lớp học không công khai này");
        }

        if (userId != null && accessPolicy.isMembershipExpired(userId, classId)) {
            // D-19: a lapsed paid member may read About / Store / the paywall only - not even the public part of the feed.
            throw new AppException(ErrorCode.MEMBERSHIP_EXPIRED);
        }

        int safeSize = Math.min(Math.max(size != null ? size : DEFAULT_PAGE_SIZE, 1), MAX_PAGE_SIZE);
        FeedCursor position = FeedCursor.decode(cursor);

        ViewerContext viewer = resolveViewer(classId, userId);
        List<Post> pagePosts = new ArrayList<>();
        boolean hasNext = false;

        // Fetch successive keyset batches, advancing the cursor by the last scanned row each time
        // (not by how many turned out visible), until the page is full — plus one more visible
        // post beyond it to know hasNext — or the class runs out of posts. No cap on the number of
        // batches (R5-05): every visible post stays reachable, however deep in the feed.
        batchLoop:
        while (true) {
            List<Post> batch = postRepository.findKeysetBatch(classId, "PUBLISHED",
                    position.pinned, position.createdAt, position.id, Pageable.ofSize(BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            for (Post p : batch) {
                if (!isVisible(p, viewer)) {
                    continue;
                }
                if (pagePosts.size() == safeSize) {
                    hasNext = true;
                    break batchLoop;
                }
                pagePosts.add(p);
            }
            Post last = batch.get(batch.size() - 1);
            position = FeedCursor.of(last.isPinned(), last.getCreatedAt(), last.getId());
            if (batch.size() < BATCH_SIZE) {
                // Short batch: the underlying table is exhausted, nothing more to fetch.
                break;
            }
        }

        String nextCursor = null;
        if (hasNext && !pagePosts.isEmpty()) {
            Post lastOnPage = pagePosts.get(pagePosts.size() - 1);
            nextCursor = FeedCursor.of(lastOnPage.isPinned(), lastOnPage.getCreatedAt(), lastOnPage.getId()).encode();
        }

        return new FeedPageDto(toDtos(pagePosts, viewer), nextCursor, hasNext);
    }

    /** Viewer-scoped state resolved once per request and reused across every post's visibility check. */
    private static final class ViewerContext {
        final String userId;
        final String classId;
        final boolean isOwner;
        final boolean isStaff;
        final boolean isMember;
        final boolean isPro;
        final boolean includeComments;
        final Instant now;
        /** PRODUCT_OWNER (R5-01): the viewer's active entitlement product ids, loaded once. */
        final Set<String> ownedProductIds;
        /** SEGMENT (R5-01): memoized per request so each distinct segment id is evaluated once. */
        final Map<String, Boolean> segmentMembershipMemo = new HashMap<>();
        final SegmentService segmentService;

        ViewerContext(String userId, String classId, boolean isOwner, boolean isStaff, boolean isMember,
                      boolean isPro, boolean includeComments, Instant now, Set<String> ownedProductIds,
                      SegmentService segmentService) {
            this.userId = userId;
            this.classId = classId;
            this.isOwner = isOwner;
            this.isStaff = isStaff;
            this.isMember = isMember;
            this.isPro = isPro;
            this.includeComments = includeComments;
            this.now = now;
            this.ownedProductIds = ownedProductIds;
            this.segmentService = segmentService;
        }

        boolean isInSegment(String segmentId) {
            return segmentMembershipMemo.computeIfAbsent(segmentId,
                    id -> segmentService.isUserInSegment(id, userId, classId));
        }
    }

    private ViewerContext resolveViewer(String classId, String userId) {
        boolean isOwner = (userId != null) && accessPolicy.isOwner(userId, classId);
        boolean isStaff = (userId != null) && accessPolicy.canManage(userId, classId, "FEED", "VIEW", null);
        boolean isMember = (userId != null) && accessPolicy.isMember(userId, classId);
        boolean isPro = (userId != null) && proPolicy.isPro(userId, classId);
        // R3-01: comments are internal discussion, not public content — an anonymous or
        // non-member viewer must never see them, even on a PUBLIC post.
        boolean includeComments = isMember || isOwner || isStaff;
        Instant now = Instant.now();

        // R5-01: owner/staff see every post regardless of targeting, so resolving entitlements for
        // them would be wasted work; a non-member also can never satisfy PRODUCT_OWNER (it requires
        // isMember()), so only load entitlements for a plain member.
        Set<String> ownedProductIds = Set.of();
        if (userId != null && isMember && !isOwner && !isStaff) {
            List<Entitlement> active = entitlementRepository.findActiveEntitlements(userId, classId, now);
            ownedProductIds = new HashSet<>();
            for (Entitlement e : active) {
                if (e.getProductId() != null) {
                    ownedProductIds.add(e.getProductId());
                }
            }
        }

        return new ViewerContext(userId, classId, isOwner, isStaff, isMember, isPro, includeComments, now, ownedProductIds, segmentService);
    }

    private boolean isVisible(Post p, ViewerContext viewer) {
        if (viewer.isOwner || viewer.isStaff) {
            return true;
        }
        String vis = (p.getVisibility() != null) ? p.getVisibility().toUpperCase().trim() : "";
        return switch (vis) {
            case "PUBLIC" -> true;
            case "FREE" -> viewer.isMember;
            case "PRO" -> viewer.isMember && viewer.isPro;
            case "PRODUCT_OWNER" -> viewer.userId != null && p.getTargetProductId() != null
                    && viewer.isMember
                    && viewer.ownedProductIds.contains(p.getTargetProductId());
            case "SEGMENT" -> p.getTargetSegmentId() != null && viewer.userId != null
                    && viewer.isMember
                    && viewer.isInSegment(p.getTargetSegmentId());
            default -> false; // Deny by default for unknown or malformed visibility (Finding 9)
        };
    }

    /**
     * R20-03: the whole page in a FIXED number of statements, independent of how many comments or distinct authors it holds:
     * comment counts (1), the latest comments of all posts (1), every author and commenter (1). The viewer's privacy context is
     * built ONCE for the page - per-call policy methods used to rebuild it (and re-run the class-administrator / peer queries on
     * staff_assignments and class_members) for every name, avatar and id of every post and comment: 853 statements for 10 posts.
     */
    private List<PostDto> toDtos(List<Post> visibleRaw, ViewerContext viewer) {
        List<PostDto> visiblePosts = new ArrayList<>();
        if (visibleRaw.isEmpty()) {
            return visiblePosts;
        }
        List<String> postIds = visibleRaw.stream().map(Post::getId).toList();
        Map<String, Long> commentCounts = new HashMap<>();
        commentRepository.countByPostIdIn(postIds)
                .forEach(row -> commentCounts.put(row.getPostId(), row.getTotal()));

        Map<String, List<Comment>> commentsByPost = new HashMap<>();
        if (viewer.includeComments) {
            for (Comment c : commentRepository.findLatestByPostIds(postIds, EMBEDDED_COMMENTS_PER_POST)) {
                commentsByPost.computeIfAbsent(c.getPostId(), k -> new ArrayList<>()).add(c);
            }
        }

        Set<String> userIds = new LinkedHashSet<>();
        for (Post p : visibleRaw) userIds.add(p.getAuthorId());
        commentsByPost.values().forEach(list -> list.forEach(c -> userIds.add(c.getAuthorId())));
        Map<String, User> usersById = loadUsers(userIds);

        ProfileVisibilityPolicy.ViewerContext identity = profileVisibilityPolicy
                .viewerContext(viewer.userId, viewer.classId)
                .withKnownClassPeer(viewer.isMember)
                .prefetchMembershipRecords(userIds);

        for (Post p : visibleRaw) {
            List<CommentDto> commentDtos = null;
            if (viewer.includeComments) {
                commentDtos = new ArrayList<>();
                for (Comment c : commentsByPost.getOrDefault(p.getId(), List.of())) {
                    commentDtos.add(toCommentDto(c, usersById.get(c.getAuthorId()), identity));
                }
            }
            visiblePosts.add(toPostDto(p, usersById.get(p.getAuthorId()), commentCounts.getOrDefault(p.getId(), 0L),
                    commentDtos, identity));
        }
        return visiblePosts;
    }

    /** One {@code findAllById} for any set of user ids. */
    private Map<String, User> loadUsers(Collection<String> userIds) {
        Map<String, User> usersById = new HashMap<>();
        if (!userIds.isEmpty()) {
            userRepository.findAllById(userIds).forEach(u -> usersById.put(u.getId(), u));
        }
        return usersById;
    }

    /**
     * R20-03: "Xem thêm bình luận" - the {@code size} comments immediately older than {@code beforeCommentId} (the oldest one the
     * client already shows), oldest-first, with {@code hasMore} / {@code nextBefore} for the next click. Same gates as the feed:
     * the post must be published and visible to the caller, and - like the embedded comments - comments are internal
     * discussion, only for members / owner / feed staff (R3-01).
     */
    @Transactional(readOnly = true)
    public CommentPageDto listComments(String postId, String userId, String beforeCommentId, Integer size) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài viết"));
        Classroom classroom = classroomRepository.findById(post.getClassId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học"));
        if (accessPolicy.isHiddenPrivateClass(classroom, userId)) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài viết"); // identical to an unknown post id
        }
        if (!accessPolicy.isClassVisibleToUser(classroom, userId)) {
            throw new AppException(userId == null ? ErrorCode.UNAUTHORIZED : ErrorCode.FORBIDDEN,
                    "Bạn không có quyền xem bình luận của lớp học này");
        }
        if (userId != null && accessPolicy.isMembershipExpired(userId, post.getClassId())) {
            throw new AppException(ErrorCode.MEMBERSHIP_EXPIRED);
        }
        ViewerContext viewer = resolveViewer(post.getClassId(), userId);
        if (!"PUBLISHED".equalsIgnoreCase(post.getStatus()) || !isVisible(post, viewer)) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài viết");
        }
        if (!viewer.includeComments) {
            throw new AppException(ErrorCode.FORBIDDEN, "Chỉ thành viên lớp học mới xem được bình luận");
        }

        int pageSize = Math.min(Math.max(size != null ? size : COMMENT_PAGE_DEFAULT, 1), COMMENT_PAGE_MAX);
        List<Comment> older;
        if (beforeCommentId == null || beforeCommentId.isBlank()) {
            older = commentRepository.findOlderThan(postId, Instant.now().plusSeconds(1), "~", PageRequest.of(0, pageSize + 1));
        } else {
            Comment anchor = commentRepository.findById(beforeCommentId)
                    .filter(c -> postId.equals(c.getPostId()))
                    .orElseThrow(() -> new AppException(ErrorCode.BAD_REQUEST, "Bình luận mốc không thuộc bài viết này"));
            older = commentRepository.findOlderThan(postId, anchor.getCreatedAt(), anchor.getId(), PageRequest.of(0, pageSize + 1));
        }
        boolean hasMore = older.size() > pageSize;
        List<Comment> pageNewestFirst = hasMore ? older.subList(0, pageSize) : older;
        List<Comment> page = new ArrayList<>(pageNewestFirst);
        java.util.Collections.reverse(page); // oldest first, like the embedded comments

        Set<String> authorIds = new LinkedHashSet<>();
        page.forEach(c -> authorIds.add(c.getAuthorId()));
        Map<String, User> usersById = loadUsers(authorIds);
        ProfileVisibilityPolicy.ViewerContext identity = profileVisibilityPolicy
                .viewerContext(userId, post.getClassId())
                .withKnownClassPeer(viewer.isMember)
                .prefetchMembershipRecords(authorIds);
        List<CommentDto> dtos = new ArrayList<>(page.size());
        for (Comment c : page) {
            dtos.add(toCommentDto(c, usersById.get(c.getAuthorId()), identity));
        }
        String nextBefore = hasMore && !page.isEmpty() ? page.get(0).getId() : null;
        return new CommentPageDto(dtos, hasMore, nextBefore);
    }

    @Transactional
    public PostDto createPost(String classId, String userId, CreatePostRequest request) {
        accessPolicy.enforceMember(userId, classId);

        // Required-field validation must answer 400, not let a NOT NULL column raise a 500 at flush.
        if (request == null || request.title() == null || request.title().isBlank()
                || request.contentMarkdown() == null || request.contentMarkdown().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tiêu đề và nội dung bài viết không được để trống");
        }

        User author = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng"));

        // Validate allowed visibility values (Finding 9)
        String visibility = (request.visibility() != null && !request.visibility().isBlank())
                ? request.visibility().toUpperCase().trim()
                : "FREE";
        if (!ALLOWED_VISIBILITY.contains(visibility)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Mức độ hiển thị bài đăng không hợp lệ: " + request.visibility());
        }
        Post post = new Post(classId, userId, request.title(), request.contentMarkdown(), visibility);
        post.setId(java.util.UUID.randomUUID().toString());
        post.setTargetProductId(request.targetProductId());
        post.setTargetSegmentId(request.targetSegmentId());
        post.setPinned(request.pinned());
        post.setVisibility(visibility);

        // Validate target same-class boundaries (Finding 9)
        if ("PRODUCT_OWNER".equalsIgnoreCase(visibility)) {
            if (post.getTargetProductId() == null || post.getTargetProductId().isBlank()) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Bài đăng PRODUCT_OWNER yêu cầu chỉ định targetProductId");
            }
            Product product = productRepository.findById(post.getTargetProductId())
                    .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm mục tiêu"));
            if (!product.getClassId().equals(classId)) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Sản phẩm mục tiêu không thuộc lớp học này");
            }
        } else if ("SEGMENT".equalsIgnoreCase(visibility)) {
            if (post.getTargetSegmentId() == null || post.getTargetSegmentId().isBlank()) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Bài đăng SEGMENT yêu cầu chỉ định targetSegmentId");
            }
            Segment segment = segmentRepository.findById(post.getTargetSegmentId())
                    .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy phân khúc mục tiêu"));
            if (!segment.getClassId().equals(classId)) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Phân khúc mục tiêu không thuộc lớp học này");
            }
        }

        post.setClassId(classId);
        post.setAuthorId(userId);
        post.setStatus("PUBLISHED");

        // Only staff or owner can pin or set non-public visibility. PUBLIC is included here
        // (R3-01): it is visible to non-members and even anonymous viewers, so creating one is
        // an editorial/managed action just like PRO/PRODUCT_OWNER/SEGMENT, not a plain member action.
        if (post.isPinned() || "PUBLIC".equalsIgnoreCase(post.getVisibility())
                || "PRO".equalsIgnoreCase(post.getVisibility())
                || "PRODUCT_OWNER".equalsIgnoreCase(post.getVisibility())
                || "SEGMENT".equalsIgnoreCase(post.getVisibility())) {
            accessPolicy.enforceManage(userId, classId, "FEED", "CREATE", null);
        }

        Post saved = postRepository.save(post);
        return toPostDto(saved, false, userId);
    }

    @Transactional
    public CommentDto addComment(String postId, String userId, String content) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài viết"));

        accessPolicy.enforceMember(userId, post.getClassId());
        if (!"PUBLISHED".equalsIgnoreCase(post.getStatus()) || !canViewPost(post, userId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn không có quyền bình luận bài viết này");
        }

        if (content == null || content.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Nội dung bình luận không được để trống");
        }

        User author = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng"));

        Comment comment = new Comment(postId, userId, content.trim());
        Comment saved = commentRepository.save(comment);

        // The commenter is the viewer here, so the policy always resolves to their real identity;
        // routing through it anyway keeps this response and the feed listing on one code path.
        return toCommentDto(saved, author, userId, post.getClassId());
    }

    @Transactional
    public PostDto updatePost(String postId, String userId, UpdatePostRequest request) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài viết"));
        boolean isActiveAuthor = post.getAuthorId().equals(userId) && accessPolicy.isMember(userId, post.getClassId());
        boolean canManage = accessPolicy.canManage(userId, post.getClassId(), "FEED", "EDIT", null);
        if ((!isActiveAuthor || requiresFeedManagement(post)) && !canManage) {
            throw forbiddenOrExpired(userId, post.getClassId(), "Không có quyền sửa bài viết này");
        }
        if (request == null || request.title() == null || request.title().isBlank()
                || request.contentMarkdown() == null || request.contentMarkdown().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tiêu đề và nội dung bài viết không được để trống");
        }
        post.setTitle(request.title().trim());
        post.setContentMarkdown(request.contentMarkdown().trim());
        post.setUpdatedAt(Instant.now());
        return toPostDto(postRepository.save(post), false, userId);
    }

    @Transactional
    public CommentDto updateComment(String commentId, String userId, UpdateCommentRequest request) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bình luận"));
        Post post = postRepository.findById(comment.getPostId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài viết"));
        boolean isActiveAuthor = comment.getAuthorId().equals(userId) && accessPolicy.isMember(userId, post.getClassId());
        boolean canManage = accessPolicy.canManage(userId, post.getClassId(), "FEED", "EDIT", null);
        if (!isActiveAuthor && !canManage) {
            throw forbiddenOrExpired(userId, post.getClassId(), "Không có quyền sửa bình luận này");
        }
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Nội dung bình luận không được để trống");
        }
        comment.setContent(request.content().trim());
        Comment saved = commentRepository.save(comment);
        User author = userRepository.findById(comment.getAuthorId()).orElse(null);
        return toCommentDto(saved, author, userId, post.getClassId());
    }

    /** D-19: a lapsed paid member editing or deleting their own content is told their access expired, not a bare 403. */
    private AppException forbiddenOrExpired(String userId, String classId, String message) {
        return accessPolicy.isMembershipExpired(userId, classId)
                ? new AppException(ErrorCode.MEMBERSHIP_EXPIRED)
                : new AppException(ErrorCode.FORBIDDEN, message);
    }

    private boolean canViewPost(Post post, String userId) {
        // The owner and FEED-managing staff already see every post in the listing; the same
        // override must apply here, otherwise a teacher is locked out of commenting on their own
        // PRODUCT_OWNER / SEGMENT announcement unless they buy their own product.
        if (userId != null
                && (accessPolicy.isOwner(userId, post.getClassId())
                    || accessPolicy.canManage(userId, post.getClassId(), "FEED", "VIEW", null))) {
            return true;
        }
        String visibility = post.getVisibility() == null ? "" : post.getVisibility().trim().toUpperCase();
        return switch (visibility) {
            case "PUBLIC" -> true;
            case "FREE" -> accessPolicy.isMember(userId, post.getClassId());
            case "PRO" -> accessPolicy.isMember(userId, post.getClassId()) && proPolicy.isPro(userId, post.getClassId());
            case "PRODUCT_OWNER" -> post.getTargetProductId() != null
                    && accessPolicy.isMember(userId, post.getClassId())
                    && entitlementRepository.findActiveEntitlements(userId, post.getClassId(), Instant.now()).stream()
                    .anyMatch(e -> post.getTargetProductId().equals(e.getProductId()));
            case "SEGMENT" -> accessPolicy.isMember(userId, post.getClassId()) && post.getTargetSegmentId() != null
                    && segmentService.isUserInSegment(post.getTargetSegmentId(), userId, post.getClassId());
            default -> false;
        };
    }

    @Transactional
    public void deletePost(String postId, String userId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài viết"));

        // An author's self-service action is valid only while they remain an active member.
        boolean isAuthor = post.getAuthorId().equals(userId) && accessPolicy.isMember(userId, post.getClassId());
        boolean canManage = accessPolicy.canManage(userId, post.getClassId(), "FEED", "DELETE", null);

        if ((!isAuthor || requiresFeedManagement(post)) && !canManage) {
            throw forbiddenOrExpired(userId, post.getClassId(), "Không có quyền xóa bài viết này");
        }

        postRepository.delete(post);
    }

    @Transactional
    public void deleteComment(String commentId, String userId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bình luận"));
        Post post = postRepository.findById(comment.getPostId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài viết"));

        // Mirrors deletePost: the comment's author may remove it while still an active member of
        // the class; otherwise owner/staff need a current FEED:DELETE grant (R3-02).
        boolean isAuthor = comment.getAuthorId().equals(userId) && accessPolicy.isMember(userId, post.getClassId());
        boolean canManage = accessPolicy.canManage(userId, post.getClassId(), "FEED", "DELETE", null);

        if (!isAuthor && !canManage) {
            throw forbiddenOrExpired(userId, post.getClassId(), "Không có quyền xóa bình luận này");
        }

        // Post deletion in this service is a hard delete with no outbox event, so comment
        // deletion follows the same, simpler approach for consistency.
        commentRepository.delete(comment);
    }

    /** Targeted or pinned posts are managed content; authors need a current FEED management grant. */
    private boolean requiresFeedManagement(Post post) {
        String visibility = post.getVisibility() == null ? "" : post.getVisibility().toUpperCase(Locale.ROOT);
        return post.isPinned() || Set.of("PUBLIC", "PRO", "PRODUCT_OWNER", "SEGMENT").contains(visibility);
    }

    /**
     * Maps a post for a specific viewer.
     *
     * <p>Author names and avatars go through {@link ProfileVisibilityPolicy}, the same policy the
     * member listing and the leaderboard use. Without it a learner who set their profile to PRIVATE
     * would stay anonymous in those listings but be named in the feed, and because the DTOs also
     * carry the author id a peer could join the two and de-anonymise them.</p>
     */
    private PostDto toPostDto(Post post, boolean includeComments, String viewerId) {
        User author = userRepository.findById(post.getAuthorId()).orElse(null);
        long commentCount = commentRepository.countByPostId(post.getId());
        ProfileVisibilityPolicy.ViewerContext identity = profileVisibilityPolicy.viewerContext(viewerId, post.getClassId());
        // includeComments is false for every single-post caller (create / update): a fresh or edited post is returned without its thread.
        return toPostDto(post, author, commentCount, includeComments ? List.of() : null, identity);
    }

    /** Maps a post for the viewer described by {@code identity}; {@code comments} null means "not included". */
    private PostDto toPostDto(Post post, User author, long commentCount, List<CommentDto> comments,
                              ProfileVisibilityPolicy.ViewerContext identity) {
        PostDto dto = new PostDto();
        dto.setId(post.getId());
        dto.setClassId(post.getClassId());
        dto.setTitle(post.getTitle());
        dto.setContentMarkdown(post.getContentMarkdown());
        dto.setVisibility(post.getVisibility());
        dto.setTargetProductId(post.getTargetProductId());
        dto.setTargetSegmentId(post.getTargetSegmentId());
        dto.setPinned(post.isPinned());
        dto.setStatus(post.getStatus());
        dto.setCreatedAt(post.getCreatedAt());

        if (author != null) {
            ProfileVisibilityPolicy.Identity who = identity.identityOf(author);
            dto.setAuthorId(who.userId());
            dto.setAuthorName(who.displayName());
            dto.setAuthorAvatarUrl(who.avatarUrl());
        }

        dto.setCommentCount(commentCount);
        dto.setComments(comments);
        return dto;
    }

    /**
     * Maps a comment for the viewer described by {@code identity}, anonymising the author when their profile visibility
     * hides them from that viewer (see {@link #toPostDto}).
     *
     * @param author the resolved author, or {@code null} when the user record no longer exists
     */
    private CommentDto toCommentDto(Comment comment, User author, ProfileVisibilityPolicy.ViewerContext identity) {
        String authorName = "Thành viên";
        String avatarUrl = null;
        String visibleAuthorId = null;
        if (author != null) {
            ProfileVisibilityPolicy.Identity who = identity.identityOf(author);
            authorName = who.displayName();
            avatarUrl = who.avatarUrl();
            visibleAuthorId = who.visible() ? comment.getAuthorId() : null;
        }
        return new CommentDto(comment.getId(), comment.getPostId(), visibleAuthorId,
                authorName, avatarUrl, comment.getContent(), comment.getCreatedAt());
    }

    /** Single-comment mapping for the write paths (add / edit): the viewer is the commenter or an editor, one context per call. */
    private CommentDto toCommentDto(Comment comment, User author, String viewerId, String classId) {
        return toCommentDto(comment, author, profileVisibilityPolicy.viewerContext(viewerId, classId));
    }
}
