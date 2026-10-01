package com.classroom.modules.outbox.worker;

import com.classroom.modules.outbox.model.OutboxEvent;
import com.classroom.modules.outbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator visibility of the outbox (R20-04): how many events wait, how many are dead-lettered, how old the oldest waiting one is and
 * which projection stores the worker currently considers down. Feeds the {@code outbox} health contributor
 * ({@code /actuator/health}, details for OPS), the periodic log line and the Studio status endpoint.
 *
 * <p>Counts are read with two cheap indexed queries and cached for {@code classroom.outbox.stats.cache-ms}, so a monitoring system
 * polling the health endpoint cannot hammer the table.
 */
@Component
public class OutboxMonitor {
    private static final Logger log = LoggerFactory.getLogger(OutboxMonitor.class);
    private static final List<String> OPEN_STATUSES = List.of("PENDING", "PROCESSING", "FAILED", "DEAD_LETTER");
    /** Upper bound of the rows examined for one class's status: the payload is read to attribute an event to a class. */
    private static final int CLASS_SCAN_LIMIT = 2000;

    /** Counts of the not-yet-finished events. {@code oldestPendingSeconds} is null when nothing is pending. */
    public record Snapshot(long pending, long processing, long failed, long deadLetter, Long oldestPendingSeconds, Instant takenAt) {
        public boolean needsAttention() {
            return deadLetter > 0 || failed > 0;
        }
    }

    private final OutboxEventRepository repository;
    private final OutboxWorker worker;
    private final OutboxProperties properties;
    private final Clock clock;
    private volatile Snapshot cached;
    private volatile boolean wasBusy;

    @Autowired
    public OutboxMonitor(OutboxEventRepository repository, OutboxWorker worker, OutboxProperties properties) {
        this(repository, worker, properties, Clock.systemUTC());
    }

    OutboxMonitor(OutboxEventRepository repository, OutboxWorker worker, OutboxProperties properties, Clock clock) {
        this.repository = repository;
        this.worker = worker;
        this.properties = properties;
        this.clock = clock;
    }

    /** Current counts (cached briefly). */
    public Snapshot snapshot() {
        Instant now = clock.instant();
        Snapshot current = cached;
        if (current != null && Duration.between(current.takenAt(), now).toMillis() < properties.getStats().getCacheMs()) {
            return current;
        }
        long pending = 0;
        long processing = 0;
        long failed = 0;
        long deadLetter = 0;
        for (Object[] row : repository.countByStatuses(OPEN_STATUSES)) {
            String status = String.valueOf(row[0]);
            long count = ((Number) row[1]).longValue();
            switch (status) {
                case "PENDING" -> pending = count;
                case "PROCESSING" -> processing = count;
                case "FAILED" -> failed = count;
                case "DEAD_LETTER" -> deadLetter = count;
                default -> { }
            }
        }
        Long oldestSeconds = null;
        if (pending > 0) {
            oldestSeconds = repository.findFirstByStatusOrderBySequenceNoAsc("PENDING")
                    .map(OutboxEvent::getCreatedAt)
                    .map(created -> Math.max(0, Duration.between(created, now).getSeconds()))
                    .orElse(null);
        }
        Snapshot fresh = new Snapshot(pending, processing, failed, deadLetter, oldestSeconds, now);
        cached = fresh;
        return fresh;
    }

    public List<SinkStatus> sinks() {
        return worker.sinkStatuses();
    }

    /**
     * The same numbers restricted to one class, for the Studio owner/staff who may replay it. An event is attributed to a class
     * exactly like the replay does ({@link OutboxWorker#belongsToClass}), by its payload's {@code classId} or the CLASSROOM aggregate.
     */
    public Map<String, Object> classStatus(String classId) {
        List<OutboxEvent> open = repository.findByStatusInOrderBySequenceNoAsc(OPEN_STATUSES, PageRequest.of(0, CLASS_SCAN_LIMIT));
        long pending = 0;
        long processing = 0;
        long failed = 0;
        long deadLetter = 0;
        for (OutboxEvent event : open) {
            if (!OutboxWorker.belongsToClass(event, classId)) {
                continue;
            }
            switch (event.getStatus()) {
                case "PENDING" -> pending++;
                case "PROCESSING" -> processing++;
                case "FAILED" -> failed++;
                case "DEAD_LETTER" -> deadLetter++;
                default -> { }
            }
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("classId", classId);
        status.put("pending", pending);
        status.put("processing", processing);
        status.put("failed", failed);
        status.put("deadLetter", deadLetter);
        status.put("truncated", open.size() >= CLASS_SCAN_LIMIT);
        Map<String, Object> sinks = new LinkedHashMap<>();
        for (SinkStatus sink : sinks()) {
            sinks.put(sink.sink().toLowerCase(), sink.enabled() ? (sink.up() ? "UP" : "DOWN") : "DISABLED");
        }
        status.put("sinks", sinks);
        return status;
    }

    /** One line per minute while there is something to say; identical consecutive quiet lines are not repeated. */
    @Scheduled(fixedDelayString = "${classroom.outbox.stats.log-interval-ms:60000}",
            initialDelayString = "${classroom.outbox.stats.log-interval-ms:60000}")
    public void logStatus() {
        try {
            Snapshot s = snapshot();
            List<SinkStatus> sinkStatuses = sinks();
            boolean sinkDown = sinkStatuses.stream().anyMatch(x -> x.enabled() && !x.up());
            String sinkText = sinkStatuses.stream()
                    .map(x -> x.sink().toLowerCase() + "=" + (x.enabled() ? (x.up() ? "UP" : "DOWN") : "DISABLED"))
                    .reduce((a, b) -> a + " " + b).orElse("");
            String line = String.format("Outbox: pending=%d processing=%d failed=%d deadLetter=%d oldestPendingSeconds=%s %s",
                    s.pending(), s.processing(), s.failed(), s.deadLetter(),
                    s.oldestPendingSeconds() == null ? "-" : s.oldestPendingSeconds().toString(), sinkText);
            boolean busy = s.pending() > 0 || s.processing() > 0;
            if (s.deadLetter() > 0 || s.failed() > 0) {
                log.warn("{} - dead letters are re-driven automatically up to {} time(s) per event, then need a manual replay "
                        + "(Studio > outbox replay)", line, properties.getRedrive().getMaxAutoReplays());
            } else if (sinkDown) {
                log.warn("{} - a projection store is unreachable: events wait as PENDING (nothing is dead-lettered) and drain "
                        + "automatically once it answers", line);
            } else if (busy) {
                log.info(line);
            } else if (wasBusy) {
                log.info("{} (drained)", line);
            }
            wasBusy = busy || s.deadLetter() > 0 || sinkDown;
        } catch (Exception ex) {
            log.debug("Outbox status log skipped: {}", ex.getMessage());
        }
    }
}
