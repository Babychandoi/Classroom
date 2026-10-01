package com.classroom.modules.outbox.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R20-04b: the per-store "dependency down" state - back-off arithmetic and the CLOSED / OPEN / HALF_OPEN transitions. */
class SinkBreakerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static SinkBreaker breaker() {
        return new SinkBreaker(OutboxSink.MONGO, 2000, 15000, 30000);
    }

    @Test
    @DisplayName("back-off doubles per failed probe (base, 2x, 4x, ...) and is capped; huge failure counts do not overflow")
    void backoffIsExponentialAndCapped() {
        assertEquals(2000, SinkBreaker.backoffMillis(1, 2000, 15000));
        assertEquals(4000, SinkBreaker.backoffMillis(2, 2000, 15000));
        assertEquals(8000, SinkBreaker.backoffMillis(3, 2000, 15000));
        assertEquals(15000, SinkBreaker.backoffMillis(4, 2000, 15000), "16 s is capped to 15 s");
        assertEquals(15000, SinkBreaker.backoffMillis(5, 2000, 15000));
        assertEquals(15000, SinkBreaker.backoffMillis(1_000, 2000, 15000));
        assertEquals(15000, SinkBreaker.backoffMillis(Integer.MAX_VALUE, 2000, 15000));
        assertEquals(60000, SinkBreaker.backoffMillis(9, 2000, 60000), "a 60 s cap is reached after 2,4,8,16,32,60");
        assertEquals(2000, SinkBreaker.backoffMillis(0, 2000, 15000));
    }

    @Test
    @DisplayName("healthy: every caller gets a permit and nothing is blocked")
    void closedAllowsEveryone() {
        SinkBreaker breaker = breaker();
        assertNotNull(breaker.tryAcquire(T0));
        assertNotNull(breaker.tryAcquire(T0));
        assertFalse(breaker.isBlocked(T0));
        assertTrue(breaker.status().up());
    }

    @Test
    @DisplayName("the first transient failure opens the breaker; calls are refused until the back-off elapsed")
    void failureOpensAndBlocksUntilProbeIsDue() {
        SinkBreaker breaker = breaker();
        SinkBreaker.Permit permit = breaker.tryAcquire(T0);
        breaker.recordFailure(permit, T0, "Timed out after 3000 ms");

        assertFalse(breaker.status().up());
        assertEquals(SinkBreaker.State.OPEN, breaker.status().state());
        assertEquals("Timed out after 3000 ms", breaker.status().lastError());
        assertTrue(breaker.isBlocked(T0.plusMillis(1999)));
        assertNull(breaker.tryAcquire(T0.plusMillis(1999)));
        assertFalse(breaker.isBlocked(T0.plusSeconds(2)));
    }

    @Test
    @DisplayName("when the probe is due exactly ONE caller gets it; the others wait for its verdict")
    void exactlyOneProbeAtATime() {
        SinkBreaker breaker = breaker();
        breaker.recordFailure(breaker.tryAcquire(T0), T0, "down");
        Instant due = T0.plusSeconds(2);

        SinkBreaker.Permit probe = breaker.tryAcquire(due);
        assertNotNull(probe);
        assertTrue(probe.probe());
        assertNull(breaker.tryAcquire(due), "a second caller must not probe concurrently");
        assertNull(breaker.tryAcquire(due.plusSeconds(1)));
        assertTrue(breaker.isBlocked(due.plusSeconds(1)));
        assertEquals(SinkBreaker.State.HALF_OPEN, breaker.status().state());
    }

    @Test
    @DisplayName("a failed probe reopens with a longer back-off; a successful one closes the breaker")
    void failedProbeBacksOffFurtherAndSuccessCloses() {
        SinkBreaker breaker = breaker();
        breaker.recordFailure(breaker.tryAcquire(T0), T0, "down");            // next probe at +2 s
        Instant t1 = T0.plusSeconds(2);
        breaker.recordFailure(breaker.tryAcquire(t1), t1, "still down");      // next probe at +4 s after that
        assertTrue(breaker.isBlocked(t1.plusSeconds(3)));
        assertFalse(breaker.isBlocked(t1.plusSeconds(4)));

        Instant t2 = t1.plusSeconds(4);
        SinkBreaker.Permit probe = breaker.tryAcquire(t2);
        assertNotNull(probe);
        breaker.recordSuccess(t2);

        assertTrue(breaker.status().up());
        assertEquals(0, breaker.status().consecutiveFailures());
        assertNotNull(breaker.tryAcquire(t2));
        assertFalse(breaker.isBlocked(t2));
    }

    @Test
    @DisplayName("late failures from callers that started before the breaker opened do not extend the back-off")
    void concurrentFailuresOfTheSameOutageAreIdempotent() {
        SinkBreaker breaker = breaker();
        SinkBreaker.Permit a = breaker.tryAcquire(T0);
        SinkBreaker.Permit b = breaker.tryAcquire(T0);
        SinkBreaker.Permit c = breaker.tryAcquire(T0);

        breaker.recordFailure(a, T0.plusMillis(3000), "down");
        breaker.recordFailure(b, T0.plusMillis(3100), "down");
        breaker.recordFailure(c, T0.plusMillis(3200), "down");

        assertEquals(1, breaker.status().consecutiveFailures());
        assertFalse(breaker.isBlocked(T0.plusMillis(5000)), "the probe is due 2 s after the FIRST failure");
    }

    @Test
    @DisplayName("a probe permit handed back unused lets the next caller probe immediately")
    void releasedProbeCanBeRetakenAtOnce() {
        SinkBreaker breaker = breaker();
        breaker.recordFailure(breaker.tryAcquire(T0), T0, "down");
        Instant due = T0.plusSeconds(2);
        SinkBreaker.Permit probe = breaker.tryAcquire(due);

        breaker.release(probe);

        assertNotNull(breaker.tryAcquire(due));
    }

    @Test
    @DisplayName("a probe that never reports back is abandoned after the probe timeout")
    void hungProbeIsAbandoned() {
        SinkBreaker breaker = breaker();
        breaker.recordFailure(breaker.tryAcquire(T0), T0, "down");
        Instant due = T0.plusSeconds(2);
        assertNotNull(breaker.tryAcquire(due));

        assertNull(breaker.tryAcquire(due.plusSeconds(29)));
        assertNotNull(breaker.tryAcquire(due.plus(Duration.ofSeconds(30))), "after 30 s another probe is allowed");
    }

    @Test
    @DisplayName("a success while CLOSED changes nothing (no log noise, no state)")
    void successWhileClosedIsANoOp() {
        SinkBreaker breaker = breaker();
        breaker.recordSuccess(T0);
        assertTrue(breaker.status().up());
    }
}
