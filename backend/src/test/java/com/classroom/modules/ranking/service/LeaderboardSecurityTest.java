package com.classroom.modules.ranking.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.exam.repository.ExamRepository;
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

    private LeaderboardService leaderboardService;

    @BeforeEach
    void setUp() {
        // A real visibility policy over the mocked AccessPolicy, so the leaderboard is filtered
        // by the same rule the profile endpoint uses.
        leaderboardService = new LeaderboardService(leaderboardRepository, recalcJobRepository,
                rankTierRepository, rewardRuleRepository, attemptRepository, examRepository,
                userRepository, accessPolicy, new ProfileVisibilityPolicy(accessPolicy), null);
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
        when(leaderboardRepository.findByClassIdOrderByTotalPointsDesc("class-1"))
                .thenReturn(List.of(entry));

        User user = new User("student-1", "student@test.local", "hash", "Nguyen Van B", "STUDENT");
        user.setAvatarUrl("https://avatar.test/2.png");
        when(userRepository.findById("student-1")).thenReturn(Optional.of(user));

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
}
