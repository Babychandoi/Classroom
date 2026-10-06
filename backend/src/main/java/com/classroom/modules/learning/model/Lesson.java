package com.classroom.modules.learning.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.classroom.modules.learning.service.VideoLinkParser;
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

    // D-31: external video. The stored columns are never bound from, or written to, JSON; clients send only "videoUrl" (parsed by the
    // service) and read the derived videoProvider / videoUrl / embedUrl.
    @Column(name = "video_provider", length = 16)
    private String storedVideoProvider;

    @Column(name = "video_ref", length = 128)
    private String storedVideoRef;

    @Transient
    private String videoUrlInput;

    @JsonIgnore
    public String getStoredVideoProvider() { return storedVideoProvider; }
    @JsonIgnore
    public void setStoredVideoProvider(String v) { this.storedVideoProvider = v; }
    @JsonIgnore
    public String getStoredVideoRef() { return storedVideoRef; }
    @JsonIgnore
    public void setStoredVideoRef(String v) { this.storedVideoRef = v; }

    /** What the author pasted (request only): null = keep, "" = clear, otherwise parsed by the service. */
    @JsonIgnore
    public String getVideoUrlInput() { return videoUrlInput; }
    /** Request side of "videoUrl" (the response side is {@link #getVideoUrl()}). */
    public void setVideoUrl(String v) { this.videoUrlInput = v; }

    /** UPLOAD when a file is attached, the external provider, or null (response only). */
    @JsonProperty(value = "videoProvider", access = JsonProperty.Access.READ_ONLY)
    public String getVideoProvider() {
        if (mediaAssetId != null && !mediaAssetId.isBlank()) return "UPLOAD";
        return storedVideoRef == null ? null : storedVideoProvider;
    }

    public String getVideoUrl() {
        return storedVideoRef == null ? null : VideoLinkParser.canonicalUrl(storedVideoProvider, storedVideoRef);
    }

    @JsonProperty(value = "embedUrl", access = JsonProperty.Access.READ_ONLY)
    public String getEmbedUrl() {
        return storedVideoRef == null ? null : VideoLinkParser.embedUrl(storedVideoProvider, storedVideoRef);
    }

    /** D-31: the one definition of "this lesson has a video" - an uploaded file or an external link. */
    @JsonIgnore
    public boolean hasVideo() {
        return (mediaAssetId != null && !mediaAssetId.isBlank()) || (storedVideoRef != null && storedVideoProvider != null);
    }

    // D-32: assignment component. Persisted as has_assignment / assignment_instructions; the JSON "hasAssignment" is bound through a
    // transient Boolean so "absent" (keep) can be told from false.
    @Column(name = "has_assignment", nullable = false)
    private boolean assignmentEnabled = false;

    @Column(name = "assignment_instructions", columnDefinition = "MEDIUMTEXT")
    private String assignmentInstructions;

    @Transient
    private Boolean hasAssignmentInput;

    @JsonIgnore
    public boolean isAssignmentEnabled() { return assignmentEnabled; }
    @JsonIgnore
    public void setAssignmentEnabled(boolean v) { this.assignmentEnabled = v; }
    public String getAssignmentInstructions() { return assignmentInstructions; }
    public void setAssignmentInstructions(String v) { this.assignmentInstructions = v; }
    @JsonIgnore
    public Boolean getHasAssignmentInput() { return hasAssignmentInput; }
    /** Request side of "hasAssignment": null = keep. */
    public void setHasAssignment(Boolean v) { this.hasAssignmentInput = v; }
    public boolean getHasAssignment() { return assignmentEnabled; }

    /** D-32: the derived summary type - assignment, else video (upload or link), else documents, else text. */
    public static String deriveType(boolean hasAssignment, boolean hasVideo, int attachments) {
        if (hasAssignment) return "ASSIGNMENT";
        if (hasVideo) return "VIDEO";
        if (attachments > 0) return "DOCUMENT";
        return "TEXT";
    }

    /** Recomputes {@link #type} from the components; call on every write. */
    public void recomputeType(int attachments) {
        this.type = deriveType(assignmentEnabled, hasVideo(), attachments);
    }

    public Lesson() {
        this.id = UUID.randomUUID().toString();
    }

    public Lesson(String sectionId, String courseId, String title, String type, int position) {
        this.id = UUID.randomUUID().toString();
        this.sectionId = sectionId;
        this.courseId = courseId;
        this.title = title;
        this.type = (type != null) ? type : "VIDEO";
        // D-32: the legacy "type" argument of programmatic callers (seeds, tests): ASSIGNMENT enables the assignment component, nothing else is implied.
        this.assignmentEnabled = "ASSIGNMENT".equalsIgnoreCase(type);
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

    /** D-32: a derived summary (see {@link #deriveType}); a request cannot set it (the service recomputes it on every write). */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
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
