package com.classroom.integration;

import com.classroom.modules.classroom.dto.ClassMemberDto;
import com.classroom.modules.classroom.dto.ClassMemberPageDto;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.service.ClassroomService;
import com.classroom.modules.classroom.service.MemberService;
import com.classroom.modules.community.dto.CommentPageDto;
import com.classroom.modules.community.dto.FeedPageDto;
import com.classroom.modules.community.dto.PostDto;
import com.classroom.modules.community.model.Comment;
import com.classroom.modules.community.model.Post;
import com.classroom.modules.community.repository.CommentRepository;
import com.classroom.modules.community.repository.PostRepository;
import com.classroom.modules.community.service.FeedService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.ranking.dto.LeaderboardEntryDto;
import com.classroom.modules.ranking.model.LeaderboardEntry;
import com.classroom.modules.ranking.repository.LeaderboardEntryRepository;
import com.classroom.modules.ranking.service.LeaderboardService;
import io.minio.MinioClient;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R20-03 regression: the read models that used to cost hundreds or thousands of SQL statements per request must stay at a small,
 * FIXED number of statements no matter how many comments, authors or members they cover.
 *
 * <p>Measured before the fix on a real class (drill): a 10-post feed page = 819 statements (631 on staff_assignments alone, one
 * {@code findById} per comment, one comments query per post), a 50-post page = 3 738, the Studio member list of a 2 000-member
 * class = 6 011. Every assertion below is orders of magnitude under those numbers, so the old code cannot pass them: it issued
 * 10 posts x (1 comments query + 20 x user lookup + 3 x N policy queries) - well over a hundred statements - for exactly the data
 * set built here. The "control" test proves the counter really sees the per-row policy queries the old code used.</p>
 *
 * <p>Runs on the in-memory database of the default profile: the number of statements is a property of the code, not of the engine.
 * Statements are counted by Hibernate {@link Statistics} (every {@code prepareStatement}, native queries included).</p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.classroom.integration.QueryCountRegressionTest$CountingInspector")
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class QueryCountRegressionTest {

    /** Generous ceiling for a page (measured: about a dozen); the pre-fix code needed 853 for the same page. */
    private static final int FEED_PAGE_MAX_STATEMENTS = 15;
    private static final int LISTING_MAX_STATEMENTS = 12;

    @Autowired private UserRepository userRepository;
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private ClassMemberRepository memberRepository;
    @Autowired private PostRepository postRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private LeaderboardEntryRepository leaderboardEntryRepository;
    @Autowired private FeedService feedService;
    @Autowired private MemberService memberService;
    @Autowired private ClassroomService classroomService;
    @Autowired private LeaderboardService leaderboardService;
    @Autowired private ProfileVisibilityPolicy profileVisibilityPolicy;

    @MockBean(name = "minioClient") private MinioClient minioClient;
    @MockBean(name = "minioPresigningClient") private MinioClient minioPresigningClient;

    private String classId;
    private User owner;
    private User viewer;
    private final List<User> members = new ArrayList<>();
    private final List<String> postIds = new ArrayList<>();

    @BeforeAll
    void seed() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        owner = userRepository.save(new User(null, "qc.owner." + run + "@test.local", "hash", "QC Owner", "USER"));
        Classroom c = new Classroom();
        c.setOwnerId(owner.getId());
        c.setSlug("qc-class-" + run);
        c.setTitle("Query count class");
        c.setStatus("ACTIVE");
        classId = classroomRepository.save(c).getId();
        memberRepository.save(new ClassMember(classId, owner.getId(), "OWNER"));

        // 20 distinct authors/commenters with every profile-visibility setting, all ACTIVE members
        String[] visibilities = {"PUBLIC", "CLASS", "PRIVATE"};
        for (int i = 0; i < 20; i++) {
            User u = new User(null, "qc.m" + i + "." + run + "@test.local", "hash", "QC Member " + i, "USER");
            u.setProfileVisibility(visibilities[i % 3]);
            members.add(userRepository.save(u));
        }
        memberRepository.saveAll(members.stream().map(u -> new ClassMember(classId, u.getId(), "STUDENT")).toList());
        viewer = members.get(0);

        // 10 posts x 20 comments, every comment by a different one of the 20 authors
        fillWithPosts(classId, 20, postIds);
    }

    /** A second class with the SAME 20 people as members, 10 posts, {@code commentsPerPost} comments under each. */
    private String buildClass(String tag, int commentsPerPost) {
        Classroom c = new Classroom();
        c.setOwnerId(owner.getId());
        c.setSlug("qc-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8));
        c.setTitle("Query count " + tag);
        c.setStatus("ACTIVE");
        String id = classroomRepository.save(c).getId();
        memberRepository.save(new ClassMember(id, owner.getId(), "OWNER"));
        memberRepository.saveAll(members.stream().map(u -> new ClassMember(id, u.getId(), "STUDENT")).toList());
        fillWithPosts(id, commentsPerPost, new ArrayList<>());
        return id;
    }

    private void fillWithPosts(String targetClassId, int commentsPerPost, List<String> collectIds) {
        Instant base = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        for (int p = 0; p < 10; p++) {
            Post post = new Post(targetClassId, members.get(p % 20).getId(), "Post " + p, "Body " + p, "FREE");
            post.setId(UUID.randomUUID().toString());
            post.setStatus("PUBLISHED");
            post.setCreatedAt(base.plusSeconds(p * 60L));
            postRepository.save(post);
            collectIds.add(post.getId());
            addComments(post.getId(), commentsPerPost, base.plusSeconds(p * 60L + 1), 0);
        }
    }

    private void addComments(String postId, int count, Instant from, int authorOffset) {
        List<Comment> batch = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            Comment cm = new Comment(postId, members.get((k + authorOffset) % 20).getId(), "Binh luan " + k);
            cm.setCreatedAt(from.plusSeconds(k));
            batch.add(cm);
        }
        commentRepository.saveAll(batch);
    }

    public static class CountingInspector implements StatementInspector {
        static final ThreadLocal<Long> COUNT = new ThreadLocal<>();
        @Override public String inspect(String sql) {
            Long current = COUNT.get();
            if (current != null) COUNT.set(current + 1);
            return sql;
        }
    }

    /** Count this caller's SQL, excluding unrelated scheduler threads; preserve the exact N+1 assertions. */
    private long statementsOf(Supplier<?> work) {
        CountingInspector.COUNT.set(0L);
        try {
            work.get();
            long n = CountingInspector.COUNT.get();
            System.out.println("[query-count] " + Thread.currentThread().getStackTrace()[2].getMethodName() + " = " + n + " statements");
            return n;
        } finally { CountingInspector.COUNT.remove(); }
    }

    @Test
    @DisplayName("A feed page (10 posts x 20 comments x 20 authors) costs a fixed dozen statements for a plain member")
    void feedPageForMemberIsFewStatements() {
        FeedPageDto warm = feedService.getFeedPage(classId, viewer.getId(), null, 10);
        assertEquals(10, warm.getPosts().size());

        long statements = statementsOf(() -> feedService.getFeedPage(classId, viewer.getId(), null, 10));

        assertTrue(statements <= FEED_PAGE_MAX_STATEMENTS,
                "feed page took " + statements + " statements, expected <= " + FEED_PAGE_MAX_STATEMENTS + " (pre-fix: 853)");
    }

    @Test
    @DisplayName("The class OWNER's page (privacy override + membership lookups) is just as cheap")
    void feedPageForOwnerIsFewStatements() {
        feedService.getFeedPage(classId, owner.getId(), null, 10);

        long statements = statementsOf(() -> feedService.getFeedPage(classId, owner.getId(), null, 10));

        assertTrue(statements <= FEED_PAGE_MAX_STATEMENTS, "owner feed page took " + statements + " statements");
    }

    @Test
    @DisplayName("Feed page content: latest 3 comments per post oldest-first, total in commentCount, identities still anonymised")
    void feedPageEmbedsOnlyTheLatestComments() {
        FeedPageDto page = feedService.getFeedPage(classId, viewer.getId(), null, 10);

        for (PostDto post : page.getPosts()) {
            assertEquals(20, post.getCommentCount());
            assertEquals(FeedService.EMBEDDED_COMMENTS_PER_POST, post.getComments().size());
            // the embedded ones are the NEWEST three (comment 17, 18, 19), in ascending order
            assertEquals(List.of("Binh luan 17", "Binh luan 18", "Binh luan 19"),
                    post.getComments().stream().map(cm -> cm.getContent()).toList());
        }
        // authors 2, 5, 8 ... are PRIVATE: hidden from a peer; the viewer's own PUBLIC/CLASS neighbours stay visible
        assertTrue(page.getPosts().stream().flatMap(p -> p.getComments().stream())
                .anyMatch(cm -> ProfileVisibilityPolicy.ANONYMOUS_DISPLAY_NAME.equals(cm.getAuthorName()) && cm.getAuthorId() == null));
        assertTrue(page.getPosts().stream().flatMap(p -> p.getComments().stream())
                .anyMatch(cm -> cm.getAuthorId() != null && !ProfileVisibilityPolicy.ANONYMOUS_DISPLAY_NAME.equals(cm.getAuthorName())));
    }

    @Test
    @DisplayName("The statement count does not grow with the number of comments")
    void feedPageCostIsIndependentOfCommentCount() {
        feedService.getFeedPage(classId, viewer.getId(), null, 10);
        long twenty = statementsOf(() -> feedService.getFeedPage(classId, viewer.getId(), null, 10));

        // the same 10 posts and 20 people, but 80 comments under every post
        String heavyClass = buildClass("heavy", 80);
        feedService.getFeedPage(heavyClass, viewer.getId(), null, 10);
        long eighty = statementsOf(() -> feedService.getFeedPage(heavyClass, viewer.getId(), null, 10));

        assertEquals(twenty, eighty, "a page with 80 comments per post must cost the same as one with 20");
    }

    @Test
    @DisplayName("'Xem thêm bình luận' pages backwards through the older comments without gaps or repeats")
    void olderCommentsArePagedBackwards() {
        String postId = postIds.get(0);
        FeedPageDto page = feedService.getFeedPage(classId, viewer.getId(), null, 10);
        PostDto post = page.getPosts().stream().filter(p -> p.getId().equals(postId)).findFirst().orElseThrow();
        long total = post.getCommentCount();
        List<String> seen = new ArrayList<>(post.getComments().stream().map(cm -> cm.getId()).toList());
        String before = post.getComments().get(0).getId();

        CommentPageDto older;
        int guard = 0;
        do {
            older = feedService.listComments(postId, viewer.getId(), before, 7);
            assertTrue(older.getComments().size() <= 7);
            for (var cm : older.getComments()) {
                assertFalse(seen.contains(cm.getId()), "comment repeated across pages");
                seen.add(cm.getId());
            }
            before = older.getNextBefore();
        } while (older.isHasMore() && ++guard < 100);

        assertEquals(total, seen.size(), "every comment of the post is reachable exactly once");
    }

    @Test
    @DisplayName("Studio member list: a fixed handful of statements for a page of a 300+ member class, with paging and search")
    void studioMembersIsFewStatementsAndPaged() {
        // grow the class to 320 members
        String run = UUID.randomUUID().toString().substring(0, 6);
        List<User> more = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            more.add(new User(null, "qc.bulk" + i + "." + run + "@test.local", "hash", "Bulk Member " + i, "USER"));
        }
        userRepository.saveAll(more);
        memberRepository.saveAll(more.stream().map(u -> new ClassMember(classId, u.getId(), "STUDENT")).toList());

        memberService.getStudioMembers(classId, owner.getId());
        long statements = statementsOf(() -> memberService.getStudioMembers(classId, owner.getId(), 0, 50, null, null, null));
        assertTrue(statements <= LISTING_MAX_STATEMENTS,
                "studio members page took " + statements + " statements (pre-fix: ~3 per member of the WHOLE class)");

        ClassMemberPageDto first = memberService.getStudioMembers(classId, owner.getId(), 0, 50, null, null, null);
        assertEquals(50, first.getMembers().size());
        assertEquals(321, first.getTotal());
        assertTrue(first.isHasNext());
        ClassMemberDto ownerRow = memberService.getStudioMembers(classId, owner.getId(), 0, 50, null, "ACTIVE", "OWNER")
                .getMembers().get(0);
        assertTrue(ownerRow.isPro(), "the owner is PRO in their own class");
        assertEquals(owner.getEmail(), ownerRow.getUserEmail());

        ClassMemberPageDto last = memberService.getStudioMembers(classId, owner.getId(), 6, 50, null, null, null);
        assertEquals(21, last.getMembers().size());
        assertFalse(last.isHasNext());

        ClassMemberPageDto capped = memberService.getStudioMembers(classId, owner.getId(), 0, 100000, null, null, null);
        assertEquals(MemberService.STUDIO_MEMBERS_MAX_SIZE, capped.getMembers().size());

        ClassMemberPageDto found = memberService.getStudioMembers(classId, owner.getId(), 0, 50, "bulk member 29", null, null);
        assertEquals(11, found.getTotal()); // "Bulk Member 29", "Bulk Member 290".."Bulk Member 299"
        ClassMemberPageDto owners = memberService.getStudioMembers(classId, owner.getId(), 0, 50, null, "ACTIVE", "OWNER");
        assertEquals(1, owners.getTotal());
    }

    @Test
    @DisplayName("Class member list and leaderboard stay at a few statements however many people are listed")
    void classMemberListAndLeaderboardAreFewStatements() {
        for (User u : members) {
            leaderboardEntryRepository.save(new LeaderboardEntry(classId, u.getId(), 10, "Bronze"));
        }
        classroomService.getClassMembers(classId, viewer.getId());
        long membersStatements = statementsOf(() -> classroomService.getClassMembers(classId, viewer.getId()));
        assertTrue(membersStatements <= LISTING_MAX_STATEMENTS, "getClassMembers took " + membersStatements);

        List<LeaderboardEntryDto> board = leaderboardService.getLeaderboard(classId, viewer.getId());
        assertEquals(20, board.size());
        long boardStatements = statementsOf(() -> leaderboardService.getLeaderboard(classId, viewer.getId()));
        assertTrue(boardStatements <= LISTING_MAX_STATEMENTS, "getLeaderboard took " + boardStatements);
    }

    @Test
    @DisplayName("Control: the per-call policy API the old feed used really costs statements per row, so this counter would have failed it")
    void perCallPolicyApiIsTheOldNPlusOne() {
        var users = userRepository.findAllById(members.stream().map(User::getId).toList());
        long statements = statementsOf(() -> {
            // exactly what FeedService did for every post author and comment author: a fresh viewer context per call
            int visible = 0;
            for (int round = 0; round < 3; round++) {
                for (User u : users) {
                    if (profileVisibilityPolicy.isIdentityVisible(u, viewer.getId(), classId)) visible++;
                }
            }
            return visible;
        });
        assertTrue(statements > 20, "the control must observe the per-row queries; saw " + statements);
    }
}
