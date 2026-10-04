package com.classroom.modules.blog.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** D-27: a class blog post (table {@code blog_posts}, V44). */
@Entity
@Table(name = "blog_posts")
public class BlogPost {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String AUDIENCE_PUBLIC = "PUBLIC";
    public static final String AUDIENCE_MEMBERS = "MEMBERS";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(name = "author_id", nullable = false, length = 36)
    private String authorId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 300)
    private String excerpt;

    @Column(length = 60)
    private String category;

    @Column(name = "content_markdown", columnDefinition = "MEDIUMTEXT")
    private String contentMarkdown;

    @Column(name = "cover_media_id", length = 36)
    private String coverMediaId;

    @Column(nullable = false, length = 16)
    private String audience = AUDIENCE_PUBLIC;

    @Column(nullable = false, length = 16)
    private String status = STATUS_DRAFT;

    @Column(name = "reading_minutes", nullable = false)
    private int readingMinutes = 1;

    @Column(name = "published_at")
    private Instant publishedAt;

    /**
     * Optimistic lock (V45): concurrent edits / publish / unpublish of the same post cannot silently overwrite each other - the loser gets
     * 409 (GlobalExceptionHandler). A wrapper type, so a new post (null) is persisted, not merged.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public BlogPost() {
        this.id = UUID.randomUUID().toString();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getClassId() { return classId; }
    public void setClassId(String classId) { this.classId = classId; }
    public String getAuthorId() { return authorId; }
    public void setAuthorId(String authorId) { this.authorId = authorId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getExcerpt() { return excerpt; }
    public void setExcerpt(String excerpt) { this.excerpt = excerpt; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getContentMarkdown() { return contentMarkdown; }
    public void setContentMarkdown(String contentMarkdown) { this.contentMarkdown = contentMarkdown; }
    public String getCoverMediaId() { return coverMediaId; }
    public void setCoverMediaId(String coverMediaId) { this.coverMediaId = coverMediaId; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getReadingMinutes() { return readingMinutes; }
    public void setReadingMinutes(int readingMinutes) { this.readingMinutes = readingMinutes; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }

    public boolean isPublished() {
        return STATUS_PUBLISHED.equals(status);
    }

    public boolean isMembersOnly() {
        return AUDIENCE_MEMBERS.equals(audience);
    }
}
