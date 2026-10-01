package com.classroom.modules.outbox.worker;

import com.classroom.modules.outbox.model.OutboxEvent;
import com.classroom.modules.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R20-05 retention of PROCESSED events and R20-04b automatic re-drive of dead letters. */
@ExtendWith(MockitoExtension.class)
class OutboxRetentionAndRedriveTest {

    @Mock
    private OutboxEventRepository repository;

    private final TestClock clock = TestClock.at("2026-01-31T12:00:00Z");
    private final OutboxProperties properties = new OutboxProperties();

    @BeforeEach
    void fastRetention() {
        properties.getRetention().setPauseMs(0);
    }

    // ------------------------------------------------------------------------------------------------ retention

    private static List<String> ids(int count, String prefix) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(prefix + i);
        }
        return ids;
    }

    @Test
    @DisplayName("R20-05: only PROCESSED events older than the retention window are purged, in batches, until none are left")
    void purgesInBatchesUntilEmpty() {
        OutboxRetentionJob job = new OutboxRetentionJob(repository, properties, clock);
        when(repository.findProcessedIdsBefore(any(), any(Pageable.class)))
                .thenReturn(ids(1000, "a"))
                .thenReturn(ids(1000, "b"))
                .thenReturn(ids(250, "c"))
                .thenReturn(List.of());
        when(repository.findProcessedWithoutTimestampCreatedBefore(any(), any(Pageable.class))).thenReturn(List.of());
        when(repository.deleteProcessedByIds(any())).thenAnswer(inv -> ((java.util.Collection<?>) inv.getArgument(0)).size());

        int purged = job.purgeExpired();

        assertEquals(2250, purged);
        verify(repository, times(3)).deleteProcessedByIds(any());
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository, times(4)).findProcessedIdsBefore(cutoff.capture(), any(Pageable.class));
        assertEquals(Instant.parse("2026-01-24T12:00:00Z"), cutoff.getValue(), "cutoff = now - 7 days");
    }

    @Test
    @DisplayName("R20-05: the batch size and the retention window are configurable")
    void batchSizeAndWindowAreConfigurable() {
        properties.getRetention().setDays(30);
        properties.getRetention().setBatchSize(200);
        OutboxRetentionJob job = new OutboxRetentionJob(repository, properties, clock);
        when(repository.findProcessedIdsBefore(any(), any(Pageable.class))).thenReturn(List.of());
        when(repository.findProcessedWithoutTimestampCreatedBefore(any(), any(Pageable.class))).thenReturn(List.of());

        job.purgeExpired();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).findProcessedIdsBefore(cutoff.capture(), page.capture());
        assertEquals(200, page.getValue().getPageSize());
        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), cutoff.getValue());
    }

    @Test
    @DisplayName("R20-05: a disabled retention deletes nothing; a run with nothing to delete costs two indexed lookups")
    void disabledAndIdleRuns() {
        properties.getRetention().setEnabled(false);
        OutboxRetentionJob disabled = new OutboxRetentionJob(repository, properties, clock);
        assertEquals(0, disabled.purgeExpired());
        verify(repository, never()).findProcessedIdsBefore(any(), any());

        properties.getRetention().setEnabled(true);
        OutboxRetentionJob job = new OutboxRetentionJob(repository, properties, clock);
        when(repository.findProcessedIdsBefore(any(), any(Pageable.class))).thenReturn(List.of());
        when(repository.findProcessedWithoutTimestampCreatedBefore(any(), any(Pageable.class))).thenReturn(List.of());
        assertEquals(0, job.purgeExpired());
        verify(repository, never()).deleteProcessedByIds(any());
    }

    @Test
    @DisplayName("R20-05: the time budget of one run stops the purge; the rest is left for the next run")
    void timeBudgetStopsTheRun() {
        properties.getRetention().setMaxRunMs(100);
        OutboxRetentionJob job = new OutboxRetentionJob(repository, properties, clock);
        when(repository.findProcessedIdsBefore(any(), any(Pageable.class))).thenAnswer(inv -> {
            Thread.sleep(150); // one batch already exceeds the 100 ms budget
            return ids(1000, "x");
        });
        when(repository.deleteProcessedByIds(any())).thenReturn(1000);

        assertEquals(1000, job.purgeExpired());
        verify(repository, times(1)).deleteProcessedByIds(any());
    }

    // ------------------------------------------------------------------------------------------------- re-drive

    private OutboxEvent deadLetter(String aggregateId, long seq, String kind, String message, Duration deadFor, int autoReplays) {
        OutboxEvent event = new OutboxEvent("CLASSROOM", aggregateId, "MEMBER_JOINED", "{}");
        event.setSequenceNo(seq);
        event.setStatus("DEAD_LETTER");
        event.setRetryCount(5);
        event.setFailureKind(kind);
        event.setErrorMessage(message);
        event.setProcessedAt(clock.instant().minus(deadFor));
        event.setAutoReplayCount(autoReplays);
        return event;
    }

    private OutboxRedriveJob redrive() {
        lenient().when(repository.existsEarlierUncompletedEvent(any(), any(), anyLong())).thenReturn(false);
        lenient().when(repository.autoReplay(any(), any())).thenReturn(1);
        return new OutboxRedriveJob(repository, properties, clock);
    }

    private void candidates(OutboxEvent... events) {
        when(repository.findRedriveCandidates(anyInt(), any(), any(Pageable.class))).thenReturn(List.of(events));
    }

    @Test
    @DisplayName("R20-04b: a dead letter recorded as TRANSIENT is replayed once it has been dead for transient-after-seconds")
    void transientDeadLetterIsReplayedAfterAMinute() {
        OutboxEvent young = deadLetter("c1", 1, "TRANSIENT", "Neo4j: Unable to connect", Duration.ofSeconds(30), 0);
        OutboxEvent ripe = deadLetter("c2", 2, "TRANSIENT", "Neo4j: Unable to connect", Duration.ofSeconds(90), 0);
        candidates(young, ripe);

        int replayed = redrive().redriveDeadLetters();

        assertEquals(1, replayed);
        verify(repository, never()).autoReplay(eq(young.getId()), any());
        verify(repository).autoReplay(eq(ripe.getId()), contains("[auto-replay 1/3]"));
    }

    @Test
    @DisplayName("R20-04b: a legacy dead letter (no failure_kind) is recognised as transient from its error text")
    void legacyTransientDeadLetterIsRecognisedFromItsMessage() {
        OutboxEvent legacy = deadLetter("c1", 1, null,
                "Neo4j: Neo4j projection failed: Unable to connect to neo4j:7687, ensure the database is running", Duration.ofMinutes(2), 0);
        OutboxEvent poison = deadLetter("c2", 2, null, "MongoDB: E11000 duplicate key", Duration.ofMinutes(2), 0);
        candidates(legacy, poison);

        assertEquals(1, redrive().redriveDeadLetters());

        verify(repository).autoReplay(eq(legacy.getId()), any());
        verify(repository, never()).autoReplay(eq(poison.getId()), any());
    }

    @Test
    @DisplayName("R20-04b: a PERMANENT dead letter is replayed only after older-than-minutes, and never beyond max-auto-replays")
    void permanentDeadLetterWaitsLongerAndIsBounded() {
        OutboxEvent fresh = deadLetter("c1", 1, "PERMANENT", "poison", Duration.ofMinutes(10), 0);
        OutboxEvent old = deadLetter("c2", 2, "PERMANENT", "poison", Duration.ofMinutes(31), 2);
        candidates(fresh, old);

        assertEquals(1, redrive().redriveDeadLetters());

        verify(repository, never()).autoReplay(eq(fresh.getId()), any());
        verify(repository).autoReplay(eq(old.getId()), contains("[auto-replay 3/3]"));
    }

    @Test
    @DisplayName("R20-04b: the budget is enforced in the query (autoReplayCount < max), so an exhausted poison event is never offered again")
    void exhaustedEventsAreFilteredByTheQuery() {
        OutboxRedriveJob job = redrive();
        when(repository.findRedriveCandidates(anyInt(), any(), any(Pageable.class))).thenReturn(List.of());

        job.redriveDeadLetters();

        verify(repository).findRedriveCandidates(eq(3), any(Instant.class), any(Pageable.class));
    }

    @Test
    @DisplayName("R20-04b: only the HEAD dead letter of an aggregate is replayed (anything behind it is gated anyway)")
    void onlyTheHeadOfAnAggregateIsReplayed() {
        OutboxEvent head = deadLetter("c1", 1, "TRANSIENT", "timed out", Duration.ofMinutes(5), 0);
        OutboxEvent behind = deadLetter("c1", 2, "TRANSIENT", "timed out", Duration.ofMinutes(5), 0);
        OutboxEvent blocked = deadLetter("c2", 4, "TRANSIENT", "timed out", Duration.ofMinutes(5), 0);
        candidates(head, behind, blocked);
        OutboxRedriveJob job = redrive();
        when(repository.existsEarlierUncompletedEvent("CLASSROOM", "c2", 4L)).thenReturn(true); // an earlier exhausted/pending event holds it

        assertEquals(1, job.redriveDeadLetters());

        verify(repository).autoReplay(eq(head.getId()), any());
        verify(repository, never()).autoReplay(eq(behind.getId()), any());
        verify(repository, never()).autoReplay(eq(blocked.getId()), any());
    }

    @Test
    @DisplayName("R20-04b: the re-drive is rate limited to batch-size events per run")
    void rateLimitedPerRun() {
        properties.getRedrive().setBatchSize(3);
        List<OutboxEvent> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add(deadLetter("agg-" + i, i + 1, "TRANSIENT", "timed out", Duration.ofMinutes(5), 0));
        }
        candidates(many.toArray(new OutboxEvent[0]));

        assertEquals(3, redrive().redriveDeadLetters());
        verify(repository, times(3)).autoReplay(any(), any());
    }

    @Test
    @DisplayName("R20-04b: the re-drive can be switched off, or given a zero budget")
    void canBeDisabled() {
        properties.getRedrive().setEnabled(false);
        assertEquals(0, redrive().redriveDeadLetters());
        properties.getRedrive().setEnabled(true);
        properties.getRedrive().setMaxAutoReplays(0);
        assertEquals(0, redrive().redriveDeadLetters());
        verify(repository, never()).findRedriveCandidates(anyInt(), any(), any());
    }
}
