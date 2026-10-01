package com.classroom.modules.outbox.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables of the outbox pipeline, bound from {@code classroom.outbox.*} (R20-04 / R20-05). Every value has a safe default; the
 * ones an operator might reasonably change are documented in docs/RUNBOOK.md ("Outbox").
 */
@Component
@ConfigurationProperties(prefix = "classroom.outbox")
public class OutboxProperties {

    /** Kill switch for the dispatch loop (the scheduled poll returns immediately). Retention / re-drive have their own switches. */
    private boolean enabled = true;
    /** Delay between two polls of the {@code outbox_events} table, in ms. Idle polls are a single indexed lookup. */
    private long pollDelayMs = 500;
    /** Threads of the dedicated projection executor - the only place that talks to MongoDB / Neo4j. */
    private int workers = 4;
    /** Aggregates queued behind busy workers before the dispatcher stops handing out more (the rest wait for the next poll). */
    private int queueCapacity = 32;
    /** Ready aggregates (heads of their per-aggregate order) fetched per poll. */
    private int dispatchBatch = 50;
    /** Consecutive events of ONE aggregate projected in a single pass, in order, stopping at the first failure. */
    private int maxEventsPerAggregatePerPass = 50;
    /** PERMANENT failures tolerated before an event is dead-lettered. Transient (dependency) failures never count. */
    private int maxRetries = 5;
    /** A PROCESSING row older than this belongs to a crashed worker and is reclaimed. */
    private long staleAfterSeconds = 120;
    /** How often the stale-PROCESSING reclaim runs (it is not needed on every poll). */
    private long staleScanIntervalMs = 15000;

    private final Breaker breaker = new Breaker();
    private final Retention retention = new Retention();
    private final Redrive redrive = new Redrive();
    private final Stats stats = new Stats();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getPollDelayMs() {
        return pollDelayMs;
    }

    public void setPollDelayMs(long pollDelayMs) {
        this.pollDelayMs = Math.max(50, pollDelayMs);
    }

    public int getWorkers() {
        return workers;
    }

    public void setWorkers(int workers) {
        this.workers = Math.max(1, workers);
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = Math.max(1, queueCapacity);
    }

    public int getDispatchBatch() {
        return dispatchBatch;
    }

    public void setDispatchBatch(int dispatchBatch) {
        this.dispatchBatch = Math.max(1, dispatchBatch);
    }

    public int getMaxEventsPerAggregatePerPass() {
        return maxEventsPerAggregatePerPass;
    }

    public void setMaxEventsPerAggregatePerPass(int maxEventsPerAggregatePerPass) {
        this.maxEventsPerAggregatePerPass = Math.max(1, maxEventsPerAggregatePerPass);
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = Math.max(1, maxRetries);
    }

    public long getStaleAfterSeconds() {
        return staleAfterSeconds;
    }

    public void setStaleAfterSeconds(long staleAfterSeconds) {
        this.staleAfterSeconds = Math.max(10, staleAfterSeconds);
    }

    public long getStaleScanIntervalMs() {
        return staleScanIntervalMs;
    }

    public void setStaleScanIntervalMs(long staleScanIntervalMs) {
        this.staleScanIntervalMs = Math.max(1000, staleScanIntervalMs);
    }

    public Breaker getBreaker() {
        return breaker;
    }

    public Retention getRetention() {
        return retention;
    }

    public Redrive getRedrive() {
        return redrive;
    }

    public Stats getStats() {
        return stats;
    }

    /** Back-off of the per-sink "dependency down" state ({@link SinkBreaker}). */
    public static class Breaker {
        /** First probe delay after a store went down, in ms; doubles per failed probe. */
        private long baseDelayMs = 2000;
        /**
         * Cap of the probe delay, in ms. A recovered store is noticed at most this long after it came back, so this bounds how long a
         * backlog waits after recovery; a probe is one short attempt (bounded by the driver's connect/selection timeout), so a small
         * cap is cheap. Raise it (up to a minute) only for a store that is genuinely flaky.
         */
        private long maxDelayMs = 15000;
        /** A probe that never reports back within this many ms is abandoned and another may start. */
        private long probeTimeoutMs = 30000;

