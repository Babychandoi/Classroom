package com.classroom.modules.commerce.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "products")
public class Product {

    /** Everything sold before D-19: PRO packages and course products. */
    public static final String KIND_STANDARD = "STANDARD";
    /** D-19: the product that sells membership of a PAID class (one per class, see Classroom#getAccessProductId). */
    public static final String KIND_CLASS_ACCESS = "CLASS_ACCESS";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(name = "target_course_id", length = 36)
    private String targetCourseId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 32)
    private String status = "DRAFT"; // DRAFT, PUBLISHED, ARCHIVED

    @Column(nullable = false, length = 24)
    private String kind = KIND_STANDARD; // STANDARD, CLASS_ACCESS

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Product() {
        this.id = UUID.randomUUID().toString();
        this.status = "DRAFT";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Product(String classId, String targetCourseId, String title, String description) {
        this.id = UUID.randomUUID().toString();
        this.classId = classId;
        this.targetCourseId = targetCourseId;
        this.title = title;
        this.description = description;
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

    public String getTargetCourseId() {
        return targetCourseId;
    }

    public void setTargetCourseId(String targetCourseId) {
        this.targetCourseId = targetCourseId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isClassAccess() {
        return KIND_CLASS_ACCESS.equalsIgnoreCase(kind);
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
