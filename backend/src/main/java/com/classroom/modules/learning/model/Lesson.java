package com.classroom.modules.learning.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "lessons")
public class Lesson {
    @Column(name = "captions_vtt", columnDefinition = "MEDIUMTEXT")
    private String captionsVtt;
    public String getCaptionsVtt() { return captionsVtt; }
    public void setCaptionsVtt(String captionsVtt) { this.captionsVtt = captionsVtt; }

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "section_id", nullable = false, length = 36)
    private String sectionId;

    @Column(name = "course_id", nullable = false, length = 36)
    private String courseId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 32)
    private String type = "VIDEO"; // VIDEO, TEXT, DOCUMENT, ASSIGNMENT

    @Column(name = "content_text", columnDefinition = "MEDIUMTEXT")
    private String contentText;

    @Column(name = "media_asset_id", length = 36)
    private String mediaAssetId;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes = 0;

    @Column(nullable = false)
    private int position = 0;

    @Column(nullable = false)
    private boolean archived = false;

    public Lesson() {
        this.id = UUID.randomUUID().toString();
    }

    public Lesson(String sectionId, String courseId, String title, String type, int position) {
        this.id = UUID.randomUUID().toString();
        this.sectionId = sectionId;
        this.courseId = courseId;
        this.title = title;
        this.type = (type != null) ? type : "VIDEO";
        this.position = position;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSectionId() {
        return sectionId;
    }

    public void setSectionId(String sectionId) {
        this.sectionId = sectionId;
    }

    public String getCourseId() {
        return courseId;
    }

    public void setCourseId(String courseId) {
        this.courseId = courseId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContentText() {
        return contentText;
    }

    public void setContentText(String contentText) {
        this.contentText = contentText;
    }

    public String getMediaAssetId() {
        return mediaAssetId;
    }

    public void setMediaAssetId(String mediaAssetId) {
        this.mediaAssetId = mediaAssetId;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }
}
