package com.classroom.modules.learning.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "sections")
public class Section {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "course_id", nullable = false, length = 36)
    private String courseId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private int position = 0;

    @Column(nullable = false)
    private boolean archived = false;

    public Section() {
        this.id = UUID.randomUUID().toString();
    }

    public Section(String courseId, String title, int position) {
        this.id = UUID.randomUUID().toString();
        this.courseId = courseId;
        this.title = title;
        this.position = position;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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
