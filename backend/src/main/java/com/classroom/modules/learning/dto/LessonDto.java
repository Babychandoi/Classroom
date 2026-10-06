package com.classroom.modules.learning.dto;

public class LessonDto {
    private String captionsVtt;
    public String getCaptionsVtt() { return captionsVtt; }
    public void setCaptionsVtt(String captionsVtt) { this.captionsVtt = captionsVtt; }
    private String id;
    private String sectionId;
    private String courseId;
    private String title;
    private String type;
    private String contentText;
    private String mediaAssetId;
    private String mediaDownloadUrl;
    // D-31: UPLOAD | YOUTUBE | GOOGLE_DRIVE | null. The URLs are only filled for callers who may learn (or edit) the course.
    private String videoProvider;
    private String videoUrl;
    private String embedUrl;
    // D-32: components. hasAssignment / components are public metadata; attachments and instructions only for callers who may learn or manage.
    private boolean hasAssignment;
    private String assignmentInstructions;
    private java.util.List<LessonAttachmentDto> attachments = java.util.List.of();
    private LessonComponentsDto components;
    public boolean isHasAssignment() { return hasAssignment; }
    public void setHasAssignment(boolean hasAssignment) { this.hasAssignment = hasAssignment; }
    public String getAssignmentInstructions() { return assignmentInstructions; }
    public void setAssignmentInstructions(String assignmentInstructions) { this.assignmentInstructions = assignmentInstructions; }
    public java.util.List<LessonAttachmentDto> getAttachments() { return attachments; }
    public void setAttachments(java.util.List<LessonAttachmentDto> attachments) { this.attachments = attachments; }
    public LessonComponentsDto getComponents() { return components; }
    public void setComponents(LessonComponentsDto components) { this.components = components; }
    public String getVideoProvider() { return videoProvider; }
    public void setVideoProvider(String videoProvider) { this.videoProvider = videoProvider; }
    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }
    public String getEmbedUrl() { return embedUrl; }
    public void setEmbedUrl(String embedUrl) { this.embedUrl = embedUrl; }
    private int durationMinutes;
    private int position;
    private boolean completed;
    private boolean archived;

    public LessonDto() {}

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

    public String getMediaDownloadUrl() {
        return mediaDownloadUrl;
    }

    public void setMediaDownloadUrl(String mediaDownloadUrl) {
        this.mediaDownloadUrl = mediaDownloadUrl;
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

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }
}
