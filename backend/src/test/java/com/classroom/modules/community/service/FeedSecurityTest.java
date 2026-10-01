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
import com.classroom.modules.community.dto.PostDto;
import com.classroom.modules.community.dto.FeedPageDto;
import com.classroom.modules.community.dto.CreatePostRequest;
import com.classroom.modules.community.model.Post;
import com.classroom.modules.community.repository.CommentRepository;
import com.classroom.modules.community.repository.PostRepository;
import com.classroom.modules.community.dto.CommentDto;
import com.classroom.modules.community.dto.UpdatePostRequest;
import com.classroom.modules.community.dto.UpdateCommentRequest;
import com.classroom.modules.community.model.Comment;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.segment.model.Segment;
import com.classroom.modules.segment.repository.SegmentRepository;
import com.classroom.modules.segment.service.SegmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class FeedSecurityTest {

    @Mock
    private PostRepository postRepository;
    @Mock
    private CommentRepository commentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private ProPolicy proPolicy;
    @Mock
    private EntitlementRepository entitlementRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private SegmentRepository segmentRepository;
    @Mock
    private SegmentService segmentService;
    @Mock
    private ClassroomRepository classroomRepository;

    private FeedService feedService;

    private User author;
    private Classroom activeClass;

    @BeforeEach
    void setUp() {
        author = new User("author-1", "author@test.local", "hash", "Teacher One", "STAFF");
        // The visibility policy is exercised for real (over the mocked AccessPolicy) rather than
        // mocked, so the privacy assertions below test the behaviour the feed actually ships.
        feedService = new FeedService(postRepository, commentRepository, userRepository, accessPolicy,
                proPolicy, entitlementRepository, productRepository, segmentRepository, segmentService,
                new ProfileVisibilityPolicy(accessPolicy), classroomRepository);

        activeClass = new Classroom("class-1", "owner-1", "class-1-slug", "Lop hoc 1", "Mo ta");
        activeClass.setStatus("ACTIVE");
        lenient().when(classroomRepository.findById("class-1")).thenReturn(Optional.of(activeClass));
        // R5-04: getFeedPage now gates on AccessPolicy.isClassVisibleToUser before anything else.
        // AccessPolicy is a mock here (unlike the real ProfileVisibilityPolicy above), so every
        // test that reaches getFeedPage needs this to resolve true by default; the R5-04-specific
        // tests below override it back to false to exercise the gate itself.
        lenient().when(accessPolicy.isClassVisibleToUser(any(Classroom.class), any())).thenReturn(true);
    }

    @Test
    @DisplayName("Finding 9: createPost rejects invalid or unknown visibility values")
    void testCreatePostRejectsUnknownVisibility() {
        when(userRepository.findById("author-1")).thenReturn(Optional.of(author));

        CreatePostRequest post = new CreatePostRequest("Title", "Content", "MALFORMED_VISIBILITY", null, null, false);

        AppException ex = assertThrows(AppException.class, () ->
                feedService.createPost("class-1", "author-1", post)
        );
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(postRepository, never()).save(any());
    }

    @Test
    @DisplayName("Create request cannot supply an existing post ID")
    void testCreatePostAlwaysGeneratesServerId() {
        when(userRepository.findById("author-1")).thenReturn(Optional.of(author));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PostDto created = feedService.createPost("class-1", "author-1",
                new CreatePostRequest("Title", "Content", "FREE", null, null, false));
        assertNotNull(created.getId());
        assertFalse(created.getId().isBlank());
        verify(postRepository).save(argThat(post -> "class-1".equals(post.getClassId())
                && "author-1".equals(post.getAuthorId()) && post.getId() != null));
    }

    @Test
    @DisplayName("Finding 9: createPost rejects PRODUCT_OWNER post when target product is from another class")
    void testCreatePostRejectsCrossClassProduct() {
        when(userRepository.findById("author-1")).thenReturn(Optional.of(author));

        CreatePostRequest post = new CreatePostRequest("Title", "Content", "PRODUCT_OWNER", "prod-other-class", null, false);

        Product foreignProduct = new Product("class-2", null, "Foreign Prod", "Desc");
        foreignProduct.setId("prod-other-class");
        when(productRepository.findById("prod-other-class")).thenReturn(Optional.of(foreignProduct));

        AppException ex = assertThrows(AppException.class, () ->
                feedService.createPost("class-1", "author-1", post)
        );
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(postRepository, never()).save(any());
    }

    @Test
    @DisplayName("Finding 9: createPost rejects SEGMENT post when target segment is from another class")
    void testCreatePostRejectsCrossClassSegment() {
        when(userRepository.findById("author-1")).thenReturn(Optional.of(author));

        CreatePostRequest post = new CreatePostRequest("Title", "Content", "SEGMENT", null, "seg-other-class", false);

        Segment foreignSegment = new Segment("class-2", "Foreign Segment", "Desc", "AND", "[]");
        foreignSegment.setId("seg-other-class");
        when(segmentRepository.findById("seg-other-class")).thenReturn(Optional.of(foreignSegment));

        AppException ex = assertThrows(AppException.class, () ->
                feedService.createPost("class-1", "author-1", post)
        );
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(postRepository, never()).save(any());
    }

    @Test
    @DisplayName("Finding 9: getFeedPage denies unknown or malformed visibility by default")
    void testGetFeedPageDeniesUnknownVisibilityByDefault() {
        Post p1 = post("p-1", "author-1", "UNKNOWN_LEVEL", pinnedTime(1));

        stubSingleBatch(List.of(p1));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("student-1", "class-1", "FEED", "VIEW", null)).thenReturn(false);

        FeedPageDto result = feedService.getFeedPage("class-1", "student-1", null, 10);

        // Deny by default: malformed post is excluded from student feed
        assertTrue(result.getPosts().isEmpty());
    }

    @Test
    @DisplayName("Finding 9: getFeedPage denies SEGMENT post when targetSegmentId is null")
    void testGetFeedPageDeniesNullSegmentId() {
        Post p1 = post("p-1", "author-1", "SEGMENT", pinnedTime(1));
        p1.setTargetSegmentId(null);

        stubSingleBatch(List.of(p1));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("student-1", "class-1", "FEED", "VIEW", null)).thenReturn(false);
        when(accessPolicy.isMember("student-1", "class-1")).thenReturn(true);

        FeedPageDto result = feedService.getFeedPage("class-1", "student-1", null, 10);

        // Deny when targetSegmentId is null
        assertTrue(result.getPosts().isEmpty());
    }

    @Test
    @DisplayName("Former class member cannot delete own post without active membership")
    void formerMemberCannotDeleteOwnPost() {
        Post post = new Post("class-1", "author-1", "Title", "Body", "FREE");
        post.setId("post-1");
        when(postRepository.findById("post-1")).thenReturn(Optional.of(post));
        when(accessPolicy.isMember("author-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("author-1", "class-1", "FEED", "DELETE", null)).thenReturn(false);

        assertThrows(AppException.class, () -> feedService.deletePost("post-1", "author-1"));
        verify(postRepository, never()).delete(any(Post.class));
    }

    @Test
    @DisplayName("Class owner may comment on a PRODUCT_OWNER post without owning the product")
    void ownerMayCommentOnProductTargetedPost() {
        Post post = new Post("class-1", "author-1", "Ra mắt", "noi dung", "PRODUCT_OWNER");
        post.setTargetProductId("product-1");
        post.setStatus("PUBLISHED");
        when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));
        when(accessPolicy.isOwner("owner-1", "class-1")).thenReturn(true);
        when(userRepository.findById("owner-1")).thenReturn(Optional.of(author));
        when(commentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertNotNull(feedService.addComment(post.getId(), "owner-1", "Chao cac ban"));
        verify(entitlementRepository, never()).findActiveEntitlements(any(), any(), any());
    }

    @Test
    @DisplayName("Staff with FEED/VIEW may comment on a SEGMENT post they do not belong to")
    void staffMayCommentOnSegmentPost() {
        Post post = new Post("class-1", "author-1", "Nhac nho", "noi dung", "SEGMENT");
        post.setTargetSegmentId("segment-1");
        post.setStatus("PUBLISHED");
        when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));
        when(accessPolicy.isOwner("staff-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("staff-1", "class-1", "FEED", "VIEW", null)).thenReturn(true);
        when(userRepository.findById("staff-1")).thenReturn(Optional.of(author));
        when(commentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertNotNull(feedService.addComment(post.getId(), "staff-1", "Da ghi nhan"));
        verify(segmentService, never()).isUserInSegment(any(), any(), any());
    }

    @Test
    @DisplayName("A plain member outside the segment still cannot comment on a SEGMENT post")
    void memberOutsideSegmentStillBlockedFromCommenting() {
        Post post = new Post("class-1", "author-1", "Nhac nho", "noi dung", "SEGMENT");
        post.setTargetSegmentId("segment-1");
        post.setStatus("PUBLISHED");
        when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("student-1", "class-1", "FEED", "VIEW", null)).thenReturn(false);
        when(accessPolicy.isMember("student-1", "class-1")).thenReturn(true);
        when(segmentService.isUserInSegment("segment-1", "student-1", "class-1")).thenReturn(false);

        AppException ex = assertThrows(AppException.class,
                () -> feedService.addComment(post.getId(), "student-1", "Cho minh hoi"));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    @DisplayName("Post author can edit ordinary posts while visibility and audience targeting remain unchanged")
    void activeAuthorCanEditPostText() {
        Post post = publishedFreePostBy("author-1");
        when(postRepository.findById("post-1")).thenReturn(Optional.of(post));
        when(accessPolicy.isMember("author-1", "class-1")).thenReturn(true);
        when(accessPolicy.canManage("author-1", "class-1", "FEED", "EDIT", null)).thenReturn(false);
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById("author-1")).thenReturn(Optional.of(author));

        PostDto updated = feedService.updatePost("post-1", "author-1", new UpdatePostRequest("Edited", "Updated content"));

        assertEquals("Edited", updated.getTitle());
        assertEquals("Updated content", updated.getContentMarkdown());
        assertEquals("FREE", updated.getVisibility());
    }

    @Test
    @DisplayName("Former staff author cannot edit or delete a targeted post after management permission is revoked")
    void formerStaffCannotManageOwnTargetedPost() {
        Post post = publishedFreePostBy("author-1");
        post.setVisibility("PRODUCT_OWNER");
        post.setTargetProductId("product-1");
        when(postRepository.findById("post-1")).thenReturn(Optional.of(post));
        when(accessPolicy.isMember("author-1", "class-1")).thenReturn(true);
        when(accessPolicy.canManage("author-1", "class-1", "FEED", "EDIT", null)).thenReturn(false);
        when(accessPolicy.canManage("author-1", "class-1", "FEED", "DELETE", null)).thenReturn(false);

        assertEquals(ErrorCode.FORBIDDEN, assertThrows(AppException.class, () -> feedService.updatePost(
                "post-1", "author-1", new UpdatePostRequest("Edited", "Updated content"))).getErrorCode());
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(AppException.class,
                () -> feedService.deletePost("post-1", "author-1")).getErrorCode());
        verify(postRepository, never()).save(any(Post.class));
        verify(postRepository, never()).delete(any(Post.class));
    }

    @Test
    @DisplayName("A different member cannot edit a post or comment")
    void unrelatedMemberCannotEditPostOrComment() {
        Post post = publishedFreePostBy("author-1");
        Comment comment = new Comment("post-1", "author-1", "Original");
        when(postRepository.findById("post-1")).thenReturn(Optional.of(post));
        when(commentRepository.findById(comment.getId())).thenReturn(Optional.of(comment));
        when(accessPolicy.canManage("student-2", "class-1", "FEED", "EDIT", null)).thenReturn(false);

        assertEquals(ErrorCode.FORBIDDEN, assertThrows(AppException.class,
                () -> feedService.updatePost("post-1", "student-2", new UpdatePostRequest("No", "No"))).getErrorCode());
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(AppException.class,
                () -> feedService.updateComment(comment.getId(), "student-2", new UpdateCommentRequest("No"))).getErrorCode());
        verify(postRepository, never()).save(any());
        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Comment author can edit their own comment")
    void activeCommentAuthorCanEditComment() {
        Post post = publishedFreePostBy("author-1");
        Comment comment = new Comment("post-1", "author-1", "Original");
        when(commentRepository.findById(comment.getId())).thenReturn(Optional.of(comment));
        when(postRepository.findById("post-1")).thenReturn(Optional.of(post));
        when(accessPolicy.isMember("author-1", "class-1")).thenReturn(true);
        when(accessPolicy.canManage("author-1", "class-1", "FEED", "EDIT", null)).thenReturn(false);
        when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById("author-1")).thenReturn(Optional.of(author));

        CommentDto updated = feedService.updateComment(comment.getId(), "author-1", new UpdateCommentRequest("Revised"));

        assertEquals("Revised", updated.getContent());
    }

    // --- Cross-endpoint profile privacy (feed must agree with members list and leaderboard) ---

    private static User privateLearner() {
        User u = new User("private-1", "private@test.local", "hash", "Nguyen Van Kin", "USER");
        u.setProfileVisibility("PRIVATE");
        return u;
    }

    private Post publishedFreePostBy(String authorId) {
        Post post = new Post("class-1", authorId, "Tieu de", "Noi dung", "FREE");
        post.setId("post-1");
        post.setStatus("PUBLISHED");
        return post;
    }

    @Test
    @DisplayName("A private learner's identity is hidden from a peer in the feed listing")
    void privateAuthorIsAnonymisedForPeerInFeed() {
        stubSingleBatch(List.of(publishedFreePostBy("private-1")));
        when(accessPolicy.isMember("peer-1", "class-1")).thenReturn(true);
        when(userRepository.findAllById(any())).thenReturn(List.of(privateLearner()));
        when(commentRepository.countByPostIdIn(any())).thenReturn(List.of());
        when(commentRepository.findLatestByPostIds(any(), anyInt())).thenReturn(List.of());

        FeedPageDto result = feedService.getFeedPage("class-1", "peer-1", null, 10);

        assertEquals(1, result.getPosts().size());
        PostDto dto = result.getPosts().get(0);
        assertEquals(ProfileVisibilityPolicy.ANONYMOUS_DISPLAY_NAME, dto.getAuthorName());
        assertNull(dto.getAuthorAvatarUrl());
        assertNull(dto.getAuthorId());
        assertFalse(assertDoesNotThrow(() -> new ObjectMapper().findAndRegisterModules().writeValueAsString(dto)).contains("private-1"));
    }

    @Test
    @DisplayName("A private learner's comment is anonymised for a peer but not for the class owner")
    void privateCommenterIsAnonymisedForPeerOnly() {
        Post post = publishedFreePostBy("author-1");
        Comment comment = new Comment("post-1", "private-1", "Cho minh hoi");
        stubSingleBatch(List.of(post));
        when(commentRepository.findLatestByPostIds(any(), anyInt())).thenReturn(List.of(comment));
        when(userRepository.findAllById(any())).thenReturn(List.of(author, privateLearner()));
        when(commentRepository.countByPostIdIn(any())).thenReturn(List.of());
        when(accessPolicy.isMember("peer-1", "class-1")).thenReturn(true);

        CommentDto seenByPeer = feedService.getFeedPage("class-1", "peer-1", null, 10).getPosts().get(0).getComments().get(0);
        assertEquals(ProfileVisibilityPolicy.ANONYMOUS_DISPLAY_NAME, seenByPeer.getAuthorName());
        assertNull(seenByPeer.getAuthorAvatarUrl());
        assertNull(seenByPeer.getAuthorId());
        assertFalse(assertDoesNotThrow(() -> new ObjectMapper().findAndRegisterModules().writeValueAsString(seenByPeer)).contains("private-1"));

        // The class owner administers the class, so the same policy still shows them the real name
        // — as long as the commenter actually has an ACTIVE membership in it (R4-01). Stub
        // isMember leniently for every id the resolver/policy may query on this path (the viewer,
        // the post's author, and the commenter) so this assertion is about the privacy behaviour,
        // not an incidental strict-stubbing mismatch.
        when(accessPolicy.isOwner("owner-1", "class-1")).thenReturn(true);
        lenient().when(accessPolicy.isMember("owner-1", "class-1")).thenReturn(false);
        lenient().when(accessPolicy.isMember("author-1", "class-1")).thenReturn(false);
        lenient().when(accessPolicy.isMember("private-1", "class-1")).thenReturn(true);
        // R16-07: the class-admin override keys on the commenter having a roster row (any state); R20-03: resolved for the
        // whole page in one batch instead of one query per row.
        when(accessPolicy.usersWithMembershipRecord(eq("class-1"), any())).thenReturn(java.util.Set.of("private-1"));
        CommentDto seenByOwner = feedService.getFeedPage("class-1", "owner-1", null, 10).getPosts().get(0).getComments().get(0);
        assertEquals("Nguyen Van Kin", seenByOwner.getAuthorName());
        assertEquals("private-1", seenByOwner.getAuthorId());
    }

    @Test
    @DisplayName("A private learner still sees their own name on the comment they just posted")
    void privateCommenterSeesOwnIdentityOnCreate() {
        Post post = publishedFreePostBy("author-1");
        when(postRepository.findById("post-1")).thenReturn(Optional.of(post));
        when(accessPolicy.isMember("private-1", "class-1")).thenReturn(true);
        when(userRepository.findById("private-1")).thenReturn(Optional.of(privateLearner()));
        when(commentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CommentDto created = feedService.addComment("post-1", "private-1", "Chao moi nguoi");

        assertEquals("Nguyen Van Kin", created.getAuthorName());
        assertEquals("private-1", created.getAuthorId());
    }

    @Test
    @DisplayName("createPost without contentMarkdown is a 400 contract error, not a 500 at flush")
    void createPostRejectsMissingRequiredFields() {
        CreatePostRequest noContent = new CreatePostRequest("Title", null, "FREE", null, null, false);
        AppException ex = assertThrows(AppException.class,
                () -> feedService.createPost("class-1", "author-1", noContent));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());

        CreatePostRequest noTitle = new CreatePostRequest("  ", "Content", "FREE", null, null, false);
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(AppException.class,
                () -> feedService.createPost("class-1", "author-1", noTitle)).getErrorCode());

        verify(postRepository, never()).save(any());
    }

    @Test
    @DisplayName("addComment without content is a 400 contract error, not a 500 at flush")
    void addCommentRejectsMissingContent() {
        Post post = new Post("class-1", "author-1", "Title", "Content", "PUBLIC");
        post.setId("post-1");
        post.setStatus("PUBLISHED");
        when(postRepository.findById("post-1")).thenReturn(Optional.of(post));

        AppException ex = assertThrows(AppException.class,
                () -> feedService.addComment("post-1", "author-1", null));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());

        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(AppException.class,
                () -> feedService.addComment("post-1", "author-1", "   ")).getErrorCode());

        verify(commentRepository, never()).save(any());
    }

    // --- Helpers for the keyset-paged repository method ---

    /** A post ordered strictly by seconds-from-epoch offset for deterministic keyset ordering in tests. */
    private static Post post(String id, String authorId, String visibility, Instant createdAt) {
        Post p = new Post("class-1", authorId, "Title-" + id, "Body-" + id, visibility);
        p.setId(id);
        p.setStatus("PUBLISHED");
        p.setCreatedAt(createdAt);
        return p;
    }

    private static Instant pinnedTime(int secondsAgo) {
        return Instant.now().minus(secondsAgo, ChronoUnit.SECONDS).truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * Stubs a single keyset batch returned for any cursor position — enough for tests where the
     * batch is smaller than BATCH_SIZE, since the service stops after one short batch regardless
     * of cursor. Always returns the same {@code posts} list so the stub also works for tests that
     * call {@code getFeedPage} more than once (each call restarts its own batch loop from the
     * initial cursor).
     */
    private void stubSingleBatch(List<Post> posts) {
        when(postRepository.findKeysetBatch(eq("class-1"), eq("PUBLISHED"), anyBoolean(), any(Instant.class), anyString(), any(Pageable.class)))
                .thenReturn(posts);
    }

    @Test
    @DisplayName("R5-01: getFeedPage caps the requested page size at 100")
    void getFeedPageCapsPageSizeAtMax() {
        stubSingleBatch(List.of());

        FeedPageDto result = feedService.getFeedPage("class-1", null, null, 500);

        assertTrue(result.getPosts().isEmpty());
        assertFalse(result.isHasNext());
        assertNull(result.getNextCursor());
    }

    @Test
    @DisplayName("R5-01: getFeedPage defaults to size 10 when omitted")
    void getFeedPageDefaultsSize() {
        List<Post> posts = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            posts.add(post("p-" + i, "author-1", "PUBLIC", pinnedTime(i)));
        }
        when(postRepository.findKeysetBatch(eq("class-1"), eq("PUBLISHED"), anyBoolean(), any(Instant.class), anyString(), any(Pageable.class)))
                .thenReturn(posts);
        when(userRepository.findAllById(any())).thenReturn(List.of(author));
        when(commentRepository.countByPostIdIn(any())).thenReturn(List.of());

        FeedPageDto result = feedService.getFeedPage("class-1", null, null, null);

        assertEquals(10, result.getPosts().size());
        assertTrue(result.isHasNext());
    }

    @Test
    @DisplayName("R5-04: getFeedPage throws NOT_FOUND for a nonexistent class")
    void getFeedPageThrowsNotFoundForMissingClass() {
        when(classroomRepository.findById("missing-class")).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class,
                () -> feedService.getFeedPage("missing-class", "user-1", null, 10));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("R5-04: getFeedPage requires login for a non-ACTIVE class when anonymous")
    void getFeedPageRequiresLoginForHiddenClass() {
        activeClass.setStatus("DRAFT");
        when(accessPolicy.isClassVisibleToUser(activeClass, null)).thenReturn(false);

        AppException ex = assertThrows(AppException.class,
                () -> feedService.getFeedPage("class-1", null, null, 10));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    @DisplayName("R5-04: getFeedPage forbids a logged-in non-member from a non-ACTIVE class")
    void getFeedPageForbidsNonMemberFromHiddenClass() {
        activeClass.setStatus("DRAFT");
        when(accessPolicy.isClassVisibleToUser(activeClass, "outsider-1")).thenReturn(false);

        AppException ex = assertThrows(AppException.class,
                () -> feedService.getFeedPage("class-1", "outsider-1", null, 10));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    @DisplayName("R5-01: getFeedPage returns exactly the requested size when more visible posts remain, across ties on createdAt")
    void getFeedPageReturnsExactSizeWithHasNextAcrossTies() {
        // All 5 PUBLIC posts share the exact same createdAt (simulating the second-precision
        // timestamp column) — only the id tie-breaker keeps the ordering (and therefore paging)
        // stable.
        Instant sameInstant = pinnedTime(1);
        List<Post> posts = new java.util.ArrayList<>();
        for (int i = 4; i >= 0; i--) {
            // ids chosen so that id DESC gives a deterministic, non-alphabetical-by-insertion order
            posts.add(post("p-" + i, "author-1", "PUBLIC", sameInstant));
        }
        posts.sort((a, b) -> b.getId().compareTo(a.getId())); // id DESC, matching the repository's ORDER BY

        when(postRepository.findKeysetBatch(eq("class-1"), eq("PUBLISHED"), anyBoolean(), any(Instant.class), anyString(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    boolean cursorPinned = inv.getArgument(2);
                    Instant cursorCreatedAt = inv.getArgument(3);
                    String cursorId = inv.getArgument(4);
                    return posts.stream()
                            .filter(p -> isBefore(p, cursorPinned, cursorCreatedAt, cursorId))
                            .toList();
                });
        when(userRepository.findAllById(any())).thenReturn(List.of(author));
        when(commentRepository.countByPostIdIn(any())).thenReturn(List.of());

        FeedPageDto page0 = feedService.getFeedPage("class-1", null, null, 2);
        assertEquals(2, page0.getPosts().size());
        assertTrue(page0.isHasNext());
        assertNotNull(page0.getNextCursor());
        List<String> page0Ids = page0.getPosts().stream().map(PostDto::getId).toList();

        FeedPageDto page1 = feedService.getFeedPage("class-1", null, page0.getNextCursor(), 2);
        assertEquals(2, page1.getPosts().size());
        assertTrue(page1.isHasNext());
        List<String> page1Ids = page1.getPosts().stream().map(PostDto::getId).toList();

        FeedPageDto page2 = feedService.getFeedPage("class-1", null, page1.getNextCursor(), 2);
        assertEquals(1, page2.getPosts().size());
        assertFalse(page2.isHasNext());
        assertNull(page2.getNextCursor());
        List<String> page2Ids = page2.getPosts().stream().map(PostDto::getId).toList();

        // No id appears twice across pages, and together they cover every post exactly once.
        java.util.Set<String> all = new java.util.HashSet<>();
        all.addAll(page0Ids);
        all.addAll(page1Ids);
        all.addAll(page2Ids);
        assertEquals(5, all.size());
    }

    /** Mirrors the repository's keyset WHERE clause for the in-memory stub above. */
    private static boolean isBefore(Post p, boolean cursorPinned, Instant cursorCreatedAt, String cursorId) {
        if (p.isPinned() != cursorPinned) {
            return !p.isPinned() && cursorPinned;
        }
        int cmpTime = p.getCreatedAt().compareTo(cursorCreatedAt);
        if (cmpTime != 0) {
            return cmpTime < 0;
        }
        return p.getId().compareTo(cursorId) < 0;
    }

    @Test
    @DisplayName("R5-01: getFeedPage advances across multiple underlying batches when many posts are invisible")
    void getFeedPageAdvancesAcrossMultipleBatches() {
        // 250 PRO posts (invisible to this anonymous viewer) followed by 3 PUBLIC posts: with a
        // 200-row internal batch size, filling a page of 2 requires scanning into a second batch.
        List<Post> all = new java.util.ArrayList<>();
        Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (int i = 0; i < 250; i++) {
            all.add(post("pro-" + i, "author-1", "PRO", t.minusSeconds(i)));
        }
        for (int i = 0; i < 3; i++) {
            all.add(post("pub-" + i, "author-1", "PUBLIC", t.minusSeconds(250 + i)));
        }

        when(postRepository.findKeysetBatch(eq("class-1"), eq("PUBLISHED"), anyBoolean(), any(Instant.class), anyString(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    boolean cursorPinned = inv.getArgument(2);
                    Instant cursorCreatedAt = inv.getArgument(3);
                    String cursorId = inv.getArgument(4);
                    int limit = ((Pageable) inv.getArgument(5)).getPageSize();
                    return all.stream()
                            .filter(p -> isBefore(p, cursorPinned, cursorCreatedAt, cursorId))
                            .limit(limit)
                            .toList();
                });
        when(userRepository.findAllById(any())).thenReturn(List.of(author));
        when(commentRepository.countByPostIdIn(any())).thenReturn(List.of());

        FeedPageDto page0 = feedService.getFeedPage("class-1", null, null, 2);

        assertEquals(2, page0.getPosts().size());
        assertTrue(page0.getPosts().stream().allMatch(dto -> "PUBLIC".equals(dto.getVisibility())));
        assertTrue(page0.isHasNext());
        // Verify more than one batch was actually fetched (i.e. the scan crossed a batch boundary).
        verify(postRepository, atLeast(2)).findKeysetBatch(eq("class-1"), eq("PUBLISHED"), anyBoolean(), any(Instant.class), anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("R5-01: getFeedPage skips invisible posts when filling a page so pages stay exact")
    void getFeedPageFiltersInvisiblePostsBeforePaging() {
        // Mixed visibility: only the PUBLIC posts are visible to an anonymous request; PRO posts
        // must not create short/incomplete pages.
        List<Post> posts = new java.util.ArrayList<>();
        Instant t = pinnedTime(0);
        int idx = 0;
        for (int i = 0; i < 3; i++) {
            posts.add(post("pro-" + i, "author-1", "PRO", t.minusSeconds(idx++)));
            posts.add(post("pub-" + i, "author-1", "PUBLIC", t.minusSeconds(idx++)));
        }
        stubSingleBatch(posts);
        when(userRepository.findAllById(any())).thenReturn(List.of(author));
        when(commentRepository.countByPostIdIn(any())).thenReturn(List.of());

        FeedPageDto page0 = feedService.getFeedPage("class-1", null, null, 3);
        assertEquals(3, page0.getPosts().size());
        assertTrue(page0.getPosts().stream().allMatch(dto -> "PUBLIC".equals(dto.getVisibility())));
        assertFalse(page0.isHasNext());
    }

    @Test
    @DisplayName("R5-01: PRODUCT_OWNER entitlements are loaded exactly once per request, not once per post")
    void productOwnerEntitlementsLoadedOncePerRequest() {
        List<Post> posts = new java.util.ArrayList<>();
        Instant t = pinnedTime(0);
        for (int i = 0; i < 5; i++) {
            Post p = post("prod-" + i, "author-1", "PRODUCT_OWNER", t.minusSeconds(i));
            p.setTargetProductId("product-1");
            posts.add(p);
        }
        stubSingleBatch(posts);
        when(accessPolicy.isMember("member-1", "class-1")).thenReturn(true);
        Entitlement ent = new Entitlement("member-1", "class-1", "product-1", null, Instant.now().minusSeconds(10), Instant.now().plusSeconds(1000));
        when(entitlementRepository.findActiveEntitlements(eq("member-1"), eq("class-1"), any(Instant.class)))
                .thenReturn(List.of(ent));
        when(userRepository.findAllById(any())).thenReturn(List.of(author));
        when(commentRepository.countByPostIdIn(any())).thenReturn(List.of());

        FeedPageDto result = feedService.getFeedPage("class-1", "member-1", null, 10);

        assertEquals(5, result.getPosts().size());
        verify(entitlementRepository, times(1)).findActiveEntitlements(eq("member-1"), eq("class-1"), any(Instant.class));
    }

    @Test
    @DisplayName("R5-01: SEGMENT membership is memoized once per distinct segment id per request")
    void segmentMembershipMemoizedPerRequest() {
        List<Post> posts = new java.util.ArrayList<>();
        Instant t = pinnedTime(0);
        for (int i = 0; i < 4; i++) {
            Post p = post("seg-" + i, "author-1", "SEGMENT", t.minusSeconds(i));
            p.setTargetSegmentId("segment-1"); // same segment id on every post
            posts.add(p);
        }
        stubSingleBatch(posts);
        when(accessPolicy.isMember("member-1", "class-1")).thenReturn(true);
        when(segmentService.isUserInSegment("segment-1", "member-1", "class-1")).thenReturn(true);
        when(userRepository.findAllById(any())).thenReturn(List.of(author));
        when(commentRepository.countByPostIdIn(any())).thenReturn(List.of());

        FeedPageDto result = feedService.getFeedPage("class-1", "member-1", null, 10);

        assertEquals(4, result.getPosts().size());
        // Evaluated once, memoized for the other 3 posts targeting the same segment.
        verify(segmentService, times(1)).isUserInSegment("segment-1", "member-1", "class-1");
    }

    // --- R3-02: comment delete authorization ---

    @Test
    @DisplayName("R3-02: comment author who is still an active member can delete their own comment")
    void activeAuthorCanDeleteOwnComment() {
        Post post = publishedFreePostBy("author-2");
        Comment comment = new Comment("post-1", "author-1", "Content");
        when(commentRepository.findById(comment.getId())).thenReturn(Optional.of(comment));
        when(postRepository.findById("post-1")).thenReturn(Optional.of(post));
        when(accessPolicy.isMember("author-1", "class-1")).thenReturn(true);

        feedService.deleteComment(comment.getId(), "author-1");

        verify(commentRepository).delete(comment);
    }

    @Test
    @DisplayName("R3-02: a non-author, non-staff member cannot delete someone else's comment")
    void nonAuthorMemberCannotDeleteComment() {
        Post post = publishedFreePostBy("author-1");
        Comment comment = new Comment("post-1", "author-1", "Content");
        when(commentRepository.findById(comment.getId())).thenReturn(Optional.of(comment));
        when(postRepository.findById("post-1")).thenReturn(Optional.of(post));
        when(accessPolicy.canManage("student-2", "class-1", "FEED", "DELETE", null)).thenReturn(false);

        AppException ex = assertThrows(AppException.class,
                () -> feedService.deleteComment(comment.getId(), "student-2"));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(commentRepository, never()).delete(any(Comment.class));
    }

    @Test
    @DisplayName("R3-02: owner/staff with FEED:DELETE can delete any comment")
    void staffWithGrantCanDeleteComment() {
        Post post = publishedFreePostBy("author-1");
        Comment comment = new Comment("post-1", "author-1", "Content");
        when(commentRepository.findById(comment.getId())).thenReturn(Optional.of(comment));
        when(postRepository.findById("post-1")).thenReturn(Optional.of(post));
        when(accessPolicy.canManage("staff-1", "class-1", "FEED", "DELETE", null)).thenReturn(true);

        feedService.deleteComment(comment.getId(), "staff-1");

        verify(commentRepository).delete(comment);
    }

    @Test
    @DisplayName("R3-02: a former member cannot delete their own comment after leaving the class")
    void formerMemberCannotDeleteOwnComment() {
        Post post = publishedFreePostBy("author-1");
        Comment comment = new Comment("post-1", "author-1", "Content");
        when(commentRepository.findById(comment.getId())).thenReturn(Optional.of(comment));
        when(postRepository.findById("post-1")).thenReturn(Optional.of(post));
        when(accessPolicy.isMember("author-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("author-1", "class-1", "FEED", "DELETE", null)).thenReturn(false);

        assertThrows(AppException.class, () -> feedService.deleteComment(comment.getId(), "author-1"));
        verify(commentRepository, never()).delete(any(Comment.class));
    }

    @Test
    @DisplayName("R3-01: anonymous (no userId) GET posts excludes comments on a PUBLIC post")
    void anonymousViewerGetsNoComments() {
        Post publicPost = new Post("class-1", "author-1", "Title", "Body", "PUBLIC");
        publicPost.setId("post-1");
        publicPost.setStatus("PUBLISHED");
        stubSingleBatch(List.of(publicPost));
        when(userRepository.findAllById(any())).thenReturn(List.of(author));
        when(commentRepository.countByPostIdIn(any())).thenReturn(List.of());

        FeedPageDto result = feedService.getFeedPage("class-1", null, null, 10);

        assertEquals(1, result.getPosts().size());
        assertNull(result.getPosts().get(0).getComments());
        verify(commentRepository, never()).findLatestByPostIds(any(), anyInt());
    }

    @Test
    @DisplayName("R3-01: anonymous (no userId) GET posts excludes non-PUBLIC posts")
    void anonymousViewerOnlySeesPublicPosts() {
        Post freePost = new Post("class-1", "author-1", "Title", "Body", "FREE");
        freePost.setId("post-free");
        freePost.setStatus("PUBLISHED");
        stubSingleBatch(List.of(freePost));

        FeedPageDto result = feedService.getFeedPage("class-1", null, null, 10);

        assertTrue(result.getPosts().isEmpty());
    }

    @Test
    @DisplayName("R3-01: an active member sees comments included in the feed listing")
    void memberViewerGetsComments() {
        Post freePost = new Post("class-1", "author-1", "Title", "Body", "FREE");
        freePost.setId("post-1");
        freePost.setStatus("PUBLISHED");
        stubSingleBatch(List.of(freePost));
        when(accessPolicy.isMember("member-1", "class-1")).thenReturn(true);
        when(userRepository.findAllById(any())).thenReturn(List.of(author));
        when(commentRepository.countByPostIdIn(any())).thenReturn(List.of());
        when(commentRepository.findLatestByPostIds(any(), anyInt())).thenReturn(List.of());

        FeedPageDto result = feedService.getFeedPage("class-1", "member-1", null, 10);

        assertEquals(1, result.getPosts().size());
        assertNotNull(result.getPosts().get(0).getComments());
    }

    @Test
    @DisplayName("R3-01: a plain member cannot create a PUBLIC post without a FEED:CREATE grant")
    void plainMemberCannotCreatePublicPost() {
        when(userRepository.findById("student-1")).thenReturn(Optional.of(author));
        doThrow(new AppException(ErrorCode.FORBIDDEN, "Không có quyền"))
                .when(accessPolicy).enforceManage("student-1", "class-1", "FEED", "CREATE", null);

        CreatePostRequest post = new CreatePostRequest("Title", "Content", "PUBLIC", null, null, false);

        AppException ex = assertThrows(AppException.class, () -> feedService.createPost("class-1", "student-1", post));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(postRepository, never()).save(any());
    }

    @Test
    @DisplayName("R3-01: an owner/staff with FEED:CREATE can create a PUBLIC post")
    void ownerCanCreatePublicPost() {
        when(userRepository.findById("owner-1")).thenReturn(Optional.of(author));
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));

        CreatePostRequest post = new CreatePostRequest("Title", "Content", "PUBLIC", null, null, false);
        PostDto created = feedService.createPost("class-1", "owner-1", post);

        assertEquals("PUBLIC", created.getVisibility());
        verify(accessPolicy).enforceManage("owner-1", "class-1", "FEED", "CREATE", null);
        verify(postRepository).save(any(Post.class));
    }
}
