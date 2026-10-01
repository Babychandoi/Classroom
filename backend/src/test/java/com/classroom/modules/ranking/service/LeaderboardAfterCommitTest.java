package com.classroom.modules.ranking.service;

import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.exam.repository.ExamRepository;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.ranking.model.LeaderboardEntry;
import com.classroom.modules.ranking.model.LeaderboardRecalcJob;
import com.classroom.modules.ranking.repository.ExamRewardRuleRepository;
import com.classroom.modules.ranking.repository.LeaderboardEntryRepository;
import com.classroom.modules.ranking.repository.LeaderboardRecalcJobRepository;
import com.classroom.modules.ranking.repository.RankTierRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R20-01: the {@code afterCommit} hook of a publishing transaction must be a pure in-memory hand-off.
 *
 * <p>Spring keeps the request transaction's JDBC connection until AFTER the afterCommit callbacks have run. Any database access
 * in the hook - the old {@code recalculateUserPointsInNewTransaction} ({@code REQUIRES_NEW}) and the nested leaderboard-row
 * creation ({@code REQUIRES_NEW} again) - therefore needs a SECOND (and third) pooled connection while the first is still
 * held: N concurrent submits exhaust the pool and deadlock. These tests pin that the hook touches no repository at all, hands the
 * work to the worker executor, and that the actual recalculation then runs on a different thread with a single transaction.</p>
 */
@ExtendWith(MockitoExtension.class)
public class LeaderboardAfterCommitTest {

    @Mock private LeaderboardEntryRepository leaderboardRepository;
    @Mock private LeaderboardRecalcJobRepository recalcJobRepository;
    @Mock private RankTierRepository rankTierRepository;
    @Mock private ExamRewardRuleRepository rewardRuleRepository;
    @Mock private ExamAttemptRepository attemptRepository;
    @Mock private ExamRepository examRepository;
    @Mock private UserRepository userRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private ClassMemberRepository memberRepository;
    @Mock private AuditService auditService;
    @Mock private LeaderboardRecalcExecutor executor;

    private LeaderboardService service;

    @BeforeEach
    void setUp() {
        service = new LeaderboardService(leaderboardRepository, recalcJobRepository, rankTierRepository, rewardRuleRepository,
                attemptRepository, examRepository, userRepository, accessPolicy, new ProfileVisibilityPolicy(accessPolicy),
                memberRepository, auditService, null, executor);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        // complete the "transaction" like Spring does: the service unbinds its per-transaction resource in afterCompletion
        for (TransactionSynchronization synchronization : new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        TransactionSynchronizationManager.clearSynchronization();
    }

    private void runAfterCommitCallbacks() {
        for (TransactionSynchronization synchronization : new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())) {
            synchronization.afterCommit();
        }
    }

    @Test
    @DisplayName("afterCommit touches NO repository: no second connection can be requested while the request's is still held")
    void afterCommitHookDoesNoDatabaseWork() {
        service.scheduleRecalculation("class-1", "student-1");
        // the durable job is written inside the publishing transaction (that is the committing connection's own work) ...
        verify(recalcJobRepository).save(any(LeaderboardRecalcJob.class));
        // ... everything from here on is what runs while Spring still holds that connection
        clearInvocations(leaderboardRepository, recalcJobRepository, rankTierRepository, rewardRuleRepository, attemptRepository,
                examRepository, userRepository, accessPolicy, memberRepository, auditService);

        runAfterCommitCallbacks();

        verifyNoInteractions(leaderboardRepository, recalcJobRepository, rankTierRepository, rewardRuleRepository,
                attemptRepository, examRepository, userRepository, accessPolicy, memberRepository, auditService);
        verify(executor).submit(eq("class-1::student-1"), any(Runnable.class));
    }

