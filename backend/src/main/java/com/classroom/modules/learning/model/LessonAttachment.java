package com.classroom.modules.learning.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** D-32: one document of a lesson (an UPLOADED media asset of the same class). Unique per media asset; cascades with the lesson. */
@Entity
@Table(name = "lesson_attachments")
public class LessonAttachment {
    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @Column(name = "lesson_id", nullable = false, length = 36)
    private String lessonId;

    @Column(name = "media_asset_id", nullable = false, length = 36, unique = true)
    private String mediaAssetId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    public LessonAttachment() {}

    public LessonAttachment(String lessonId, String mediaAssetId, String title, int position) {
        this.lessonId = lessonId;
        this.mediaAssetId = mediaAssetId;
        this.title = title;
        this.position = position;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLessonId() { return lessonId; }
    public void setLessonId(String lessonId) { this.lessonId = lessonId; }
    public String getMediaAssetId() { return mediaAssetId; }
    public void setMediaAssetId(String mediaAssetId) { this.mediaAssetId = mediaAssetId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
