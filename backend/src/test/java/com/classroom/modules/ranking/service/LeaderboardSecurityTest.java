package com.classroom.modules.ranking.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.exam.repository.ExamRepository;
import com.classroom.modules.exam.model.Exam;
import com.classroom.modules.exam.model.ExamAttempt;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.ranking.dto.LeaderboardEntryDto;
import com.classroom.modules.ranking.model.LeaderboardEntry;
import com.classroom.modules.ranking.repository.ExamRewardRuleRepository;
import com.classroom.modules.ranking.repository.LeaderboardEntryRepository;
import com.classroom.modules.ranking.repository.LeaderboardRecalcJobRepository;
import com.classroom.modules.ranking.repository.RankTierRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class LeaderboardSecurityTest {

    @Mock
    private LeaderboardEntryRepository leaderboardRepository;
    @Mock
    private LeaderboardRecalcJobRepository recalcJobRepository;
    @Mock
    private RankTierRepository rankTierRepository;
    @Mock
    private ExamRewardRuleRepository rewardRuleRepository;
    @Mock
    private ExamAttemptRepository attemptRepository;
    @Mock
    private ExamRepository examRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private ClassMemberRepository memberRepository;
    @Mock
    private com.classroom.modules.audit.service.AuditService auditService;

    private LeaderboardService leaderboardService;

    @BeforeEach
    void setUp() {
        // A real visibility policy over the mocked AccessPolicy, so the leaderboard is filtered
        // by the same rule the profile endpoint uses.
        leaderboardService = new LeaderboardService(leaderboardRepository, recalcJobRepository,
                rankTierRepository, rewardRuleRepository, attemptRepository, examRepository,
                userRepository, accessPolicy, new ProfileVisibilityPolicy(accessPolicy), memberRepository,
                auditService, null);
    }

    @Test
    @DisplayName("Finding 13: getLeaderboard rejects unauthenticated or non-member callers")
    void testGetLeaderboardRejectsNonMember() {
        doThrow(new AppException(ErrorCode.FORBIDDEN, "Bạn không phải là thành viên của lớp học này"))
                .when(accessPolicy).enforceMember("attacker-1", "class-1");

        AppException ex = assertThrows(AppException.class, () ->
                leaderboardService.getLeaderboard("class-1", "attacker-1")
        );
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(leaderboardRepository, never()).findByClassIdOrderByTotalPointsDesc(any());
    }

    @Test
    @DisplayName("Finding 13: getLeaderboard returns ranking list for verified class member")
    void testGetLeaderboardAllowedForMember() {
        doNothing().when(accessPolicy).enforceMember("student-1", "class-1");

        LeaderboardEntry entry = new LeaderboardEntry("class-1", "student-1", 150, "Bạc");
        entry.setLastCalculatedAt(Instant.now());
        when(memberRepository.findActiveUserIdsByClassId("class-1")).thenReturn(List.of("student-1"));
        when(leaderboardRepository.findByClassIdOrderByTotalPointsDesc("class-1"))
                .thenReturn(List.of(entry));

        User user = new User("student-1", "student@test.local", "hash", "Nguyen Van B", "STUDENT");
        user.setAvatarUrl("https://avatar.test/2.png");
        when(userRepository.findAllById(any())).thenReturn(List.of(user));

        List<LeaderboardEntryDto> result = leaderboardService.getLeaderboard("class-1", "student-1");

        assertNotNull(result);
        assertEquals(1, result.size());
        LeaderboardEntryDto dto = result.get(0);
        assertEquals(1, dto.getRank());
        assertEquals("student-1", dto.getUserId());
        assertEquals("Nguyen Van B", dto.getUserFullName());
        assertEquals(150, dto.getTotalPoints());
        assertEquals("Bạc", dto.getCurrentTier());
    }

    @Test
    @DisplayName("Published attempt reward snapshot is used instead of mutable current reward rules")
    void publishedRewardSnapshotIsStable() {
        ExamAttempt attempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now(), false);
        attempt.setScore(new BigDecimal("95"));
        attempt.setRewardPointsSnapshot(12);
        attempt.setRewardScoreSnapshot(new BigDecimal("95"));
        attempt.setRewardRuleSnapshot("90:12,70:5");
        when(attemptRepository.lockPublishedAttemptsForRecalculation("class-1", "student-1")).thenReturn(List.of(attempt));
        when(rankTierRepository.findByClassIdOrderByMinPointsAsc("class-1")).thenReturn(List.of());
        var existingEntry = new com.classroom.modules.ranking.model.LeaderboardEntry("class-1", "student-1", 0, "Tân thủ");
        when(leaderboardRepository.findByClassIdAndUserId("class-1", "student-1")).thenReturn(Optional.of(existingEntry));
        when(leaderboardRepository.lockByClassIdAndUserId("class-1", "student-1")).thenReturn(Optional.of(existingEntry));

        leaderboardService.recalculateUserPoints("class-1", "student-1");

        verify(rewardRuleRepository, never()).findByClassIdAndExamIdOrderByMinExamScoreDesc(anyString(), anyString());
        verify(attemptRepository, never()).save(attempt);
        verify(leaderboardRepository).save(argThat(entry -> entry.getTotalPoints() == 12));
    }

    @Test
    @DisplayName("Corrected published score recomputes reward and leaderboard across threshold")
    void correctedScoreRecomputesRewardAcrossThreshold() {
        ExamAttempt attempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now(), false);
        attempt.setScore(new BigDecimal("75"));
        attempt.setRewardPointsSnapshot(12);
        attempt.setRewardScoreSnapshot(new BigDecimal("90"));
        var reward = new com.classroom.modules.ranking.model.ExamRewardRule("class-1", "exam-1", new BigDecimal("90"), 12);
        var baseReward = new com.classroom.modules.ranking.model.ExamRewardRule("class-1", "exam-1", new BigDecimal("70"), 5);
        when(attemptRepository.lockPublishedAttemptsForRecalculation("class-1", "student-1")).thenReturn(List.of(attempt));
        when(rewardRuleRepository.findByClassIdAndExamIdOrderByMinExamScoreDesc("class-1", "exam-1")).thenReturn(List.of(reward, baseReward));
        when(rankTierRepository.findByClassIdOrderByMinPointsAsc("class-1")).thenReturn(List.of());
        var existingEntry = new com.classroom.modules.ranking.model.LeaderboardEntry("class-1", "student-1", 0, "Tân thủ");
        when(leaderboardRepository.findByClassIdAndUserId("class-1", "student-1")).thenReturn(Optional.of(existingEntry));
        when(leaderboardRepository.lockByClassIdAndUserId("class-1", "student-1")).thenReturn(Optional.of(existingEntry));

        leaderboardService.recalculateUserPoints("class-1", "student-1");

        assertEquals(5, attempt.getRewardPointsSnapshot());
        assertEquals(new BigDecimal("75"), attempt.getRewardScoreSnapshot());
        verify(attemptRepository).save(attempt);
        verify(leaderboardRepository).save(argThat(entry -> entry.getTotalPoints() == 5));
    }

    // --- R5-08: configure() must reject a null entry in the JSON array as a 400 contract error ---

    @Test
    @DisplayName("R5-08/R4-05: configure rejects a null tier entry with 400, not an NPE")
    void configureRejectsNullTierEntry() {
        doNothing().when(accessPolicy).enforceManage("owner-1", "class-1", "LEADERBOARD", "EDIT", null);

        java.util.List<com.classroom.modules.ranking.dto.LeaderboardConfigRequest.Tier> tiers = new java.util.ArrayList<>();
        tiers.add(new com.classroom.modules.ranking.dto.LeaderboardConfigRequest.Tier("Bạc", 100, null, null));
        tiers.add(null); // stray null element, e.g. from a malformed JSON array
        var request = new com.classroom.modules.ranking.dto.LeaderboardConfigRequest(tiers, List.of());

        AppException ex = assertThrows(AppException.class,
                () -> leaderboardService.configure("class-1", "owner-1", request));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(rankTierRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("R5-08/R4-05: configure rejects a null reward entry with 400, not an NPE")
    void configureRejectsNullRewardEntry() {
        doNothing().when(accessPolicy).enforceManage("owner-1", "class-1", "LEADERBOARD", "EDIT", null);

        var tiers = List.of(new com.classroom.modules.ranking.dto.LeaderboardConfigRequest.Tier("Bạc", 100, null, null));
        java.util.List<com.classroom.modules.ranking.dto.LeaderboardConfigRequest.Reward> rewards = new java.util.ArrayList<>();
        rewards.add(null);
        var request = new com.classroom.modules.ranking.dto.LeaderboardConfigRequest(tiers, rewards);

        AppException ex = assertThrows(AppException.class,
                () -> leaderboardService.configure("class-1", "owner-1", request));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(rewardRuleRepository, never()).saveAll(any());
    }

    // --- R8-04: standard competition ranking (1,1,3,...) with a deterministic tie-break ---

    @Test
    @DisplayName("R8-04: entries tied on totalPoints share the same rank, and the next rank skips to the position")
    void getLeaderboardUsesStandardCompetitionRankingForTies() {
        doNothing().when(accessPolicy).enforceMember("student-1", "class-1");

        Instant earlier = Instant.parse("2026-01-01T00:00:00Z");
        Instant later = Instant.parse("2026-01-02T00:00:00Z");

        // Two entries tied at 100 points: "alice" reached it first (earlier lastCalculatedAt), so
        // she must sort ahead of "bob" deterministically, and both must be rank 1. A third entry at
        // 50 points must be rank 3 (not 2), matching standard competition ranking.
        LeaderboardEntry bob = new LeaderboardEntry("class-1", "bob", 100, "Bạc");
        bob.setLastCalculatedAt(later);
        LeaderboardEntry alice = new LeaderboardEntry("class-1", "alice", 100, "Bạc");
        alice.setLastCalculatedAt(earlier);
        LeaderboardEntry carol = new LeaderboardEntry("class-1", "carol", 50, "Đồng");
        carol.setLastCalculatedAt(earlier);

        when(memberRepository.findActiveUserIdsByClassId("class-1")).thenReturn(List.of("alice", "bob", "carol"));
        when(leaderboardRepository.findByClassIdOrderByTotalPointsDesc("class-1"))
                .thenReturn(List.of(bob, alice, carol));
        publicUsers();


        List<LeaderboardEntryDto> result = leaderboardService.getLeaderboard("class-1", "student-1");

        assertEquals(3, result.size());
        assertEquals("alice", result.get(0).getUserId());
        assertEquals(1, result.get(0).getRank());
        assertEquals("bob", result.get(1).getUserId());
        assertEquals(1, result.get(1).getRank());
        assertEquals("carol", result.get(2).getUserId());
        assertEquals(3, result.get(2).getRank());
    }

    // R13-08 (UI spec §2 "bộ lọc kỳ"): per-exam leaderboard filter.

    @Test
    @DisplayName("R13-08: examId filter rejects an exam that belongs to a different class")
    void testExamFilterRejectsMismatchedClass() {
        doNothing().when(accessPolicy).enforceMember("student-1", "class-1");
        Exam otherClassExam = new Exam();
        otherClassExam.setId("exam-1");
        otherClassExam.setClassId("class-2");
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(otherClassExam));

        AppException ex = assertThrows(AppException.class, () ->
                leaderboardService.getLeaderboard("class-1", "student-1", "exam-1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("R13-08: examId filter 404s for a nonexistent exam")
    void testExamFilterRejectsUnknownExam() {
        doNothing().when(accessPolicy).enforceMember("student-1", "class-1");
        when(examRepository.findById("exam-missing")).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () ->
                leaderboardService.getLeaderboard("class-1", "student-1", "exam-missing"));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("R13-08: examId filter ranks by best PUBLISHED score for that exam with competition ranking")
    void testExamFilterRanksByBestPublishedScore() {
        doNothing().when(accessPolicy).enforceMember("student-1", "class-1");
        Exam exam = new Exam();
        exam.setId("exam-1");
        exam.setClassId("class-1");
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));

        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-01-02T00:00:00Z");

        ExamAttempt aliceBest = new ExamAttempt("exam-1", "alice", "class-1", t1.plusSeconds(3600), false);
        aliceBest.setStatus("PUBLISHED");
        aliceBest.setScore(new BigDecimal("90"));
        aliceBest.setSubmittedAt(t1);

        // A lower-scoring earlier attempt from alice must not win over her better one.
        ExamAttempt aliceWorse = new ExamAttempt("exam-1", "alice", "class-1", t1.plusSeconds(3600), false);
        aliceWorse.setStatus("PUBLISHED");
        aliceWorse.setScore(new BigDecimal("60"));
        aliceWorse.setSubmittedAt(t1.minusSeconds(100));

        ExamAttempt bob = new ExamAttempt("exam-1", "bob", "class-1", t2.plusSeconds(3600), false);
        bob.setStatus("PUBLISHED");
        bob.setScore(new BigDecimal("90"));
        bob.setSubmittedAt(t2);

        // A preview attempt must never count toward the exam leaderboard.
        ExamAttempt preview = new ExamAttempt("exam-1", "staff-1", "class-1", t1.plusSeconds(3600), true);
        preview.setStatus("PUBLISHED");
        preview.setScore(new BigDecimal("100"));

        // A non-published attempt must never count either.
        ExamAttempt ungraded = new ExamAttempt("exam-1", "carol", "class-1", t1.plusSeconds(3600), false);
        ungraded.setStatus("SUBMITTED");
        ungraded.setScore(new BigDecimal("40"));

        when(memberRepository.findActiveUserIdsByClassId("class-1"))
                .thenReturn(List.of("alice", "bob", "carol", "staff-1"));
        when(attemptRepository.findByExamIdOrderByScoreDesc("exam-1"))
                .thenReturn(List.of(aliceBest, aliceWorse, bob, preview, ungraded));
        publicUsers();


        List<LeaderboardEntryDto> result = leaderboardService.getLeaderboard("class-1", "student-1", "exam-1");

        // Only alice and bob (both PUBLISHED, non-preview) appear, tied at rank 1 on score 90 —
        // alice's earlier submission is the deterministic tiebreaker used for stable ordering.
        assertEquals(2, result.size());
        assertEquals("alice", result.get(0).getUserId());
        assertEquals(1, result.get(0).getRank());
        assertEquals(90, result.get(0).getTotalPoints());
        assertEquals("bob", result.get(1).getUserId());
        assertEquals(1, result.get(1).getRank());
    }

    // ----- R14-11: REMOVED / BLOCKED members drop off both boards -----

    /** R16-08: users are batch-loaded with one findAllById per board; every requested id is a PUBLIC learner. */
    private void publicUsers() {
        when(userRepository.findAllById(any())).thenAnswer(inv -> {
            List<User> users = new java.util.ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                User u = new User((String) id, id + "@test.local", "hash", (String) id, "STUDENT");
                u.setProfileVisibility("PUBLIC");
                users.add(u);
            }
            return users;
        });
    }

    @Test
    @DisplayName("R14-11: the class-wide board omits non-ACTIVE members and ranks only the remaining ones")
    void classBoardExcludesRemovedAndBlockedMembers() {
        doNothing().when(accessPolicy).enforceMember("student-1", "class-1");
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        LeaderboardEntry removedTop = new LeaderboardEntry("class-1", "removed-user", 500, "Vàng");
        removedTop.setLastCalculatedAt(t);
        LeaderboardEntry blockedSecond = new LeaderboardEntry("class-1", "blocked-user", 400, "Bạc");
        blockedSecond.setLastCalculatedAt(t);
        LeaderboardEntry active = new LeaderboardEntry("class-1", "active-user", 100, "Đồng");
        active.setLastCalculatedAt(t);
        when(memberRepository.findActiveUserIdsByClassId("class-1")).thenReturn(List.of("active-user"));
        when(leaderboardRepository.findByClassIdOrderByTotalPointsDesc("class-1"))
                .thenReturn(List.of(removedTop, blockedSecond, active));
        publicUsers();

        List<LeaderboardEntryDto> board = leaderboardService.getLeaderboard("class-1", "student-1");

        assertEquals(1, board.size());
        assertEquals("active-user", board.get(0).getUserId());
        assertEquals(1, board.get(0).getRank(), "rank is computed after removing non-active members");
    }

    @Test
    @DisplayName("R14-11: the exam-scoped board omits non-ACTIVE members too")
    void examBoardExcludesRemovedAndBlockedMembers() {
        doNothing().when(accessPolicy).enforceMember("student-1", "class-1");
        Exam exam = new Exam();
        exam.setId("exam-1");
        exam.setClassId("class-1");
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        Instant t = Instant.parse("2026-01-01T00:00:00Z");

        ExamAttempt removed = new ExamAttempt("exam-1", "removed-user", "class-1", t.plusSeconds(3600), false);
        removed.setStatus("PUBLISHED");
        removed.setScore(new BigDecimal("100"));
        removed.setSubmittedAt(t);
        ExamAttempt blocked = new ExamAttempt("exam-1", "blocked-user", "class-1", t.plusSeconds(3600), false);
        blocked.setStatus("PUBLISHED");
        blocked.setScore(new BigDecimal("95"));
        blocked.setSubmittedAt(t);
        ExamAttempt active = new ExamAttempt("exam-1", "active-user", "class-1", t.plusSeconds(3600), false);
        active.setStatus("PUBLISHED");
        active.setScore(new BigDecimal("70"));
        active.setSubmittedAt(t);

        when(memberRepository.findActiveUserIdsByClassId("class-1")).thenReturn(List.of("active-user"));
        when(attemptRepository.findByExamIdOrderByScoreDesc("exam-1")).thenReturn(List.of(removed, blocked, active));
        publicUsers();

        List<LeaderboardEntryDto> board = leaderboardService.getLeaderboard("class-1", "student-1", "exam-1");

        assertEquals(1, board.size());
        assertEquals("active-user", board.get(0).getUserId());
        assertEquals(1, board.get(0).getRank());
        assertEquals(70, board.get(0).getTotalPoints());
    }

    // ----- R16-08: rows are rendered from one batched user lookup -----

    @Test
    @DisplayName("R16-08: the class board loads all its users with a single findAllById (no per-row findById)")
    void classBoardBatchLoadsUsers() {
        doNothing().when(accessPolicy).enforceMember("student-1", "class-1");
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        LeaderboardEntry a = new LeaderboardEntry("class-1", "u-a", 30, "Đồng");
        a.setLastCalculatedAt(t);
        LeaderboardEntry b = new LeaderboardEntry("class-1", "u-b", 20, "Đồng");
        b.setLastCalculatedAt(t);
        LeaderboardEntry c = new LeaderboardEntry("class-1", "u-c", 10, "Đồng");
        c.setLastCalculatedAt(t);
        when(memberRepository.findActiveUserIdsByClassId("class-1")).thenReturn(List.of("u-a", "u-b", "u-c"));
        when(leaderboardRepository.findByClassIdOrderByTotalPointsDesc("class-1")).thenReturn(List.of(a, b, c));
        publicUsers();

        List<LeaderboardEntryDto> board = leaderboardService.getLeaderboard("class-1", "student-1");

        assertEquals(3, board.size());
        assertEquals("u-a", board.get(0).getUserFullName());
        verify(userRepository, times(1)).findAllById(any());
        verify(userRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("R16-08: the per-exam board also batch-loads users, and resolves the viewer's admin status once for all rows")
    void examBoardBatchLoadsUsersAndResolvesViewerOnce() {
        doNothing().when(accessPolicy).enforceMember("student-1", "class-1");
        Exam exam = new Exam();
        exam.setId("exam-1");
        exam.setClassId("class-1");
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        ExamAttempt x = new ExamAttempt("exam-1", "u-x", "class-1", t1, false);
        x.setStatus("PUBLISHED");
        x.setScore(new BigDecimal("80"));
        ExamAttempt y = new ExamAttempt("exam-1", "u-y", "class-1", t1, false);
        y.setStatus("PUBLISHED");
        y.setScore(new BigDecimal("70"));
        ExamAttempt z = new ExamAttempt("exam-1", "u-z", "class-1", t1, false);
        z.setStatus("PUBLISHED");
        z.setScore(new BigDecimal("60"));
        when(memberRepository.findActiveUserIdsByClassId("class-1")).thenReturn(List.of("u-x", "u-y", "u-z"));
        when(attemptRepository.findByExamIdOrderByScoreDesc("exam-1")).thenReturn(List.of(x, y, z));
        // Private learners force the viewer-level checks (admin? peer?) to run for every row.
        when(userRepository.findAllById(any())).thenAnswer(inv -> {
            List<User> users = new java.util.ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                User u = new User((String) id, id + "@test.local", "hash", (String) id, "STUDENT");
                u.setProfileVisibility("PRIVATE");
                users.add(u);
            }
            return users;
        });

        List<LeaderboardEntryDto> board = leaderboardService.getLeaderboard("class-1", "student-1", "exam-1");

        assertEquals(3, board.size());
        assertTrue(board.stream().allMatch(r -> ProfileVisibilityPolicy.ANONYMOUS_DISPLAY_NAME.equals(r.getUserFullName())));
        verify(userRepository, times(1)).findAllById(any());
        verify(userRepository, never()).findById(anyString());
        // 3 rows x (id, name, avatar) used to evaluate isOwner/canManage nine times each.
        verify(accessPolicy, times(1)).canManage("student-1", "class-1", "MEMBER", "VIEW", null);
    }

    // ----- R16-09: configure / rebuild are audited -----

    @Test
    @DisplayName("R16-09: configure writes a LEADERBOARD_CONFIGURE audit event with a before/after summary")
    void configureIsAuditedWithBeforeAndAfter() {
        Exam exam = new Exam();
        exam.setId("exam-1");
        exam.setClassId("class-1");
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        when(rankTierRepository.findByClassIdOrderByMinPointsAsc("class-1"))
                .thenReturn(List.of(new com.classroom.modules.ranking.model.RankTier("class-1", "Cũ", 0, null, null)));
        when(rewardRuleRepository.findByClassId("class-1")).thenReturn(List.of());
        when(rankTierRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(rewardRuleRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leaderboardRepository.findByClassIdOrderByTotalPointsDesc("class-1")).thenReturn(List.of());

        leaderboardService.configure("class-1", "owner-1",
                new com.classroom.modules.ranking.dto.LeaderboardConfigRequest(
                        List.of(new com.classroom.modules.ranking.dto.LeaderboardConfigRequest.Tier("Mới", 0, null, null),
                                new com.classroom.modules.ranking.dto.LeaderboardConfigRequest.Tier("Vàng", 500, null, null)),
                        List.of(new com.classroom.modules.ranking.dto.LeaderboardConfigRequest.Reward("exam-1", new BigDecimal("80"), 25))));

        org.mockito.ArgumentCaptor<String> details = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq("class-1"), eq("owner-1"), eq("LEADERBOARD_CONFIGURE"), eq("LEADERBOARD"),
                eq("class-1"), details.capture());
        String json = details.getValue();
        assertTrue(json.contains("\"before\""), json);
        assertTrue(json.contains("Cũ:0"), json);
        assertTrue(json.contains("\"after\""), json);
        assertTrue(json.contains("Mới:0") && json.contains("Vàng:500"), json);
        assertTrue(json.contains("exam-1:80=25"), json);
    }

    @Test
    @DisplayName("R16-09: a rejected configure request (validation failure) leaves no audit event")
    void rejectedConfigureIsNotAudited() {
        assertThrows(AppException.class, () -> leaderboardService.configure("class-1", "owner-1",
                new com.classroom.modules.ranking.dto.LeaderboardConfigRequest(List.of(), List.of())));

        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("R16-09: configure is still gated on LEADERBOARD:EDIT and audits nothing when denied")
    void deniedConfigureIsNotAudited() {
        doThrow(new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "denied"))
                .when(accessPolicy).enforceManage("intruder", "class-1", "LEADERBOARD", "EDIT", null);

        assertThrows(AppException.class, () -> leaderboardService.configure("class-1", "intruder",
                new com.classroom.modules.ranking.dto.LeaderboardConfigRequest(List.of(), List.of())));

        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("R16-09: rebuildLeaderboard writes a LEADERBOARD_REBUILD audit event with the recalculated-user count")
    void rebuildIsAudited() {
        LeaderboardService spied = spy(leaderboardService);
        doNothing().when(spied).recalculateUserPointsInNewTransaction(anyString(), anyString());
        ExamAttempt a1 = new ExamAttempt("exam-1", "u-1", "class-1", Instant.now(), false);
        ExamAttempt a2 = new ExamAttempt("exam-2", "u-1", "class-1", Instant.now(), false);
        ExamAttempt a3 = new ExamAttempt("exam-1", "u-2", "class-1", Instant.now(), false);
        when(attemptRepository.findAllPublishedAttemptsByClass("class-1")).thenReturn(List.of(a1, a2, a3));

        spied.rebuildLeaderboard("class-1", "owner-1");

        verify(auditService).record(eq("class-1"), eq("owner-1"), eq("LEADERBOARD_REBUILD"), eq("LEADERBOARD"),
                eq("class-1"), contains("\"recalculatedUsers\":2"));
    }

    @Test
    @DisplayName("R16-09: a denied rebuild recalculates nothing and audits nothing")
    void deniedRebuildIsNotAudited() {
        doThrow(new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "denied"))
                .when(accessPolicy).enforceManage("intruder", "class-1", "LEADERBOARD", "EDIT", null);

        assertThrows(AppException.class, () -> leaderboardService.rebuildLeaderboard("class-1", "intruder"));

        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
        verify(attemptRepository, never()).findAllPublishedAttemptsByClass(any());
    }}
