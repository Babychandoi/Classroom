package com.classroom.modules.outbox.worker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A settable clock so back-off and breaker tests never sleep. */
final class TestClock extends Clock {

    private final AtomicReference<Instant> now;

    TestClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    static TestClock at(String isoInstant) {
        return new TestClock(Instant.parse(isoInstant));
    }

    void advance(Duration by) {
        now.updateAndGet(current -> current.plus(by));
    }

    void advanceSeconds(long seconds) {
        advance(Duration.ofSeconds(seconds));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
