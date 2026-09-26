package com.classroom.modules.outbox.worker;

import com.classroom.modules.outbox.model.OutboxEvent;
import com.classroom.modules.outbox.repository.OutboxEventRepository;
import com.classroom.modules.projection.mongo.LearningEventDocument;
import com.classroom.modules.projection.mongo.LearningEventRepository;
import com.classroom.modules.projection.neo4j.Neo4jSyncService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class OutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final OutboxEventRepository outboxEventRepository;
    private final Neo4jSyncService neo4jSyncService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Object workerLock = new Object();

    @Autowired(required = false)
    private LearningEventRepository learningEventRepository;

    @Value("${classroom.projection.mongo.enabled:true}")
    private boolean mongoEnabled = true;

    @Value("${classroom.projection.neo4j.enabled:true}")
    private boolean neo4jEnabled = true;

    public OutboxWorker(OutboxEventRepository outboxEventRepository,
                        Neo4jSyncService neo4jSyncService) {
        this.outboxEventRepository = outboxEventRepository;
        this.neo4jSyncService = neo4jSyncService;
    }

    /**
     * Non-transactional scheduled worker with synchronized execution and exponential backoff
     * to prevent race conditions across concurrent threads and avoid retry burning (Finding 11).
     * Projections are tracked independently and an event is marked PROCESSED only when all required
     * enabled projections succeed. Retains dead-letter events for reconciliation replay.
     */
    @Scheduled(fixedDelay = 2000)
    public void processOutboxEvents() {
        synchronized (workerLock) {
            Instant now = Instant.now();

            // Reclaim stale PROCESSING events from crashed workers (stale threshold: 120s)
            try {
                outboxEventRepository.resetStaleProcessingEvents(now.minusSeconds(120));
            } catch (Exception ex) {
                log.warn("Failed to reset stale processing events: {}", ex.getMessage());
            }

            // Exclude backoff and causally blocked rows in SQL so either kind cannot occupy
            // every slot in the batch and starve unrelated aggregates behind them.
            List<OutboxEvent> pendingEvents = outboxEventRepository.findEligiblePendingEvents(
                    now, PageRequest.of(0, 50));
            if (pendingEvents.isEmpty()) return;

            Set<String> blockedAggregates = new HashSet<>();

            for (OutboxEvent event : pendingEvents) {
                String aggregateKey = event.getAggregateType() + ":" + event.getAggregateId();

                // 1. Strict causal order per aggregate: skip newer events if earlier event is backing off, in-flight, or failed
                if (blockedAggregates.contains(aggregateKey)) {
                    continue;
                }

                // The ordering gate is only sound with a sequence number. An event without one
                // cannot be placed relative to its siblings, so fail closed rather than risk
                // applying it out of order.
                Long sequenceNo = event.getSequenceNo();
                if (sequenceNo == null) {
                    log.warn("Outbox event {} has no sequence number; deferring to preserve per-aggregate order", event.getId());
                    blockedAggregates.add(aggregateKey);
                    continue;
                }

                // 2. Strict per-aggregate causal gate. An event may only run when every strictly
                //    earlier event for the same aggregate has reached PROCESSED. This covers earlier
                //    events outside this batch window and earlier events parked in PROCESSING,
                //    FAILED or DEAD_LETTER, so a later MEMBER_JOINED can never be projected ahead of
                //    an earlier failed MEMBER_REMOVED and be undone by a later replay of it.
                try {
                    if (outboxEventRepository.existsEarlierUncompletedEvent(
                            event.getAggregateType(), event.getAggregateId(), sequenceNo)) {
                        blockedAggregates.add(aggregateKey);
                        continue;
                    }
                } catch (Exception gateFailure) {
                    // Fail closed: without a usable ordering gate, do not risk an out-of-order apply.
                    log.warn("Ordering gate check failed for outbox event {}: {}", event.getId(), gateFailure.getMessage());
                    blockedAggregates.add(aggregateKey);
                    continue;
                }

                // 3. Exponential backoff check: avoid retrying prematurely
                if (event.getRetryCount() > 0 && event.getProcessedAt() != null) {
                    long backoffSeconds = Math.min(300, (long) Math.pow(2, event.getRetryCount()));
                    if (event.getProcessedAt().plusSeconds(backoffSeconds).isAfter(now)) {
                        blockedAggregates.add(aggregateKey);
                        continue;
                    }
                }

                // 4. Distributed atomic claim across workers
                int claimed = 0;
                try {
                    claimed = outboxEventRepository.claimEvent(event.getId(), now);
                } catch (Exception ex) {
                    log.warn("Failed to claim outbox event {}: {}", event.getId(), ex.getMessage());
                }
                if (claimed <= 0) {
                    blockedAggregates.add(aggregateKey);
                    continue;
                }

                // Mark aggregate blocked for the rest of this batch so newer events for same aggregate do not run ahead
                blockedAggregates.add(aggregateKey);

                // 5. Re-verify ordering after the claim. Another worker may have claimed an earlier
                //    event for this aggregate between the gate check and our claim; release and retry.
                try {
                    if (outboxEventRepository.existsEarlierUncompletedEvent(
                            event.getAggregateType(), event.getAggregateId(), sequenceNo)) {
                        outboxEventRepository.releaseClaim(event.getId());
                        continue;
                    }
                } catch (Exception recheckFailure) {
                    log.warn("Post-claim ordering recheck failed for outbox event {}: {}", event.getId(), recheckFailure.getMessage());
                    try {
                        outboxEventRepository.releaseClaim(event.getId());
                    } catch (Exception ignored) {}
                    continue;
                }

                boolean projectionSuccess = true;
                String lastError = null;

                // 1. Idempotent projection to MongoDB if enabled (Finding 6)
                if (mongoEnabled) {
                    if (learningEventRepository == null) {
                        log.warn("MongoDB projection is enabled but LearningEventRepository is unavailable for event {}", event.getId());
                        projectionSuccess = false;
                        lastError = "MongoDB repository unavailable (projection incomplete)";
                    } else {
                        try {
                            JsonNode payload = event.getPayloadJson() == null || event.getPayloadJson().isBlank()
                                    ? objectMapper.createObjectNode() : objectMapper.readTree(event.getPayloadJson());
                            LearningEventDocument doc = new LearningEventDocument(
                                    event.getId(),
                                    event.getAggregateType(),
                                    event.getAggregateId(),
                                    event.getEventType(),
                                    event.getPayloadJson(),
                                    event.getCreatedAt(),
                                    text(payload, "userId"), text(payload, "classId"),
                                    text(payload, "courseId"), text(payload, "lessonId")
                            );
                            learningEventRepository.save(doc);
                        } catch (Exception mongoEx) {
                            log.error("MongoDB projection failed for event {}: {}", event.getId(), mongoEx.getMessage());
                            projectionSuccess = false;
                            lastError = "MongoDB: " + mongoEx.getMessage();
                        }
                    }
                }

                // 2. Idempotent projection to Neo4j if enabled (Finding 6, 8)
                if (neo4jEnabled && ("MEMBER_JOINED".equalsIgnoreCase(event.getEventType())
                        || "MEMBER_REMOVED".equalsIgnoreCase(event.getEventType()))) {
                    if (neo4jSyncService == null) {
                        log.warn("Neo4j projection is enabled but Neo4jSyncService is unavailable for event {}", event.getId());
                        projectionSuccess = false;
                        lastError = (lastError != null ? lastError + "; " : "") + "Neo4j sync service unavailable";
                    } else {
                        try {
                            String userId = null;
                            String classId = event.getAggregateId();
                            if (event.getPayloadJson() != null && !event.getPayloadJson().isBlank()) {
                                try {
                                    JsonNode node = objectMapper.readTree(event.getPayloadJson());
                                    if (node.has("userId")) userId = node.get("userId").asText();
                                    if (node.has("classId")) classId = node.get("classId").asText();
                                } catch (Exception ignored) {}
                            }
                            if (userId == null) {
                                userId = event.getAggregateId();
                            }
                            if ("MEMBER_REMOVED".equalsIgnoreCase(event.getEventType())) {
                                neo4jSyncService.removeUserClassMembership(userId, classId);
                            } else {
                                neo4jSyncService.syncUserClassMembership(userId, classId);
                            }
                        } catch (Exception neoEx) {
                            log.error("Neo4j projection failed for event {}: {}", event.getId(), neoEx.getMessage());
                            projectionSuccess = false;
                            lastError = (lastError != null ? lastError + "; " : "") + "Neo4j: " + neoEx.getMessage();
                        }
                    }
                }

                // 3. Complete only when each required projection succeeds (Finding 6, 11)
                if (projectionSuccess) {
                    event.setStatus("PROCESSED");
                    event.setProcessedAt(Instant.now());
                    event.setErrorMessage(null);
                } else {
                    int nextRetry = event.getRetryCount() + 1;
                    event.setRetryCount(nextRetry);
                    event.setErrorMessage(lastError);
                    event.setProcessedAt(now);
                    if (nextRetry >= 5) {
                        event.setStatus("DEAD_LETTER");
                        log.warn("Outbox event {} moved to DEAD_LETTER after {} retries: {}", event.getId(), nextRetry, lastError);
                    } else {
                        event.setStatus("PENDING"); // Stays PENDING for next backoff retry
                    }
                }

                try {
                    outboxEventRepository.save(event);
                } catch (Exception dbEx) {
                    log.error("Failed to update status for outbox event {}", event.getId(), dbEx);
                }
            }
        }
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    /**
     * Operational replay and reconciliation method to re-queue DEAD_LETTER and FAILED events (Finding 11).
     */
    @Transactional
    public int replayFailedEvents(String classId) {
        synchronized (workerLock) {
            List<OutboxEvent> candidates = outboxEventRepository.findByStatusInOrderBySequenceNoAsc(List.of("DEAD_LETTER", "FAILED"));
            List<OutboxEvent> toReplay = candidates.stream().filter(event -> classId == null || belongsToClass(event, classId)).limit(50).toList();

            for (OutboxEvent event : toReplay) {
                event.setStatus("PENDING");
                event.setRetryCount(0);
                event.setProcessedAt(null);
                event.setErrorMessage("Replayed for reconciliation");
                outboxEventRepository.save(event);
            }
            return toReplay.size();
        }
    }

    /** Compatibility for isolated worker tests; no production endpoint calls this unscoped form. */
    public int replayFailedEvents() {
        synchronized (workerLock) {
            List<OutboxEvent> events = new ArrayList<>(outboxEventRepository.findTop50ByStatusOrderBySequenceNoAsc("DEAD_LETTER"));
            events.addAll(outboxEventRepository.findTop50ByStatusOrderBySequenceNoAsc("FAILED"));
            return requeue(events);
        }
    }

    private int requeue(List<OutboxEvent> events) {
        for (OutboxEvent event : events) {
            event.setStatus("PENDING"); event.setRetryCount(0); event.setProcessedAt(null);
            event.setErrorMessage("Replayed for reconciliation"); outboxEventRepository.save(event);
        }
        return events.size();
    }

    private boolean belongsToClass(OutboxEvent event, String classId) {
        if (event.getPayloadJson() != null) {
            try {
                JsonNode payload = objectMapper.readTree(event.getPayloadJson());
                if (payload.hasNonNull("classId")) return classId.equals(payload.get("classId").asText());
            } catch (Exception ignored) { return false; }
        }
        return "CLASSROOM".equalsIgnoreCase(event.getAggregateType()) && classId.equals(event.getAggregateId());
    }
}
