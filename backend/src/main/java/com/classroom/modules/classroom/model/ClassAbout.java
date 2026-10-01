package com.classroom.modules.classroom.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "class_about")
public class ClassAbout {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, unique = true, length = 36)
    private String classId;

    @Column(name = "content_markdown", columnDefinition = "MEDIUMTEXT")
    private String contentMarkdown;

    @Column(name = "rules_markdown", columnDefinition = "MEDIUMTEXT")
    private String rulesMarkdown;

    @Column(name = "sections_json", columnDefinition = "MEDIUMTEXT")
    private String sectionsJson;

    public String getSectionsJson() { return sectionsJson; }
    public void setSectionsJson(String sectionsJson) { this.sectionsJson = sectionsJson; }

    @Column(name = "published_version", nullable = false)
    private int publishedVersion = 1;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ClassAbout() {
        this.id = UUID.randomUUID().toString();
        this.updatedAt = Instant.now();
    }

    public ClassAbout(String classId, String contentMarkdown, String rulesMarkdown) {
        this.id = UUID.randomUUID().toString();
        this.classId = classId;
        this.contentMarkdown = contentMarkdown;
        this.rulesMarkdown = rulesMarkdown;
        this.publishedVersion = 1;
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

    public String getContentMarkdown() {
        return contentMarkdown;
    }

    public void setContentMarkdown(String contentMarkdown) {
        this.contentMarkdown = contentMarkdown;
    }

    public String rulesMarkdown() {
        return rulesMarkdown;
    }

    public String getRulesMarkdown() {
        return rulesMarkdown;
    }

    public void setRulesMarkdown(String rulesMarkdown) {
        this.rulesMarkdown = rulesMarkdown;
    }

    public int getPublishedVersion() {
        return publishedVersion;
    }

    public void setPublishedVersion(int publishedVersion) {
        this.publishedVersion = publishedVersion;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
