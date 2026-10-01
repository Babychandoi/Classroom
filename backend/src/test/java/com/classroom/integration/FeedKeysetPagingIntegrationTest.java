package com.classroom.integration;

import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.community.dto.FeedPageDto;
import com.classroom.modules.community.dto.PostDto;
import com.classroom.modules.community.model.Post;
import com.classroom.modules.community.repository.PostRepository;
import com.classroom.modules.community.service.FeedService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R6-02 coverage gap: exercises {@link FeedService#getFeedPage} keyset paging against a real
 * MySQL database (not H2), because the reported production bug — a cursor whose decoded
 * {@code createdAt} exceeds what a SQL TIMESTAMP column can store — only reproduces against the
 * real driver's binding behaviour.
 *
 * <p>Builds an isolated class with posts sharing an identical {@code created_at} second (forcing
 * the keyset tie-breaker on id to do real work), a mix of pinned/unpinned rows, and mixed
 * visibilities (PUBLIC/FREE/PRO/PRODUCT_OWNER/SEGMENT-shaped posts are approximated with
 * PUBLIC/FREE/PRO since PRODUCT_OWNER/SEGMENT require additional fixtures out of scope here —
 * the visibility *filtering* itself is already covered by FeedSecurityTest; this test's job is
 * the keyset walk). Walks every page for an anonymous viewer and for a member viewer, checking no
 * post is duplicated or skipped, and that the very first page (no cursor) works.</p>
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
class FeedKeysetPagingIntegrationTest {

    @Autowired
    private ClassroomRepository classroomRepository;
    @Autowired
    private ClassMemberRepository memberRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private FeedService feedService;

    @Test
    @DisplayName("R6-02: keyset paging walks every page with no duplicates/missing posts, for anonymous and member viewers")
    void keysetPagingWalksAllPagesWithoutDuplicatesOrGaps() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // The owner must exist before the classroom row (fk_class_owner references users.id).
        User owner = userRepository.save(new User(null, "owner-" + suffix + "@test.local",
                "hash", "Owner " + suffix, "USER"));
        User member = userRepository.save(new User(null, "member-" + suffix + "@test.local",
                "hash", "Member " + suffix, "USER"));

        Classroom classroom = classroomRepository.save(
                new Classroom(null, owner.getId(), "feed-keyset-" + suffix, "Feed Keyset Test " + suffix, "desc"));

        memberRepository.save(new ClassMember(classroom.getId(), member.getId(), "STUDENT"));

        // Every post shares the same created_at second so the (pinned, createdAt) prefix of the
        // keyset ordering ties, forcing the id tie-breaker to actually do the work.
        Instant sharedInstant = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        List<Post> created = new ArrayList<>();
        // Pinned, PUBLIC (visible to anonymous + member)
        for (int i = 0; i < 3; i++) {
            created.add(savePost(classroom.getId(), owner.getId(), "Pinned Public " + i, "PUBLIC", true, sharedInstant));
        }
        // Unpinned, PUBLIC (visible to anonymous + member)
        for (int i = 0; i < 4; i++) {
            created.add(savePost(classroom.getId(), owner.getId(), "Public " + i, "PUBLIC", false, sharedInstant));
        }
        // Unpinned, FREE (member-only, hidden from anonymous)
        for (int i = 0; i < 5; i++) {
            created.add(savePost(classroom.getId(), owner.getId(), "Free " + i, "FREE", false, sharedInstant));
        }
        // Unpinned, PRO (member+PRO only, hidden from anonymous and from this plain member)
        for (int i = 0; i < 2; i++) {
            created.add(savePost(classroom.getId(), owner.getId(), "Pro " + i, "PRO", false, sharedInstant));
        }

        assertEquals(14, created.size());

        Set<String> anonymousExpectedIds = created.stream()
                .filter(p -> "PUBLIC".equals(p.getVisibility()))
                .map(Post::getId).collect(java.util.stream.Collectors.toSet());
        Set<String> memberExpectedIds = created.stream()
                .filter(p -> !"PRO".equals(p.getVisibility()))
                .map(Post::getId).collect(java.util.stream.Collectors.toSet());

        assertEquals(7, anonymousExpectedIds.size());
        assertEquals(12, memberExpectedIds.size());

        // Small page size so we're forced through several pages, including a boundary that falls
        // mid-tie on the shared createdAt second.
        int pageSize = 3;

        List<PostDto> anonymousAll = walkAllPages(classroom.getId(), null, pageSize);
        assertNoDuplicatesAndMatches(anonymousAll, anonymousExpectedIds);
        assertPinnedPostsComeFirst(anonymousAll);

        List<PostDto> memberAll = walkAllPages(classroom.getId(), member.getId(), pageSize);
        assertNoDuplicatesAndMatches(memberAll, memberExpectedIds);
        assertPinnedPostsComeFirst(memberAll);

        // First page with no cursor must work and return a sensible non-empty page.
        FeedPageDto firstPage = feedService.getFeedPage(classroom.getId(), null, null, pageSize);
        assertEquals(pageSize, firstPage.getPosts().size());
        assertTrue(firstPage.isHasNext());
        assertTrue(firstPage.getPosts().get(0).isPinned(), "First page must start with pinned posts");
    }

    private Post savePost(String classId, String authorId, String title, String visibility, boolean pinned, Instant createdAt) {
        Post post = new Post(classId, authorId, title, "content for " + title, visibility);
        post.setPinned(pinned);
        post.setStatus("PUBLISHED");
        post.setCreatedAt(createdAt);
        return postRepository.save(post);
    }

    /** Walks every page via the returned cursor until hasNext is false, collecting all posts seen. */
    private List<PostDto> walkAllPages(String classId, String userId, int pageSize) {
        List<PostDto> all = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        while (true) {
            FeedPageDto page = feedService.getFeedPage(classId, userId, cursor, pageSize);
            all.addAll(page.getPosts());
            if (!page.isHasNext()) {
                assertNull(page.getNextCursor(), "nextCursor must be null once hasNext is false");
                break;
            }
            cursor = page.getNextCursor();
            guard++;
            assertTrue(guard < 50, "Too many pages — likely an infinite-loop regression in keyset paging");
        }
        return all;
    }

    private void assertNoDuplicatesAndMatches(List<PostDto> all, Set<String> expectedIds) {
        List<String> ids = all.stream().map(PostDto::getId).toList();
        Set<String> uniqueIds = new HashSet<>(ids);
        assertEquals(ids.size(), uniqueIds.size(), "Keyset paging must never return the same post twice across pages");
        assertEquals(expectedIds, uniqueIds, "Keyset paging must return exactly the visible posts, none missing");
    }

    private void assertPinnedPostsComeFirst(List<PostDto> all) {
        boolean sawUnpinned = false;
        for (PostDto p : all) {
            if (!p.isPinned()) {
                sawUnpinned = true;
            } else {
                assertFalse(sawUnpinned, "A pinned post must never appear after an unpinned one across the full walk");
            }
        }
    }
}
