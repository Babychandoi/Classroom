package com.classroom.modules.learning.dto;

import java.util.List;

public class SectionDto {
    private String id;
    private String courseId;
    private String title;
    private int position;
    private boolean archived;
    private List<LessonDto> lessons;

    public SectionDto() {}

    public SectionDto(String id, String courseId, String title, int position, List<LessonDto> lessons) {
        this.id = id;
        this.courseId = courseId;
        this.title = title;
        this.position = position;
        this.lessons = lessons;
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

    public List<LessonDto> getLessons() {
        return lessons;
    }

    public void setLessons(List<LessonDto> lessons) {
        this.lessons = lessons;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }
}
