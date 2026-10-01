package com.classroom.modules.outbox.worker;

/**
 * The downstream projection stores the outbox worker writes to. Each one has its own {@link SinkBreaker} so an outage of
 * one store (R20-04) suspends only the events that need it.
 */
public enum OutboxSink {
    MONGO("MongoDB"),
    NEO4J("Neo4j");

    private final String label;

    OutboxSink(String label) {
        this.label = label;
    }

    /** Human-readable name used in error messages and logs ("MongoDB", "Neo4j"). */
    public String label() {
        return label;
    }
}
