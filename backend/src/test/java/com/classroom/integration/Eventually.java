package com.classroom.integration;

import java.util.function.BooleanSupplier;

/** Tiny polling helper for assertions on work that finishes asynchronously (e.g. the leaderboard recalculation worker). */
final class Eventually {

    private Eventually() {}

    /** Polls {@code condition} every 25 ms until it is true; returns whether it became true within {@code timeoutMs}. */
    static boolean await(long timeoutMs, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            try {
                if (condition.getAsBoolean()) return true;
            } catch (RuntimeException ignoredWhileWaiting) {
                // the row may not exist yet - keep polling until the deadline
            }
            if (System.nanoTime() >= deadline) return false;
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
