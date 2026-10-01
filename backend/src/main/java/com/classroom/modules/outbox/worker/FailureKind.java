package com.classroom.modules.outbox.worker;

/**
 * How a projection failure is treated by the outbox worker (R20-04b).
 *
 * <ul>
 *   <li>{@link #TRANSIENT}: the failure says something about the DEPENDENCY, not about the event - connection refused, timeouts,
 *       "service unavailable", a driver's transient/retryable error. Such a failure never increments the event's permanent retry
 *       counter and can never dead-letter it; the event stays {@code PENDING} and the sink's circuit breaker paces the retries.</li>
 *   <li>{@link #PERMANENT}: the event itself cannot be projected (serialization error, constraint violation, illegal argument, a
 *       server-side rejection, or anything not recognised as transient). It counts toward the dead-letter limit.</li>
 * </ul>
 */
public enum FailureKind {
    TRANSIENT,
    PERMANENT
}
