package com.classroom.modules.ranking.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * R20-01: the small, bounded worker pool that runs leaderboard recalculations <em>after</em> the publishing
 * transaction committed.
 *
 * <p>Why a separate pool: the recalculation used to run inside the publishing request's
 * {@code afterCommit} hook. Spring keeps the request transaction's JDBC connection until
 * {@code afterCompletion}, i.e. <em>after</em> the hooks, so every submitting request held one pooled connection while
 * asking for a second (the recalculation's own transaction) and a third (the leaderboard row creation). With N
 * concurrent submits and a pool of 10 connections the requests each held one connection and all waited for another:
 * a pool deadlock that froze the whole backend for the connection timeout. Handing the work to this executor makes
 * the {@code afterCommit} hook a pure in-memory operation - it never touches the database - and bounds the
 * recalculation load to {@code workers} connections no matter how many learners submit at once.</p>
 *
 * <p>Delivery guarantee is unchanged: the durable {@code leaderboard_recalc_jobs} row was committed with the score, so
 * a task that is dropped (queue full, shutdown, process death) is picked up by
 * {@link LeaderboardService#sweepPendingRecalculations()}. Tasks are de-duplicated per key while they are still
 * <em>queued</em> (a queued run reads every outstanding job row when it starts, so a second trigger adds nothing);
 * once a run has started, a new trigger queues another run, because the running one may already have read the jobs.</p>
 */
@Component
public class LeaderboardRecalcExecutor {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardRecalcExecutor.class);

    private final ThreadPoolExecutor executor;
    private final Set<String> queuedKeys = ConcurrentHashMap.newKeySet();
    private final AtomicLong dropped = new AtomicLong();

    public LeaderboardRecalcExecutor(
            @Value("${classroom.leaderboard.recalc.workers:3}") int workers,
            @Value("${classroom.leaderboard.recalc.queue-capacity:2000}") int queueCapacity) {
        int threads = Math.max(1, workers);
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread t = new Thread(runnable, "leaderboard-recalc-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)), factory, new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * Queues {@code task} unless a task with the same key is already waiting in the queue. Never blocks, never
     * throws and never touches the database: safe to call from a transaction synchronization callback.
     *
     * @return {@code true} if the task was queued or an equivalent one is already queued; {@code false} if it was
     *         dropped (queue full / shutting down) and is therefore left to the sweeper
     */
    public boolean submit(String key, Runnable task) {
        if (!queuedKeys.add(key)) {
            return true; // an equivalent run is still waiting and will observe this job as well
        }
        try {
            executor.execute(() -> {
                queuedKeys.remove(key); // from here on a new trigger must queue a fresh run
                try {
                    task.run();
                } catch (Throwable t) {
                    log.error("Leaderboard recalculation task {} failed unexpectedly", key, t);
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            queuedKeys.remove(key);
            long n = dropped.incrementAndGet();
            if (n == 1 || n % 100 == 0) {
                log.warn("Leaderboard recalculation queue is full or stopped ({} dropped so far); the durable job row "
                        + "will be retried by the sweeper", n);
            }
            return false;
        }
    }

    /** Test/diagnostic seam: number of tasks currently queued or running. */
    public int pending() {
        return executor.getQueue().size() + executor.getActiveCount();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
