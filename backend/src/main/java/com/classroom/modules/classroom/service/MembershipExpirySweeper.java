package com.classroom.modules.classroom.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * D-19: flips paid members whose access ran out from ACTIVE to EXPIRED (and emits MEMBER_EXPIRED) on a timer.
 *
 * <p><b>Threading.</b> Deliberately NOT a {@code @Scheduled} method: the shared scheduler pool is sized to the number of scheduled jobs (a
 * test enforces it) and one blocked job must not delay the others (R20-04a). The sweep runs on its own single daemon thread
 * ({@code membership-expiry}), one run at a time, each run a series of short transactions in {@link MembershipExpiryService} - it never
 * nests a transaction inside another and never runs inside an {@code afterCommit} hook. A run is capped at {@link #MAX_BATCHES_PER_RUN}
 * batches so a huge backlog (for instance after downtime) is worked off over several runs instead of monopolising a connection.</p>
 *
 * <p>The sweep is an optimisation of bookkeeping, not of security: a member whose date has passed is already treated as a non-member by
 * {@code AccessPolicy.isMember} and by every "active member" query, swept or not.</p>
 *
 * <p>Configuration ({@code classroom.membership.expiry.*}): {@code enabled}, {@code interval-seconds} (delay between runs, default 60),
 * {@code batch-size} (rows per transaction, default 200).</p>
 */
@Component
public class MembershipExpirySweeper {
    private static final Logger log = LoggerFactory.getLogger(MembershipExpirySweeper.class);

    static final int MAX_BATCHES_PER_RUN = 50;

    private final MembershipExpiryService expiryService;
    private final boolean enabled;
    private final long intervalSeconds;
    private final int batchSize;
    private ScheduledExecutorService executor;

    public MembershipExpirySweeper(MembershipExpiryService expiryService,
                                   @Value("${classroom.membership.expiry.enabled:true}") boolean enabled,
                                   @Value("${classroom.membership.expiry.interval-seconds:60}") long intervalSeconds,
                                   @Value("${classroom.membership.expiry.batch-size:200}") int batchSize) {
        this.expiryService = expiryService;
        this.enabled = enabled;
        this.intervalSeconds = Math.max(1, intervalSeconds);
        this.batchSize = Math.max(1, batchSize);
    }

    @PostConstruct
    void start() {
        if (!enabled || executor != null) {
            return;
        }
        AtomicInteger seq = new AtomicInteger();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "membership-expiry-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        // fixedDelay: a run that overruns never overlaps the next one.
        executor.scheduleWithFixedDelay(this::runQuietly, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void runQuietly() {
        try {
            sweepNow();
        } catch (RuntimeException e) {
            // A database hiccup must not kill the schedule: the next run retries from scratch.
            log.warn("Membership expiry sweep failed and will be retried: {}", e.toString());
        }
    }

    /**
     * One run: batches until a batch comes back short or {@link #MAX_BATCHES_PER_RUN} is reached. Public so an operator-facing job or a test
     * can drive it.
     *
     * @return how many members were flipped to EXPIRED
     */
    public int sweepNow() {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            MembershipExpiryService.BatchResult batch = expiryService.sweepBatch(batchSize, Instant.now());
            total += batch.expired();
            if (batch.examined() < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("Membership expiry sweep: {} member(s) moved to EXPIRED", total);
        }
        return total;
    }
}