        public long getBaseDelayMs() {
            return baseDelayMs;
        }

        public void setBaseDelayMs(long baseDelayMs) {
            this.baseDelayMs = Math.max(1, baseDelayMs);
        }

        public long getMaxDelayMs() {
            return maxDelayMs;
        }

        public void setMaxDelayMs(long maxDelayMs) {
            this.maxDelayMs = Math.max(1, maxDelayMs);
        }

        public long getProbeTimeoutMs() {
            return probeTimeoutMs;
        }

        public void setProbeTimeoutMs(long probeTimeoutMs) {
            this.probeTimeoutMs = Math.max(1000, probeTimeoutMs);
        }
    }

    /** Purge of {@code PROCESSED} rows so the table (and every poll) stays small. */
    public static class Retention {
        private boolean enabled = true;
        /** PROCESSED events older than this many days are deleted. DEAD_LETTER / PENDING rows are never purged. */
        private int days = 7;
        /** Rows deleted per statement - small so the delete never holds locks for long. */
        private int batchSize = 1000;
        /** Pause between two batches, in ms, so the purge does not starve the workers of I/O. */
        private long pauseMs = 100;
        /** Time budget of one run, in ms; the remainder is picked up by the next run. */
        private long maxRunMs = 15000;
        /** Delay between runs, in ms. */
        private long intervalMs = 60000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getDays() {
            return days;
        }

        public void setDays(int days) {
            this.days = Math.max(1, days);
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = Math.min(10000, Math.max(1, batchSize));
        }

        public long getPauseMs() {
            return pauseMs;
        }

        public void setPauseMs(long pauseMs) {
            this.pauseMs = Math.max(0, pauseMs);
        }

        public long getMaxRunMs() {
            return maxRunMs;
        }

        public void setMaxRunMs(long maxRunMs) {
            this.maxRunMs = Math.max(100, maxRunMs);
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = Math.max(1000, intervalMs);
        }
    }

    /** Automatic replay of dead-lettered events at a low, bounded rate. */
    public static class Redrive {
        private boolean enabled = true;
        /** Delay between runs, in ms. */
        private long intervalMs = 60000;
        /** Events replayed per run at most (the "low rate"). */
        private int batchSize = 20;
        /** A dead letter whose cause was classified transient is replayed once it has been dead this many seconds. */
        private long transientAfterSeconds = 60;
        /** Any other dead letter (permanent / unknown cause) is replayed once it has been dead this many minutes. */
        private long olderThanMinutes = 30;
        /** Automatic replays per event; after that only the manual studio replay revives it. */
        private int maxAutoReplays = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = Math.max(1000, intervalMs);
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = Math.max(1, batchSize);
        }

        public long getTransientAfterSeconds() {
            return transientAfterSeconds;
        }

        public void setTransientAfterSeconds(long transientAfterSeconds) {
            this.transientAfterSeconds = Math.max(0, transientAfterSeconds);
        }

        public long getOlderThanMinutes() {
            return olderThanMinutes;
        }

        public void setOlderThanMinutes(long olderThanMinutes) {
            this.olderThanMinutes = Math.max(0, olderThanMinutes);
        }

        public int getMaxAutoReplays() {
            return maxAutoReplays;
        }

        public void setMaxAutoReplays(int maxAutoReplays) {
            this.maxAutoReplays = Math.max(0, maxAutoReplays);
        }
    }

    /** Operator visibility: the periodic log line and the cached counts behind the health indicator. */
    public static class Stats {
        /** Delay between the periodic "outbox status" log lines, in ms. */
        private long logIntervalMs = 60000;
        /** How long a counts snapshot is reused (health probes must not hammer the table), in ms. */
        private long cacheMs = 5000;

        public long getLogIntervalMs() {
            return logIntervalMs;
        }

        public void setLogIntervalMs(long logIntervalMs) {
            this.logIntervalMs = Math.max(1000, logIntervalMs);
        }

        public long getCacheMs() {
            return cacheMs;
        }

        public void setCacheMs(long cacheMs) {
            this.cacheMs = Math.max(0, cacheMs);
        }
    }
}
