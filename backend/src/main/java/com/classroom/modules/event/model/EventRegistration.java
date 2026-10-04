package com.classroom.modules.event.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** D-27: one person registered for one event; (event_id, user_id) is unique (V44). */
@Entity
@Table(name = "class_event_registrations",
        uniqueConstraints = @UniqueConstraint(name = "uk_class_event_registration", columnNames = {"event_id", "user_id"}))
public class EventRegistration {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    public EventRegistration() {
        this.id = UUID.randomUUID().toString();
        this.registeredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public EventRegistration(String eventId, String userId) {
        this();
        this.eventId = eventId;
        this.userId = userId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }
}
