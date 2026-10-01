package com.classroom.modules.outbox.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * Circuit-breaker style "dependency down" state of ONE projection store (R20-04b).
 *
 * <p>Without it, a stopped MongoDB or Neo4j made the worker attempt every eligible event, each one blocking for the driver's
 * connect/selection timeout and each one logging an error. Now the first transient failure OPENS the breaker: projections to that
 * store are suspended (no event is even claimed) and a single probe is allowed once the back-off has elapsed. The probe interval
 * doubles per failed probe (base, 2x, 4x, ...) up to a cap, so a long outage costs one short attempt every few seconds instead of a
 * stream of blocked calls. The first success closes it again and the backlog drains at full speed.
 *
 * <pre>
 *   CLOSED --transient failure--&gt; OPEN --back-off elapsed, one caller wins the probe--&gt; HALF_OPEN
 *   HALF_OPEN --success--&gt; CLOSED          HALF_OPEN --transient failure--&gt; OPEN (longer back-off)
 * </pre>
 *
 * <p>State changes are logged exactly once (CLOSED to OPEN as a warning, OPEN to CLOSED as an info line with the outage length);
 * repeated failed probes are silent. All methods are thread-safe. The clock is passed in so tests do not sleep.
 */
final class SinkBreaker {

    private static final Logger log = LoggerFactory.getLogger(SinkBreaker.class);

    enum State { CLOSED, OPEN, HALF_OPEN }

    /** Proof that the holder may call the sink. {@code probe} is true for the single trial call granted while the breaker is open. */
    record Permit(boolean probe) {
    }

    /** Immutable view for health output / logs. */
    record Status(OutboxSink sink, State state, int consecutiveFailures, Instant downSince, Instant nextProbeAt, String lastError) {
        boolean up() {
            return state == State.CLOSED;
        }
    }

    private final OutboxSink sink;
    private final long baseDelayMillis;
    private final long maxDelayMillis;
    private final long probeTimeoutMillis;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant downSince;
    private Instant nextProbeAt = Instant.EPOCH;
    private Instant probeDeadline = Instant.EPOCH;
    private String lastError;

    SinkBreaker(OutboxSink sink, long baseDelayMillis, long maxDelayMillis, long probeTimeoutMillis) {
        this.sink = sink;
        this.baseDelayMillis = Math.max(1, baseDelayMillis);
        this.maxDelayMillis = Math.max(this.baseDelayMillis, maxDelayMillis);
        this.probeTimeoutMillis = Math.max(1000, probeTimeoutMillis);
    }

    /**
     * Delay before the next probe after {@code failures} consecutive failures: {@code base * 2^(failures-1)}, capped at {@code max}.
     * Overflow-safe for any failure count.
     */
    static long backoffMillis(int failures, long baseMillis, long maxMillis) {
        if (failures <= 1) {
            return Math.min(baseMillis, maxMillis);
        }
        int shift = Math.min(failures - 1, 30);
        long delay = baseMillis << shift;
        return delay < 0 || delay > maxMillis ? maxMillis : delay;
    }

    /**
     * Asks for permission to call the sink now. Returns {@code null} while the store is considered down (open and not yet due, or a
     * probe is already in flight); otherwise a permit. When open and due, exactly ONE caller receives the probe permit.
     */
    synchronized Permit tryAcquire(Instant now) {
        switch (state) {
            case CLOSED:
                return new Permit(false);
            case OPEN:
                if (!now.isBefore(nextProbeAt)) {
                    state = State.HALF_OPEN;
                    probeDeadline = now.plusMillis(probeTimeoutMillis);
                    return new Permit(true);
                }
                return null;
            case HALF_OPEN:
            default:
                if (!now.isBefore(probeDeadline)) {
                    // The probe never reported back (its thread died or hung past every driver timeout): allow a fresh one.
                    probeDeadline = now.plusMillis(probeTimeoutMillis);
                    return new Permit(true);
                }
                return null;
        }
    }

    /** True when {@link #tryAcquire} would refuse right now. Does not change any state; used to skip fetching work that cannot run. */
    synchronized boolean isBlocked(Instant now) {
        return switch (state) {
            case CLOSED -> false;
            case OPEN -> now.isBefore(nextProbeAt);
            case HALF_OPEN -> now.isBefore(probeDeadline);
        };
    }

    /** The sink answered (a successful write, or a server-side rejection, which also proves it is reachable). Closes the breaker. */
    synchronized void recordSuccess(Instant now) {
        if (state == State.CLOSED) {
            return;
        }
        Duration outage = downSince == null ? Duration.ZERO : Duration.between(downSince, now);
        int failedProbes = Math.max(0, consecutiveFailures - 1);
        state = State.CLOSED;
        consecutiveFailures = 0;
        downSince = null;
        nextProbeAt = Instant.EPOCH;
        lastError = null;
        log.info("Outbox: {} is reachable again after {} s ({} failed probe(s)); resuming projections and draining the backlog",
                sink.label(), outage.toSeconds(), failedProbes);
    }

    /** A transient failure: the store could not be reached in time. Opens the breaker (or lengthens the back-off of a failed probe). */
    synchronized void recordFailure(Permit permit, Instant now, String reason) {
        lastError = reason;
        if (permit != null && permit.probe()) {
            // A failed probe: same outage, wait longer before the next one. Silent by design (state did not change).
            consecutiveFailures++;
            state = State.OPEN;
            nextProbeAt = now.plusMillis(backoffMillis(consecutiveFailures, baseDelayMillis, maxDelayMillis));
            return;
        }
        if (state == State.CLOSED) {
            consecutiveFailures = 1;
            state = State.OPEN;
            downSince = now;
            nextProbeAt = now.plusMillis(backoffMillis(1, baseDelayMillis, maxDelayMillis));
            log.warn("Outbox: {} is UNAVAILABLE ({}). Projections to it are suspended and no event will be dead-lettered while it is "
                            + "down; probing again every {}-{} s until it answers.",
                    sink.label(), abbreviate(reason), baseDelayMillis / 1000.0, maxDelayMillis / 1000.0);
        }
        // else: another worker already opened the breaker for the same outage - nothing to add.
    }

    /** The permit was taken but the sink was never called (or the call proved nothing): hand a probe permit back for the next caller. */
    synchronized void release(Permit permit) {
        if (permit != null && permit.probe() && state == State.HALF_OPEN) {
            state = State.OPEN;
            probeDeadline = Instant.EPOCH;
            // nextProbeAt is unchanged (already in the past), so the next caller may probe immediately.
        }
    }

    synchronized Status status() {
        return new Status(sink, state, consecutiveFailures, downSince, state == State.CLOSED ? null : nextProbeAt, lastError);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "no detail";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
