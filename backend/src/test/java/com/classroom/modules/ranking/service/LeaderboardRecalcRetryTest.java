package com.classroom.modules.ranking.service;

import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.exam.repository.ExamRepository;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.ranking.model.LeaderboardEntry;
import com.classroom.modules.ranking.model.LeaderboardRecalcJob;
import com.classroom.modules.ranking.repository.ExamRewardRuleRepository;
import com.classroom.modules.ranking.repository.LeaderboardEntryRepository;
import com.classroom.modules.ranking.repository.LeaderboardRecalcJobRepository;
import com.classroom.modules.ranking.repository.RankTierRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A published or corrected score must never stay missing from the leaderboard because the
 * recalculation happened to fail once. These tests cover the durable job that guarantees the
 * recalculation is retried rather than silently dropped.
 */
@ExtendWith(MockitoExtension.class)
public class LeaderboardRecalcRetryTest {

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

    @InjectMocks
    private LeaderboardService leaderboardService;

    @Test
    @DisplayName("A completed recalculation clears its durable retry job in the same transaction")
    void testSuccessfulRecalculationClearsJob() {
        LeaderboardEntry entry = new LeaderboardEntry("class-1", "student-1", 0, "Tân thủ");
        when(leaderboardRepository.findByClassIdAndUserId("class-1", "student-1")).thenReturn(Optional.of(entry));
        when(leaderboardRepository.lockByClassIdAndUserId("class-1", "student-1")).thenReturn(Optional.of(entry));
        when(attemptRepository.lockPublishedAttemptsForRecalculation("class-1", "student-1")).thenReturn(List.of());
        when(rankTierRepository.findByClassIdOrderByMinPointsAsc("class-1")).thenReturn(List.of());
        LeaderboardRecalcJob job = new LeaderboardRecalcJob("class-1", "student-1");
        when(recalcJobRepository.findByClassIdAndUserId("class-1", "student-1")).thenReturn(List.of(job));

        leaderboardService.recalculateUserPoints("class-1", "student-1");

        verify(leaderboardRepository).save(entry);
        verify(recalcJobRepository).deleteByIdIn(List.of(job.getId()));
    }

    @Test
    @DisplayName("The sweeper retries an outstanding job so a failed recalculation is repaired without a manual rebuild")
    void testSweeperRetriesOutstandingJob() {
        LeaderboardRecalcJob job = new LeaderboardRecalcJob("class-1", "student-1");
        when(recalcJobRepository.findDueJobs(any(Instant.class), any())).thenReturn(List.of(job));

        LeaderboardEntry entry = new LeaderboardEntry("class-1", "student-1", 0, "Tân thủ");
        when(leaderboardRepository.findByClassIdAndUserId("class-1", "student-1")).thenReturn(Optional.of(entry));
        when(leaderboardRepository.lockByClassIdAndUserId("class-1", "student-1")).thenReturn(Optional.of(entry));
        when(attemptRepository.lockPublishedAttemptsForRecalculation("class-1", "student-1")).thenReturn(List.of());
        when(rankTierRepository.findByClassIdOrderByMinPointsAsc("class-1")).thenReturn(List.of());
        when(recalcJobRepository.findByClassIdAndUserId("class-1", "student-1")).thenReturn(List.of(job));

        leaderboardService.sweepPendingRecalculations();

        verify(leaderboardRepository).save(entry);
        verify(recalcJobRepository).deleteByIdIn(List.of(job.getId()));
    }

    @Test
    @DisplayName("A failed retry keeps the job and backs it off instead of dropping the stale total")
    void testFailedRetryKeepsJobAndBacksOff() {
        LeaderboardRecalcJob job = new LeaderboardRecalcJob("class-1", "student-1");
        when(recalcJobRepository.findDueJobs(any(Instant.class), any())).thenReturn(List.of(job));
        when(recalcJobRepository.findByClassIdAndUserId("class-1", "student-1")).thenReturn(List.of(job));
        when(leaderboardRepository.findByClassIdAndUserId("class-1", "student-1"))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("database unavailable"));

        Instant before = Instant.now();
        leaderboardService.sweepPendingRecalculations();

        // The job survives for another attempt, and is deferred rather than spun on.
        verify(recalcJobRepository, never()).deleteByIdIn(any());
        verify(recalcJobRepository).save(job);
        assertEquals(1, job.getAttempts());
        assertTrue(job.getNextAttemptAt().isAfter(before));
        assertNotNull(job.getLastError());
    }

    @Test
    @DisplayName("A database failure while listing jobs is tolerated so the sweeper keeps running")
    void testSweeperToleratesJobLookupFailure() {
        when(recalcJobRepository.findDueJobs(any(Instant.class), any()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("database unavailable"));

        assertDoesNotThrow(() -> leaderboardService.sweepPendingRecalculations());
    }

    @Test
    @DisplayName("A publication that arms a job mid-recalculation keeps that job instead of losing the score")
    void testJobArmedDuringRecalculationSurvives() {
        // The recalculation snapshots the armings before reading the attempts. A publication that
        // commits afterwards adds a job the run never saw, and whose score it therefore could not
        // have folded in: that job must survive for the sweeper.
        LeaderboardEntry entry = new LeaderboardEntry("class-1", "student-1", 0, "Tan thu");
        LeaderboardRecalcJob observed = new LeaderboardRecalcJob("class-1", "student-1");

        when(leaderboardRepository.findByClassIdAndUserId("class-1", "student-1")).thenReturn(Optional.of(entry));
        when(leaderboardRepository.lockByClassIdAndUserId("class-1", "student-1")).thenReturn(Optional.of(entry));
        when(recalcJobRepository.findByClassIdAndUserId("class-1", "student-1")).thenReturn(List.of(observed));
        when(attemptRepository.lockPublishedAttemptsForRecalculation("class-1", "student-1")).thenReturn(List.of());
        when(rankTierRepository.findByClassIdOrderByMinPointsAsc("class-1")).thenReturn(List.of());

        leaderboardService.recalculateUserPoints("class-1", "student-1");

        // Only the observed arming is cleared; anything armed later is left for the sweeper.
        verify(recalcJobRepository).deleteByIdIn(List.of(observed.getId()));
        verify(recalcJobRepository, never()).deleteAll();
    }

    @Test
    @DisplayName("The job is written inside the publishing transaction so it cannot be serviced before the score commits")
    void testJobIsArmedInThePublishingTransaction() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            leaderboardService.scheduleRecalculation("class-1", "student-1");

            // Armed on the caller's own transaction, not a separate one, and no recalculation has
            // run yet - it is deferred to after commit.
            verify(recalcJobRepository).save(any(LeaderboardRecalcJob.class));
            verify(attemptRepository, never()).lockPublishedAttemptsForRecalculation(anyString(), anyString());
        } finally {
            // finish the "transaction" the way Spring does, so the per-transaction resource does not leak into other tests
            for (var synchronization : new java.util.ArrayList<>(
                    org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations())) {
                synchronization.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED);
            }
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
