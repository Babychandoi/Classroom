package com.classroom.modules.community.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.model.Product;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.community.dto.PostDto;
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

    private FeedService feedService;

    private User author;

    @BeforeEach
    void setUp() {
        author = new User("author-1", "author@test.local", "hash", "Teacher One", "STAFF");
        // The visibility policy is exercised for real (over the mocked AccessPolicy) rather than
        // mocked, so the privacy assertions below test the behaviour the feed actually ships.
        feedService = new FeedService(postRepository, commentRepository, userRepository, accessPolicy,
                proPolicy, entitlementRepository, productRepository, segmentRepository, segmentService,
                new ProfileVisibilityPolicy(accessPolicy));
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
    @DisplayName("Finding 9: getFeedPosts denies unknown or malformed visibility by default")
    void testGetFeedPostsDeniesUnknownVisibilityByDefault() {
        Post p1 = new Post("class-1", "author-1", "Title", "Hacked Post", "UNKNOWN_LEVEL");
        p1.setId("p-1");
        p1.setStatus("PUBLISHED");

        when(postRepository.findByClassIdAndStatusOrderByPinnedDescCreatedAtDesc("class-1", "PUBLISHED"))
                .thenReturn(List.of(p1));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("student-1", "class-1", "FEED", "VIEW", null)).thenReturn(false);

        List<PostDto> result = feedService.getFeedPosts("class-1", "student-1");

        // Deny by default: malformed post is excluded from student feed
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Finding 9: getFeedPosts denies SEGMENT post when targetSegmentId is null")
    void testGetFeedPostsDeniesNullSegmentId() {
        Post p1 = new Post("class-1", "author-1", "Title", "Segment Post", "SEGMENT");
        p1.setId("p-1");
        p1.setStatus("PUBLISHED");
        p1.setTargetSegmentId(null);

        when(postRepository.findByClassIdAndStatusOrderByPinnedDescCreatedAtDesc("class-1", "PUBLISHED"))
                .thenReturn(List.of(p1));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("student-1", "class-1", "FEED", "VIEW", null)).thenReturn(false);

        List<PostDto> result = feedService.getFeedPosts("class-1", "student-1");

        // Deny when targetSegmentId is null
        assertTrue(result.isEmpty());
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
        when(postRepository.findByClassIdAndStatusOrderByPinnedDescCreatedAtDesc("class-1", "PUBLISHED"))
                .thenReturn(List.of(publishedFreePostBy("private-1")));
        when(accessPolicy.isMember("peer-1", "class-1")).thenReturn(true);
        when(userRepository.findById("private-1")).thenReturn(Optional.of(privateLearner()));
        when(commentRepository.findByPostIdOrderByCreatedAtAsc("post-1")).thenReturn(List.of());

        List<PostDto> result = feedService.getFeedPosts("class-1", "peer-1");

        assertEquals(1, result.size());
        PostDto dto = result.get(0);
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
        when(postRepository.findByClassIdAndStatusOrderByPinnedDescCreatedAtDesc("class-1", "PUBLISHED"))
                .thenReturn(List.of(post));
        when(commentRepository.findByPostIdOrderByCreatedAtAsc("post-1")).thenReturn(List.of(comment));
        when(userRepository.findById("author-1")).thenReturn(Optional.of(author));
        when(userRepository.findById("private-1")).thenReturn(Optional.of(privateLearner()));
        when(accessPolicy.isMember("peer-1", "class-1")).thenReturn(true);

        CommentDto seenByPeer = feedService.getFeedPosts("class-1", "peer-1").get(0).getComments().get(0);
        assertEquals(ProfileVisibilityPolicy.ANONYMOUS_DISPLAY_NAME, seenByPeer.getAuthorName());
        assertNull(seenByPeer.getAuthorAvatarUrl());
        assertNull(seenByPeer.getAuthorId());
        assertFalse(assertDoesNotThrow(() -> new ObjectMapper().findAndRegisterModules().writeValueAsString(seenByPeer)).contains("private-1"));

        // The class owner administers the class, so the same policy still shows them the real name.
        when(accessPolicy.isOwner("owner-1", "class-1")).thenReturn(true);
        CommentDto seenByOwner = feedService.getFeedPosts("class-1", "owner-1").get(0).getComments().get(0);
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
}
