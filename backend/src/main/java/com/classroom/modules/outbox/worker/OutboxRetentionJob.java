package com.classroom.modules.outbox.worker;

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
import java.util.List;

/**
 * Retention of the outbox (R20-05): PROCESSED events older than {@code classroom.outbox.retention.days} (default 7) are deleted in
 * small batches so the table - and with it every poll, gate check and index - stays small. Only PROCESSED rows are ever removed;
 * PENDING, PROCESSING, FAILED and DEAD_LETTER rows are the operators' work queue and are never purged.
 *
 * <p>The delete is deliberately gentle: {@code batch-size} rows per statement (ids found through the (status, processed_at) index,
 * deleted by primary key), a pause between batches and a time budget per run, the remainder being picked up by the next run. A first
 * run over a 300 000-row backlog therefore takes a few minutes instead of one long lock-holding statement, without ever blocking the
 * projection workers.
 */
@Component
public class OutboxRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(OutboxRetentionJob.class);

    private final OutboxEventRepository repository;
    private final OutboxProperties properties;
    private final Clock clock;

    @Autowired
    public OutboxRetentionJob(OutboxEventRepository repository, OutboxProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    OutboxRetentionJob(OutboxEventRepository repository, OutboxProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${classroom.outbox.retention.interval-ms:60000}",
            initialDelayString = "${classroom.outbox.retention.initial-delay-ms:60000}")
    public void purgeScheduled() {
        try {
            purgeExpired();
        } catch (Exception ex) {
            log.warn("Outbox retention run failed (will retry on the next run): {}", ex.getMessage());
        }
    }

    /** One run: deletes expired PROCESSED events in batches until none are left or the time budget is spent. Returns the row count. */
    public int purgeExpired() {
        OutboxProperties.Retention cfg = properties.getRetention();
        if (!cfg.isEnabled()) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(Duration.ofDays(cfg.getDays()));
        long deadlineNanos = System.nanoTime() + Duration.ofMillis(cfg.getMaxRunMs()).toNanos();
        PageRequest page = PageRequest.of(0, cfg.getBatchSize());
        int total = 0;
        while (true) {
            List<String> ids = repository.findProcessedIdsBefore(cutoff, page);
            if (ids.isEmpty()) {
                ids = repository.findProcessedWithoutTimestampCreatedBefore(cutoff, page);
            }
            if (ids.isEmpty()) {
                break;
            }
            int deleted = repository.deleteProcessedByIds(ids);
            total += deleted;
            if (deleted == 0 || System.nanoTime() - deadlineNanos >= 0) {
                break; // nothing removable (concurrently changed) or the budget of this run is spent
            }
            if (cfg.getPauseMs() > 0) {
                try {
                    Thread.sleep(cfg.getPauseMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (total > 0) {
            log.info("Outbox retention: purged {} PROCESSED event(s) older than {} day(s)", total, cfg.getDays());
        }
        return total;
    }
}
