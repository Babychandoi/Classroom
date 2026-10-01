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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Automatic re-drive of dead-lettered outbox events (R20-04b), so an outage never needs a human to click "replay" in Studio.
 *
 * <p>A dead letter is replayed when it is the HEAD of its aggregate's order (replaying anything behind a stuck event is pointless
 * because the ordering gate would hold it) and either
 * <ul>
 *   <li>its cause was TRANSIENT (recorded {@code failure_kind}, or - for rows written before V36 - recognised from the stored error
 *       text: "Unable to connect", "timed out", ...) and it has been dead for {@code transient-after-seconds} (default 60), or</li>
 *   <li>it has been dead for {@code older-than-minutes} (default 30), whatever the cause - a deployed fix, a restored store or an
 *       operator's data repair gets a chance to unblock it without manual work.</li>
 * </ul>
 * The rate is deliberately low and bounded: at most {@code batch-size} events per run (default 20 per minute), and at most
 * {@code max-auto-replays} automatic replays per event (default 3). After that the event stays {@code DEAD_LETTER} - visible in the
 * health indicator, the periodic log line and the Studio status endpoint - and only a manual replay (which resets the budget)
 * revives it. A genuinely poisonous event therefore blocks its own aggregate only, and never loops forever.
 */
@Component
public class OutboxRedriveJob {
    private static final Logger log = LoggerFactory.getLogger(OutboxRedriveJob.class);
    /** How many dead letters one run looks at; enough to find the replayable heads without loading an unbounded backlog. */
    private static final int SCAN_WINDOW = 200;

    private final OutboxEventRepository repository;
    private final OutboxProperties properties;
    private final Clock clock;

    @Autowired
    public OutboxRedriveJob(OutboxEventRepository repository, OutboxProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    OutboxRedriveJob(OutboxEventRepository repository, OutboxProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${classroom.outbox.redrive.interval-ms:60000}",
            initialDelayString = "${classroom.outbox.redrive.initial-delay-ms:30000}")
    public void redriveScheduled() {
        try {
            redriveDeadLetters();
        } catch (Exception ex) {
            log.warn("Outbox re-drive run failed (will retry on the next run): {}", ex.getMessage());
        }
    }

    /** One run. Returns the number of events put back into the queue. */
    public int redriveDeadLetters() {
        OutboxProperties.Redrive cfg = properties.getRedrive();
        if (!cfg.isEnabled() || cfg.getMaxAutoReplays() <= 0) {
            return 0;
        }
        Instant now = clock.instant();
        Duration transientAfter = Duration.ofSeconds(cfg.getTransientAfterSeconds());
        Duration olderThan = Duration.ofMinutes(cfg.getOlderThanMinutes());
        Duration minAge = transientAfter.compareTo(olderThan) < 0 ? transientAfter : olderThan;

        List<OutboxEvent> candidates = repository.findRedriveCandidates(
                cfg.getMaxAutoReplays(), now.minus(minAge), PageRequest.of(0, SCAN_WINDOW));
        Set<String> aggregatesSeen = new HashSet<>();
        int replayed = 0;
        for (OutboxEvent event : candidates) {
            if (replayed >= cfg.getBatchSize()) {
                break;
            }
            // Only the first dead letter of an aggregate can matter: everything behind it is gated by it.
            if (!aggregatesSeen.add(event.getAggregateType() + ":" + event.getAggregateId())) {
                continue;
            }
            if (!eligible(event, now, transientAfter, olderThan) || !isHeadOfAggregate(event)) {
                continue;
            }
            int attempt = event.getAutoReplayCount() + 1;
            String previous = event.getErrorMessage() == null ? "" : " | previous: " + abbreviate(event.getErrorMessage(), 300);
            String message = "[auto-replay " + attempt + "/" + cfg.getMaxAutoReplays() + "]" + previous;
            if (repository.autoReplay(event.getId(), message) > 0) {
                replayed++;
                log.info("Outbox re-drive: replaying dead-lettered event {} ({} {}, auto-replay {}/{}, cause {})",
                        event.getId(), event.getAggregateType(), event.getEventType(), attempt, cfg.getMaxAutoReplays(),
                        causeOf(event));
            }
        }
        return replayed;
    }

    private boolean eligible(OutboxEvent event, Instant now, Duration transientAfter, Duration olderThan) {
        Duration dead = event.getProcessedAt() == null ? Duration.ofDays(3650) : Duration.between(event.getProcessedAt(), now);
        if (dead.compareTo(olderThan) >= 0) {
            return true;
        }
        return causeOf(event) == FailureKind.TRANSIENT && dead.compareTo(transientAfter) >= 0;
    }

    /** The recorded failure kind, or - for rows that predate V36 - the kind recognised from the stored error text. */
    static FailureKind causeOf(OutboxEvent event) {
        if (event.getFailureKind() != null) {
            try {
                return FailureKind.valueOf(event.getFailureKind());
            } catch (IllegalArgumentException ignored) {
                // fall through to the text
            }
        }
        return OutboxFailureClassifier.classifyMessage(event.getErrorMessage());
    }

    private boolean isHeadOfAggregate(OutboxEvent event) {
        Long sequenceNo = event.getSequenceNo();
        return sequenceNo != null
                && !repository.existsEarlierUncompletedEvent(event.getAggregateType(), event.getAggregateId(), sequenceNo);
    }

    private static String abbreviate(String text, int max) {
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }
}
