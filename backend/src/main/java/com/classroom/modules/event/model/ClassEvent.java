package com.classroom.modules.event.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * D-27: a class event (table {@code class_events}, V44). {@code registeredCount} is a denormalised counter changed ONLY while the row is
 * locked ({@code ClassEventRepository#findByIdForUpdate}) together with the registration rows, so it always equals their number and the
 * capacity check made under that lock is exact.
 */
@Entity
@Table(name = "class_events")
public class ClassEvent {

    public static final String FORMAT_ONLINE = "ONLINE";
    public static final String FORMAT_OFFLINE = "OFFLINE";
    public static final String STATUS_SCHEDULED = "SCHEDULED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String AUDIENCE_PUBLIC = "PUBLIC";
    public static final String AUDIENCE_MEMBERS = "MEMBERS";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "host_user_id", nullable = false, length = 36)
    private String hostUserId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String description;

    @Column(name = "for_whom", length = 500)
    private String forWhom;

    /** JSON array of strings ("Mang về gì"); null or "[]" = none. */
    @Column(name = "takeaways_json", columnDefinition = "TEXT")
    private String takeawaysJson;

    @Column(nullable = false, length = 16)
    private String format = FORMAT_ONLINE;

    @Column(length = 300)
    private String location;

    @Column(name = "meeting_url", length = 1000)
    private String meetingUrl;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    /** null = unlimited. */
    @Column
    private Integer capacity;

    @Column(name = "registered_count", nullable = false)
    private int registeredCount;

    @Column(name = "cover_media_id", length = 36)
    private String coverMediaId;

    @Column(nullable = false, length = 16)
    private String audience = AUDIENCE_PUBLIC;

    @Column(nullable = false, length = 16)
    private String status = STATUS_SCHEDULED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ClassEvent() {
        this.id = UUID.randomUUID().toString();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getClassId() { return classId; }
    public void setClassId(String classId) { this.classId = classId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getHostUserId() { return hostUserId; }
    public void setHostUserId(String hostUserId) { this.hostUserId = hostUserId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getForWhom() { return forWhom; }
    public void setForWhom(String forWhom) { this.forWhom = forWhom; }
    public String getTakeawaysJson() { return takeawaysJson; }
    public void setTakeawaysJson(String takeawaysJson) { this.takeawaysJson = takeawaysJson; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getMeetingUrl() { return meetingUrl; }
    public void setMeetingUrl(String meetingUrl) { this.meetingUrl = meetingUrl; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public void setEndsAt(Instant endsAt) { this.endsAt = endsAt; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public int getRegisteredCount() { return registeredCount; }
    public void setRegisteredCount(int registeredCount) { this.registeredCount = registeredCount; }
    public String getCoverMediaId() { return coverMediaId; }
    public void setCoverMediaId(String coverMediaId) { this.coverMediaId = coverMediaId; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean isFull() {
        return capacity != null && registeredCount >= capacity;
    }
}
