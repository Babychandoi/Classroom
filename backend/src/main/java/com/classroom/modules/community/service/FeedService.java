package com.classroom.modules.community.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.model.Product;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.community.dto.CommentDto;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    public FeedService(PostRepository postRepository,
                       CommentRepository commentRepository,
                       UserRepository userRepository,
                       AccessPolicy accessPolicy,
                       ProPolicy proPolicy,
                       EntitlementRepository entitlementRepository,
                       ProductRepository productRepository,
                       SegmentRepository segmentRepository,
                       SegmentService segmentService,
                       ProfileVisibilityPolicy profileVisibilityPolicy) {
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
    }

    @Transactional(readOnly = true)
    public List<PostDto> getFeedPosts(String classId, String userId) {
        List<Post> allPosts = postRepository.findByClassIdAndStatusOrderByPinnedDescCreatedAtDesc(classId, "PUBLISHED");
        List<PostDto> visiblePosts = new ArrayList<>();

        boolean isOwner = (userId != null) && accessPolicy.isOwner(userId, classId);
        boolean isStaff = (userId != null) && accessPolicy.canManage(userId, classId, "FEED", "VIEW", null);
        boolean isMember = (userId != null) && accessPolicy.isMember(userId, classId);
        boolean isPro = (userId != null) && proPolicy.isPro(userId, classId);

        Instant now = Instant.now();

        for (Post p : allPosts) {
            boolean visible = false;

            if (isOwner || isStaff) {
                visible = true;
            } else {
                String vis = (p.getVisibility() != null) ? p.getVisibility().toUpperCase().trim() : "";
                switch (vis) {
                    case "PUBLIC" -> visible = true;
                    case "FREE" -> visible = isMember;
                    case "PRO" -> visible = isMember && isPro;
                    case "PRODUCT_OWNER" -> {
                        if (userId != null && p.getTargetProductId() != null) {
                            visible = isMember && entitlementRepository.findActiveEntitlements(userId, classId, now).stream()
                                    .anyMatch(e -> p.getTargetProductId().equals(e.getProductId()));
                        }
                    }
                    case "SEGMENT" -> {
                        if (p.getTargetSegmentId() != null && userId != null) {
                            visible = isMember && segmentService.isUserInSegment(p.getTargetSegmentId(), userId, classId);
                        } else {
                            visible = false;
                        }
                    }
                    default -> visible = false; // Deny by default for unknown or malformed visibility (Finding 9)
                }
            }

            if (visible) {
                visiblePosts.add(toPostDto(p, true, userId));
            }
        }

        return visiblePosts;
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

        // Only staff or owner can pin or set non-public visibility
        if (post.isPinned() || "PRO".equalsIgnoreCase(post.getVisibility())
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
            throw new AppException(ErrorCode.FORBIDDEN, "Không có quyền sửa bài viết này");
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
            throw new AppException(ErrorCode.FORBIDDEN, "Không có quyền sửa bình luận này");
        }
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Nội dung bình luận không được để trống");
        }
        comment.setContent(request.content().trim());
        Comment saved = commentRepository.save(comment);
        User author = userRepository.findById(comment.getAuthorId()).orElse(null);
        return toCommentDto(saved, author, userId, post.getClassId());
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
            throw new AppException(ErrorCode.FORBIDDEN, "Không có quyền xóa bài viết này");
        }

        postRepository.delete(post);
    }

    /** Targeted or pinned posts are managed content; authors need a current FEED management grant. */
    private boolean requiresFeedManagement(Post post) {
        String visibility = post.getVisibility() == null ? "" : post.getVisibility().toUpperCase(Locale.ROOT);
        return post.isPinned() || Set.of("PRO", "PRODUCT_OWNER", "SEGMENT").contains(visibility);
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

        userRepository.findById(post.getAuthorId()).ifPresent(u -> {
            if (profileVisibilityPolicy.isIdentityVisible(u, viewerId, post.getClassId())) {
                dto.setAuthorId(post.getAuthorId());
            }
            dto.setAuthorName(profileVisibilityPolicy.displayName(u, viewerId, post.getClassId()));
            dto.setAuthorAvatarUrl(profileVisibilityPolicy.avatarUrl(u, viewerId, post.getClassId()));
        });

        long commentCount = commentRepository.countByPostId(post.getId());
        dto.setCommentCount(commentCount);

        if (includeComments) {
            List<Comment> comments = commentRepository.findByPostIdOrderByCreatedAtAsc(post.getId());
            List<CommentDto> commentDtos = comments.stream()
                    .map(c -> toCommentDto(c, userRepository.findById(c.getAuthorId()).orElse(null),
                            viewerId, post.getClassId()))
                    .toList();
            dto.setComments(commentDtos);
        }

        return dto;
    }

    /**
     * Maps a comment for a specific viewer, anonymising the author when their profile visibility
     * hides them from that viewer (see {@link #toPostDto}).
     *
     * @param author the resolved author, or {@code null} when the user record no longer exists
     */
    private CommentDto toCommentDto(Comment comment, User author, String viewerId, String classId) {
        String authorName = "Thành viên";
        String avatarUrl = null;
        if (author != null) {
            authorName = profileVisibilityPolicy.displayName(author, viewerId, classId);
            avatarUrl = profileVisibilityPolicy.avatarUrl(author, viewerId, classId);
        }
        String visibleAuthorId = author != null
                && profileVisibilityPolicy.isIdentityVisible(author, viewerId, classId)
                ? comment.getAuthorId() : null;
        return new CommentDto(comment.getId(), comment.getPostId(), visibleAuthorId,
                authorName, avatarUrl, comment.getContent(), comment.getCreatedAt());
    }
}
