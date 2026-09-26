package com.classroom.modules.community.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "posts")
public class Post {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(name = "author_id", nullable = false, length = 36)
    private String authorId;

    @Column(nullable = false)
    private String title;

    @Column(name = "content_markdown", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String contentMarkdown;

    @Column(nullable = false, length = 32)
    private String visibility = "PUBLIC"; // PUBLIC, FREE, PRO, PRODUCT_OWNER, SEGMENT

    @Column(name = "target_product_id", length = 36)
    private String targetProductId;

    @Column(name = "target_segment_id", length = 36)
    private String targetSegmentId;

    @Column(nullable = false)
    private boolean pinned = false;

    @Column(nullable = false, length = 32)
    private String status = "DRAFT";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Post() {
        this.id = UUID.randomUUID().toString();
        this.status = "DRAFT";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Post(String classId, String authorId, String title, String contentMarkdown, String visibility) {
        this.id = UUID.randomUUID().toString();
        this.classId = classId;
        this.authorId = authorId;
        this.title = title;
        this.contentMarkdown = contentMarkdown;
        this.visibility = (visibility != null) ? visibility : "PUBLIC";
        this.status = "DRAFT";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getClassId() {
        return classId;
    }

    public void setClassId(String classId) {
        this.classId = classId;
    }

    public String getAuthorId() {
        return authorId;
    }

    public void setAuthorId(String authorId) {
        this.authorId = authorId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContentMarkdown() {
        return contentMarkdown;
    }

    public void setContentMarkdown(String contentMarkdown) {
        this.contentMarkdown = contentMarkdown;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public String getTargetProductId() {
        return targetProductId;
    }

    public void setTargetProductId(String targetProductId) {
        this.targetProductId = targetProductId;
    }

    public String getTargetSegmentId() {
        return targetSegmentId;
    }

    public void setTargetSegmentId(String targetSegmentId) {
        this.targetSegmentId = targetSegmentId;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
