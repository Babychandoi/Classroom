package com.classroom.modules.outbox.worker;

import com.classroom.modules.outbox.model.OutboxEvent;
import com.classroom.modules.outbox.repository.OutboxEventRepository;
import com.classroom.modules.projection.mongo.LearningEventDocument;
import com.classroom.modules.projection.mongo.LearningEventRepository;
import com.classroom.modules.projection.neo4j.Neo4jSyncService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Projects the transactional outbox into MongoDB (learning events) and Neo4j (class membership graph).
 *
 * <h3>Threading (R20-04a)</h3>
 * The {@code @Scheduled} poll runs on the shared scheduler and only does quick MySQL work: it finds the ready aggregates and hands
 * each one to a dedicated, bounded executor ({@code outbox-worker-N}). All blocking I/O to MongoDB / Neo4j happens on that executor,
 * never on the shared scheduler, so a store that hangs for its whole connect timeout can no longer hold up attempt finalisation, the
 * leaderboard sweeper, media clean-up or the token purges. At most one task per aggregate is in flight at a time
 * ({@link #inFlight}); across instances the atomic {@code PENDING -> PROCESSING} claim and the ordering gate keep the same guarantee.
 *
 * <h3>Ordering and throughput (R20-05)</h3>
 * Events of one aggregate are still projected strictly in {@code sequence_no} order and only when every earlier event of that
 * aggregate is {@code PROCESSED}. Instead of one event per aggregate per poll, a task now takes the aggregate's head and the
 * consecutive {@code PENDING} events behind it (up to {@code max-events-per-aggregate-per-pass}) and projects them back to back,
 * stopping at the first event that does not complete. 200 members joining one class no longer wait 200 poll cycles.
 *
 * <h3>Failures (R20-04b)</h3>
 * A failure is classified ({@link OutboxFailureClassifier}). A TRANSIENT failure (store unreachable / timing out) never increments
 * {@code retry_count} and never dead-letters: the event goes back to {@code PENDING}, the store's {@link SinkBreaker} opens, and the
 * worker stops touching that store until a probe succeeds. A PERMANENT failure (the event cannot be projected) counts toward the
 * dead-letter limit with exponential back-off, exactly as before. Dead letters are re-driven automatically by
 * {@link OutboxRedriveJob} and manually through {@link #replayFailedEvents(String)}.
 */
@Component
public class OutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final int MAX_STORED_MESSAGE = 1000;
    /** Event types that also project to Neo4j; every event projects to MongoDB while that projection is enabled. */
    static final List<String> NEO4J_EVENT_TYPES = List.of("MEMBER_JOINED", "MEMBER_REMOVED", "MEMBER_EXPIRED");
    /** D-19: events that take the membership edge away (a lapsed paid member is projected exactly like a removed one). */
    static final List<String> MEMBERSHIP_ENDED_EVENT_TYPES = List.of("MEMBER_REMOVED", "MEMBER_EXPIRED");
    private static final ObjectMapper PAYLOAD_READER = new ObjectMapper();

    private final OutboxEventRepository outboxEventRepository;
    private final Neo4jSyncService neo4jSyncService;
    private final OutboxProperties properties;
    private final Clock clock;
    private final Executor executor;
    private final ThreadPoolExecutor ownedExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<OutboxSink, SinkBreaker> breakers = new EnumMap<>(OutboxSink.class);
    /** Aggregates that currently have a projection task queued or running in THIS JVM (single-flight per aggregate). */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private volatile Instant lastStaleScan = Instant.EPOCH;

    @Autowired(required = false)
    private LearningEventRepository learningEventRepository;
    @Autowired(required = false)
    private OutboxLeaseStore leaseStore;

    @Value("${classroom.projection.mongo.enabled:true}")
    private boolean mongoEnabled = true;

    @Value("${classroom.projection.neo4j.enabled:true}")
    private boolean neo4jEnabled = true;

    @Autowired
    public OutboxWorker(OutboxEventRepository outboxEventRepository,
                        Neo4jSyncService neo4jSyncService,
                        OutboxProperties properties) {
        this(outboxEventRepository, neo4jSyncService, properties, Clock.systemUTC(), null);
    }

    /** Test seam: a controllable clock and, optionally, a same-thread executor. {@code null} means "own bounded executor". */
    OutboxWorker(OutboxEventRepository outboxEventRepository,
                 Neo4jSyncService neo4jSyncService,
                 OutboxProperties properties,
                 Clock clock,
                 Executor executorOverride) {
        this.outboxEventRepository = outboxEventRepository;
        this.neo4jSyncService = neo4jSyncService;
        this.properties = properties;
        this.clock = clock;
        if (executorOverride == null) {
            this.ownedExecutor = newProjectionExecutor(properties);
            this.executor = ownedExecutor;
        } else {
            this.ownedExecutor = null;
            this.executor = executorOverride;
        }
        OutboxProperties.Breaker b = properties.getBreaker();
        for (OutboxSink sink : OutboxSink.values()) {
            breakers.put(sink, new SinkBreaker(sink, b.getBaseDelayMs(), b.getMaxDelayMs(), b.getProbeTimeoutMs()));
        }
    }

    private static ThreadPoolExecutor newProjectionExecutor(OutboxProperties properties) {
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                properties.getWorkers(), properties.getWorkers(), 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "outbox-worker-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @PreDestroy
    public void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
            try {
                if (!ownedExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    ownedExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                ownedExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------ dispatch

    /**
     * The scheduled poll. Cheap and non-blocking with respect to MongoDB / Neo4j: it never calls them. An idle poll is one indexed
     * lookup of the PENDING rows (V36), independent of how many PROCESSED rows the table holds.
     */
    @Scheduled(fixedDelayString = "${classroom.outbox.poll-delay-ms:500}")
    public void processOutboxEvents() {
        if (!properties.isEnabled()) {
            return;
        }
        Instant now = clock.instant();
        reclaimStaleEventsIfDue(now);

        // While a required store is known to be down there is nothing useful to fetch: every event needs MongoDB (while enabled),
        // and the events that need Neo4j are excluded in SQL so they cannot occupy the whole batch.
        if (mongoEnabled && breakers.get(OutboxSink.MONGO).isBlocked(now)) {
            return;
        }
        boolean neo4jBlocked = neo4jEnabled && breakers.get(OutboxSink.NEO4J).isBlocked(now);

        PageRequest batch = PageRequest.of(0, properties.getDispatchBatch());
        List<OutboxEvent> heads = neo4jBlocked
                ? outboxEventRepository.findEligiblePendingEventsExcludingTypes(now, NEO4J_EVENT_TYPES, batch)
                : outboxEventRepository.findEligiblePendingEvents(now, batch);
        Set<String> dispatchedThisPoll = new HashSet<>();
        for (OutboxEvent head : heads) {
            // The SQL only returns the head of each aggregate; if a second event of the same aggregate ever shows up in the batch it
            // must not run ahead of (or beside) the first one - the pass below picks up the followers in order.
            if (!dispatchedThisPoll.add(aggregateKey(head))) {
                continue;
            }
            if (!dispatch(head)) {
                break; // executor saturated: the remaining aggregates are picked up by the next poll
            }
        }
    }

    /** Hands one ready aggregate to the projection executor. Returns false when the executor is saturated. */
    private boolean dispatch(OutboxEvent head) {
        String key = aggregateKey(head);
        if (!inFlight.add(key)) {
            return true; // already queued or running: single-flight per aggregate
        }
        try {
            executor.execute(() -> runPass(head, key));
            return true;
        } catch (RejectedExecutionException saturated) {
            inFlight.remove(key);
            return false;
        }
    }

    private void runPass(OutboxEvent head, String key) {
        try {
            projectAggregate(head);
        } catch (Throwable unexpected) {
            log.error("Outbox: unexpected error while projecting aggregate {}", key, unexpected);
        } finally {
            inFlight.remove(key);
        }
    }

    private static String aggregateKey(OutboxEvent event) {
        return event.getAggregateType() + ":" + event.getAggregateId();
    }

    /** Aggregates with a task queued or running right now (tests, diagnostics). */
    int inFlightAggregates() {
        return inFlight.size();
    }

    /**
     * Reclaims events stuck in PROCESSING (a worker that crashed) - at most every {@code stale-scan-interval-ms}, not on every poll.
     * Each reclaim counts as a retry so a payload that always crashes its worker still reaches DEAD_LETTER instead of being handed
     * out forever (R1-09); an event already at the retry ceiling is dead-lettered directly.
     */
    private void reclaimStaleEventsIfDue(Instant now) {
        if (now.isBefore(lastStaleScan.plusMillis(properties.getStaleScanIntervalMs()))) {
            return;
        }
        lastStaleScan = now;
        Instant staleThreshold = now.minusSeconds(properties.getStaleAfterSeconds());
        try {
            outboxEventRepository.deadLetterStaleProcessingEventsAtRetryLimit(staleThreshold, properties.getMaxRetries());
        } catch (Exception ex) {
            log.warn("Failed to dead-letter stale processing events at retry limit: {}", ex.getMessage());
        }
        try {
            outboxEventRepository.resetStaleProcessingEvents(staleThreshold, properties.getMaxRetries());
        } catch (Exception ex) {
            log.warn("Failed to reset stale processing events: {}", ex.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------ one aggregate

    private enum Outcome { PROCESSED, STOPPED }

    /**
     * Projects the head of an aggregate and the consecutive PENDING events behind it, in order, stopping at the first event that
     * does not complete (not claimable, gated, backing off, store down, or failed).
     */
    void projectAggregate(OutboxEvent head) {
        List<OutboxEvent> pass = new ArrayList<>();
        pass.add(head);
        int more = properties.getMaxEventsPerAggregatePerPass() - 1;
        if (more > 0 && head.getSequenceNo() != null) {
            try {
                pass.addAll(outboxEventRepository.findPendingAfter(
                        head.getAggregateType(), head.getAggregateId(), head.getSequenceNo(), PageRequest.of(0, more)));
            } catch (Exception ex) {
                log.warn("Failed to read the events following outbox event {}: {}", head.getId(), ex.getMessage());
            }
        }
        for (OutboxEvent event : pass) {
            if (processOne(event) != Outcome.PROCESSED) {
                return;
            }
        }
    }

    private Outcome processOne(OutboxEvent event) {
        Instant now = clock.instant();

        // The ordering gate is only sound with a sequence number. An event without one cannot be placed relative to its
        // siblings, so fail closed rather than risk applying it out of order.
        Long sequenceNo = event.getSequenceNo();
        if (sequenceNo == null) {
            log.warn("Outbox event {} has no sequence number; deferring to preserve per-aggregate order", event.getId());
            return Outcome.STOPPED;
        }

        // Back-off between PERMANENT retries (2, 4, 8, ... s, at most 5 min). Transient failures do not set a retry count.
        if (event.getRetryCount() > 0 && event.getProcessedAt() != null) {
            long backoffSeconds = Math.min(300, (long) Math.pow(2, event.getRetryCount()));
            if (event.getProcessedAt().plusSeconds(backoffSeconds).isAfter(now)) {
                return Outcome.STOPPED;
            }
        }

        // A store that is down is not called at all: no claim, no write, no log line.
        Set<OutboxSink> required = requiredSinks(event);
        Map<OutboxSink, SinkBreaker.Permit> permits = acquirePermits(required, now);
        if (permits == null) {
            return Outcome.STOPPED;
        }

        // Distributed atomic claim across workers.
        int claimed = 0;
        try {
            event.setLeaseToken(java.util.UUID.randomUUID().toString());
            claimed = leaseStore == null ? outboxEventRepository.claimEvent(event.getId(), now)
                    : leaseStore.claim(event.getId(), event.getLeaseToken(), now);
        } catch (Exception ex) {
            log.warn("Failed to claim outbox event {}: {}", event.getId(), ex.getMessage());
        }
        if (claimed <= 0) {
            releasePermits(permits);
            return Outcome.STOPPED;
        }

        // Strict per-aggregate causal gate, checked AFTER the claim: an event may only run when every strictly earlier event of the
        // same aggregate has reached PROCESSED (this also covers earlier events parked in PROCESSING, FAILED or DEAD_LETTER, so a
        // later MEMBER_JOINED can never be projected ahead of an earlier failed MEMBER_REMOVED). Another worker may have taken an
        // earlier event between the poll and our claim; release and let a later pass retry in order.
        try {
            if (outboxEventRepository.existsEarlierUncompletedEvent(event.getAggregateType(), event.getAggregateId(), sequenceNo)) {
                releaseClaim(event);
                releasePermits(permits);
                return Outcome.STOPPED;
            }
        } catch (Exception gateFailure) {
            log.warn("Ordering gate check failed for outbox event {}: {}", event.getId(), gateFailure.getMessage());
            try {
                releaseClaim(event);
            } catch (Exception ignored) {
                // the claim is reclaimed as stale later
            }
            releasePermits(permits);
            return Outcome.STOPPED;
        }

        // Idempotent projections; an event is PROCESSED only when every required, enabled projection succeeded (Finding 6, 11).
        SinkFailure failure = null;
        if (required.contains(OutboxSink.MONGO)) {
            failure = settle(OutboxSink.MONGO, permits, projectToMongo(event));
        }
        if (failure == null && required.contains(OutboxSink.NEO4J)) {
            failure = settle(OutboxSink.NEO4J, permits, projectToNeo4j(event));
        }
        releasePermits(permits); // whatever was not exercised (a store skipped after an earlier failure)

        if (failure == null) {
            return finishSuccess(event);
        }
        if (failure.kind() == FailureKind.TRANSIENT) {
            finishTransientFailure(event, failure);
        } else {
            finishPermanentFailure(event, failure);
        }
        return Outcome.STOPPED;
    }

    private Set<OutboxSink> requiredSinks(OutboxEvent event) {
        EnumSet<OutboxSink> required = EnumSet.noneOf(OutboxSink.class);
        if (mongoEnabled) {
            required.add(OutboxSink.MONGO);
        }
        if (neo4jEnabled && isNeo4jEvent(event.getEventType())) {
            required.add(OutboxSink.NEO4J);
        }
        return required;
    }

    private static boolean isNeo4jEvent(String eventType) {
        return NEO4J_EVENT_TYPES.stream().anyMatch(type -> type.equalsIgnoreCase(eventType));
    }

    /** All permits or none: a store that refuses means the event waits, and permits already taken are handed back. */
    private Map<OutboxSink, SinkBreaker.Permit> acquirePermits(Set<OutboxSink> required, Instant now) {
        Map<OutboxSink, SinkBreaker.Permit> permits = new EnumMap<>(OutboxSink.class);
        for (OutboxSink sink : required) {
            SinkBreaker.Permit permit = breakers.get(sink).tryAcquire(now);
            if (permit == null) {
                releasePermits(permits);
                return null;
            }
            permits.put(sink, permit);
        }
        return permits;
    }

    private void releasePermits(Map<OutboxSink, SinkBreaker.Permit> permits) {
        permits.forEach((sink, permit) -> breakers.get(sink).release(permit));
        permits.clear();
    }

    /** Feeds one projection's result to that store's breaker and consumes its permit. Returns the failure, or null on success. */
    private SinkFailure settle(OutboxSink sink, Map<OutboxSink, SinkBreaker.Permit> permits, SinkFailure result) {
        SinkBreaker breaker = breakers.get(sink);
        SinkBreaker.Permit permit = permits.remove(sink);
        Instant now = clock.instant();
        if (result == null) {
            breaker.recordSuccess(now);
        } else if (result.kind() == FailureKind.TRANSIENT) {
            breaker.recordFailure(permit, now, result.message());
        } else if (result.reachedSink()) {
            breaker.recordSuccess(now); // the store answered, it just refused this event
        } else {
            breaker.release(permit);     // the store was never called (bad payload, missing bean): proves nothing about it
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------------------- outcomes

    private Outcome finishSuccess(OutboxEvent event) {
        Instant now = clock.instant();
        try {
            int updated = leaseStore == null ? outboxEventRepository.markProcessed(event.getId(), now)
                    : leaseStore.processed(event.getId(), event.getLeaseToken(), now);
            if (updated <= 0) {
                // Reclaimed as stale while we were projecting; the projections are idempotent, so the re-run is harmless.
                log.warn("Outbox event {} was projected but is no longer PROCESSING (reclaimed); leaving its state to the new owner", event.getId());
                return Outcome.STOPPED;
            }
        } catch (Exception dbEx) {
            log.error("Failed to update status for outbox event {}", event.getId(), dbEx);
            return Outcome.STOPPED; // stays PROCESSING; the stale reclaim re-queues it and the idempotent projections make that safe
        }
        event.setStatus("PROCESSED");
        event.setProcessedAt(now);
        event.setErrorMessage(null);
        event.setFailureKind(null);
        return Outcome.PROCESSED;
    }

    private void finishTransientFailure(OutboxEvent event, SinkFailure failure) {
        // The dependency is down, not the event: back to PENDING, retry_count untouched, so it can never dead-letter because of this.
        String message = truncate("[TRANSIENT] " + failure.message());
        try {
            if (leaseStore == null) outboxEventRepository.markTransientFailure(event.getId(), message);
            else if (leaseStore.transientFailure(event.getId(), event.getLeaseToken(), message) == 0) return;
        } catch (Exception dbEx) {
            log.error("Failed to release outbox event {} after a transient failure", event.getId(), dbEx);
        }
        event.setStatus("PENDING");
        event.setErrorMessage(message);
        event.setFailureKind(FailureKind.TRANSIENT.name());
        log.debug("Outbox event {} deferred (transient): {}", event.getId(), failure.message());
    }

    private void finishPermanentFailure(OutboxEvent event, SinkFailure failure) {
        Instant now = clock.instant();
        int nextRetry = event.getRetryCount() + 1;
        boolean deadLetter = nextRetry >= properties.getMaxRetries();
        String status = deadLetter ? "DEAD_LETTER" : "PENDING"; // PENDING: next backoff retry
        String message = truncate(failure.message());
        try {
            if (leaseStore == null) outboxEventRepository.markPermanentFailure(event.getId(), status, nextRetry, message, now);
            else if (leaseStore.permanentFailure(event.getId(), event.getLeaseToken(), status, nextRetry, message, now) == 0) return;
        } catch (Exception dbEx) {
            log.error("Failed to update status for outbox event {}", event.getId(), dbEx);
        }
        event.setRetryCount(nextRetry);
        event.setStatus(status);
        event.setErrorMessage(message);
        event.setFailureKind(FailureKind.PERMANENT.name());
        event.setProcessedAt(now);
        if (deadLetter) {
            log.warn("Outbox event {} ({} {}) moved to DEAD_LETTER after {} permanent failures: {}",
                    event.getId(), event.getAggregateType(), event.getEventType(), nextRetry, message);
        } else {
            log.warn("Outbox event {} ({} {}) failed permanently ({}/{}), retrying with back-off: {}",
                    event.getId(), event.getAggregateType(), event.getEventType(), nextRetry, properties.getMaxRetries(), message);
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_STORED_MESSAGE ? message.substring(0, MAX_STORED_MESSAGE) : message;
    }

    private void releaseClaim(OutboxEvent event) {
        if (leaseStore == null) outboxEventRepository.releaseClaim(event.getId());
        else leaseStore.release(event.getId(), event.getLeaseToken());
    }

    // ------------------------------------------------------------------------------------------------------ projections

    /** A failed projection. {@code reachedSink} is false when the failure happened before the store was called. */
    private record SinkFailure(FailureKind kind, String message, boolean reachedSink) {
    }

    private SinkFailure projectToMongo(OutboxEvent event) {
        if (learningEventRepository == null) {
            log.warn("MongoDB projection is enabled but LearningEventRepository is unavailable for event {}", event.getId());
            return new SinkFailure(FailureKind.PERMANENT, "MongoDB repository unavailable (projection incomplete)", false);
        }
        LearningEventDocument doc;
        try {
            JsonNode payload = event.getPayloadJson() == null || event.getPayloadJson().isBlank()
                    ? objectMapper.createObjectNode() : objectMapper.readTree(event.getPayloadJson());
            doc = new LearningEventDocument(
                    event.getId(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getEventType(),
                    event.getPayloadJson(),
                    event.getCreatedAt(),
                    text(payload, "userId"), text(payload, "classId"),
                    text(payload, "courseId"), text(payload, "lessonId")
            );
        } catch (Exception badPayload) {
            log.warn("MongoDB projection cannot read the payload of event {}: {}", event.getId(), badPayload.getMessage());
            return new SinkFailure(FailureKind.PERMANENT, "MongoDB: invalid payload: " + badPayload.getMessage(), false);
        }
        try {
            learningEventRepository.save(doc);
            return null;
        } catch (Exception mongoEx) {
            FailureKind kind = OutboxFailureClassifier.classify(mongoEx);
            if (kind == FailureKind.PERMANENT) {
                log.warn("MongoDB projection failed for event {}: {}", event.getId(), mongoEx.getMessage());
            }
            return new SinkFailure(kind, "MongoDB: " + mongoEx.getMessage(), kind == FailureKind.PERMANENT);
        }
    }

    private SinkFailure projectToNeo4j(OutboxEvent event) {
        if (neo4jSyncService == null) {
            log.warn("Neo4j projection is enabled but Neo4jSyncService is unavailable for event {}", event.getId());
            return new SinkFailure(FailureKind.PERMANENT, "Neo4j sync service unavailable", false);
        }
        try {
            String userId = null;
            String classId = event.getAggregateId();
            if (event.getPayloadJson() != null && !event.getPayloadJson().isBlank()) {
                try {
                    JsonNode node = objectMapper.readTree(event.getPayloadJson());
                    if (node.has("userId")) userId = node.get("userId").asText();
                    if (node.has("classId")) classId = node.get("classId").asText();
                } catch (Exception ignored) {
                    // an unreadable payload falls back to the aggregate id, as before
                }
            }
            if (userId == null) {
                userId = event.getAggregateId();
            }
            if (MEMBERSHIP_ENDED_EVENT_TYPES.stream().anyMatch(type -> type.equalsIgnoreCase(event.getEventType()))) {
                if (leaseStore == null) neo4jSyncService.removeUserClassMembership(userId, classId);
                else neo4jSyncService.applyMembership(userId, classId, event.getSequenceNo(), false);
            } else {
                if (leaseStore == null) neo4jSyncService.syncUserClassMembership(userId, classId);
                else neo4jSyncService.applyMembership(userId, classId, event.getSequenceNo(), true);
            }
            return null;
        } catch (Exception neoEx) {
            FailureKind kind = OutboxFailureClassifier.classify(neoEx);
            if (kind == FailureKind.PERMANENT) {
                log.warn("Neo4j projection failed for event {}: {}", event.getId(), neoEx.getMessage());
            }
            return new SinkFailure(kind, "Neo4j: " + neoEx.getMessage(), kind == FailureKind.PERMANENT);
        }
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    // ----------------------------------------------------------------------------------------------------- visibility

    /** State of the per-store "dependency down" breakers, for the health indicator, the studio status endpoint and the log line. */
    public List<SinkStatus> sinkStatuses() {
        List<SinkStatus> statuses = new ArrayList<>();
        for (OutboxSink sink : OutboxSink.values()) {
            SinkBreaker.Status s = breakers.get(sink).status();
            boolean enabled = sink == OutboxSink.MONGO ? mongoEnabled : neo4jEnabled;
            statuses.add(new SinkStatus(sink.name(), enabled, s.up(), s.state().name(), s.consecutiveFailures(),
                    s.downSince(), s.nextProbeAt(), s.lastError()));
        }
        return statuses;
    }

    // ---------------------------------------------------------------------------------------------------------- replay

    /**
     * Operational replay and reconciliation method to re-queue DEAD_LETTER and FAILED events (Finding 11). A manual replay also
     * gives the event a fresh automatic-replay budget.
     */
    @Transactional
    public int replayFailedEvents(String classId) {
        List<OutboxEvent> candidates = outboxEventRepository.findByStatusInOrderBySequenceNoAsc(
                List.of("DEAD_LETTER", "FAILED"), PageRequest.of(0, 5000));
        List<OutboxEvent> toReplay = candidates.stream()
                .filter(event -> classId == null || belongsToClass(event, classId))
                .limit(50)
                .toList();
        return requeue(toReplay);
    }

    /** Compatibility for isolated worker tests; no production endpoint calls this unscoped form. */
    public int replayFailedEvents() {
        List<OutboxEvent> events = new ArrayList<>(outboxEventRepository.findTop50ByStatusOrderBySequenceNoAsc("DEAD_LETTER"));
        events.addAll(outboxEventRepository.findTop50ByStatusOrderBySequenceNoAsc("FAILED"));
        return requeue(events);
    }

    private int requeue(List<OutboxEvent> events) {
        for (OutboxEvent event : events) {
            event.setStatus("PENDING");
            event.setRetryCount(0);
            event.setProcessedAt(null);
            event.setFailureKind(null);
            event.setAutoReplayCount(0);
            event.setErrorMessage("Replayed for reconciliation");
            outboxEventRepository.save(event);
        }
        return events.size();
    }

    /**
     * Whether the event belongs to the class: by the {@code classId} of its payload, else by being a CLASSROOM aggregate event of
     * that class. Shared by the studio replay and the studio status view so both talk about the same set of events.
     */
    static boolean belongsToClass(OutboxEvent event, String classId) {
        if (event.getPayloadJson() != null) {
            try {
                JsonNode payload = PAYLOAD_READER.readTree(event.getPayloadJson());
                if (payload.hasNonNull("classId")) return classId.equals(payload.get("classId").asText());
            } catch (Exception ignored) { return false; }
        }
        return "CLASSROOM".equalsIgnoreCase(event.getAggregateType()) && classId.equals(event.getAggregateId());
    }
}