    @Test
    @DisplayName("the recalculation itself runs only when the worker executes the task - never during afterCommit")
    void recalculationRunsOnlyInTheWorker() {
        LeaderboardService spied = spy(service);
        spied.scheduleRecalculation("class-1", "student-1");
        runAfterCommitCallbacks();

        verify(spied, never()).recalculateUserPointsInNewTransaction(anyString(), anyString());
        verify(spied, never()).recalculateUserPoints(anyString(), anyString());

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).submit(eq("class-1::student-1"), task.capture());
        // the recalculation has not started; it does when (and only when) the worker thread runs the task
        LeaderboardEntry entry = new LeaderboardEntry("class-1", "student-1", 0, "Tân thủ");
        when(leaderboardRepository.findByClassIdAndUserId("class-1", "student-1")).thenReturn(Optional.of(entry));
        when(leaderboardRepository.lockByClassIdAndUserId("class-1", "student-1")).thenReturn(Optional.of(entry));
        when(attemptRepository.lockPublishedAttemptsForRecalculation("class-1", "student-1")).thenReturn(List.of());
        when(rankTierRepository.findByClassIdOrderByMinPointsAsc("class-1")).thenReturn(List.of());
        task.getValue().run();
        verify(spied).recalculateUserPointsInNewTransaction("class-1", "student-1");
    }

    @Test
    @DisplayName("creating the missing leaderboard row is one INSERT ... ON DUPLICATE KEY on the same connection, not a nested transaction")
    void missingEntryIsCreatedWithoutANestedTransaction() {
        when(leaderboardRepository.findByClassIdAndUserId("class-1", "student-1")).thenReturn(Optional.empty());
        when(leaderboardRepository.lockByClassIdAndUserId("class-1", "student-1"))
                .thenReturn(Optional.of(new LeaderboardEntry("class-1", "student-1", 0, "Tân thủ")));
        when(attemptRepository.lockPublishedAttemptsForRecalculation("class-1", "student-1")).thenReturn(List.of());
        when(rankTierRepository.findByClassIdOrderByMinPointsAsc("class-1")).thenReturn(List.of());

        service.recalculateUserPoints("class-1", "student-1");

        verify(leaderboardRepository).insertIfAbsent(anyString(), eq("class-1"), eq("student-1"), anyString());
        // no saveAndFlush(new entry) - that was the body of the REQUIRES_NEW method that no longer exists
        verify(leaderboardRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a hand-off that the executor drops is not an error: the durable job is left for the sweeper")
    void droppedHandOffLeavesTheJobForTheSweeper() {
        when(executor.submit(anyString(), any(Runnable.class))).thenReturn(false);

        service.scheduleRecalculation("class-1", "student-1");
        runAfterCommitCallbacks();

        verify(recalcJobRepository).save(any(LeaderboardRecalcJob.class));
        verify(recalcJobRepository, never()).deleteByIdIn(any());
    }

    @Test
    @DisplayName("a freshly armed job is hidden from the sweeper for a grace period so worker and sweeper do not race")
    void armedJobIsDeferredForTheSweeper() {
        ArgumentCaptor<LeaderboardRecalcJob> job = ArgumentCaptor.forClass(LeaderboardRecalcJob.class);

        service.scheduleRecalculation("class-1", "student-1");

        verify(recalcJobRepository).save(job.capture());
        assertTrue(job.getValue().getNextAttemptAt().isAfter(java.time.Instant.now().plusSeconds(5)),
                "grace before the sweeper may pick the job up");
    }

    // ---- the executor itself ----

    @Test
    @DisplayName("executor: tasks run on worker threads, equal queued keys collapse, a running key can be queued again")
    void executorRunsOnWorkersAndCoalescesQueuedKeys() throws Exception {
        LeaderboardRecalcExecutor real = new LeaderboardRecalcExecutor(1, 10);
        try {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<String> workerThread = new AtomicReference<>();
            List<String> ran = new ArrayList<>();

            assertTrue(real.submit("blocker", () -> {
                workerThread.set(Thread.currentThread().getName());
                firstStarted.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            assertNotEquals(Thread.currentThread().getName(), workerThread.get());
            assertTrue(workerThread.get().startsWith("leaderboard-recalc-"));

            // while the single worker is busy, three triggers for the same learner become ONE queued run
            CountDownLatch done = new CountDownLatch(1);
            assertTrue(real.submit("class-1::u", () -> { synchronized (ran) { ran.add("first"); } done.countDown(); }));
            assertTrue(real.submit("class-1::u", () -> { synchronized (ran) { ran.add("dup-1"); } }));
            assertTrue(real.submit("class-1::u", () -> { synchronized (ran) { ran.add("dup-2"); } }));

            release.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);
            synchronized (ran) {
                assertEquals(List.of("first"), ran);
            }

            // once a run has started, a new trigger queues a fresh run (the running one may already have read the jobs)
            CountDownLatch again = new CountDownLatch(1);
            assertTrue(real.submit("class-1::u", again::countDown));
            assertTrue(again.await(5, TimeUnit.SECONDS));
        } finally {
            real.shutdown();
        }
    }

    @Test
    @DisplayName("executor: a full queue drops the task (returns false) instead of blocking or running it on the caller")
    void executorNeverBlocksOrRunsOnTheCallerWhenFull() throws Exception {
        LeaderboardRecalcExecutor real = new LeaderboardRecalcExecutor(1, 1);
        try {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            real.submit("busy", () -> {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(real.submit("queued", () -> { }));           // fills the single queue slot
            long t0 = System.nanoTime();
            boolean accepted = real.submit("overflow", () -> { throw new AssertionError("must never run on the caller"); });
            long ms = (System.nanoTime() - t0) / 1_000_000;

            assertFalse(accepted);
            assertTrue(ms < 500, "submit must not block: " + ms + " ms");
            release.countDown();
        } finally {
            real.shutdown();
        }
    }
}
