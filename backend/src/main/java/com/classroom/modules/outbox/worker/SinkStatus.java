package com.classroom.modules.outbox.worker;

import java.time.Instant;

/**
 * Operator-facing view of one projection store as seen by the outbox worker (health indicator, studio status endpoint, log line).
 *
 * @param sink                {@code MONGO} or {@code NEO4J}
 * @param enabled             whether the projection to this store is switched on at all ({@code classroom.projection.*.enabled})
 * @param up                  {@code false} while the worker considers the store down and has suspended projections to it
 * @param state               breaker state: {@code CLOSED} (healthy), {@code OPEN} (down, waiting to probe) or {@code HALF_OPEN} (probing)
 * @param consecutiveFailures failed probes in the current outage (0 when healthy)
 * @param downSince           when the current outage was first noticed, {@code null} when healthy
 * @param nextProbeAt         when the next trial call is allowed, {@code null} when healthy
 * @param lastError           the last transient error message, {@code null} when healthy
 */
public record SinkStatus(String sink, boolean enabled, boolean up, String state, int consecutiveFailures,
                         Instant downSince, Instant nextProbeAt, String lastError) {
}
