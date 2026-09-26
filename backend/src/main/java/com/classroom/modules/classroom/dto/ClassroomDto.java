package com.classroom.modules.classroom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public class ClassroomDto {
    private String id;
    private String ownerId;
    private String ownerName;
    private String slug;
    private String title;
    private String description;
    private String coverImageUrl;
    private String status;
    private long memberCount;
    private boolean isOwner;
    private boolean isMember;
    private boolean isPro;
    private String userRole; // OWNER, STAFF, STUDENT, GUEST
    private Instant createdAt;
    private List<String> studioPermissions = List.of();

    public ClassroomDto() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
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

    public String getCoverImageUrl() {
        return coverImageUrl;
    }

    public void setCoverImageUrl(String coverImageUrl) {
        this.coverImageUrl = coverImageUrl;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(long memberCount) {
        this.memberCount = memberCount;
    }

    @JsonProperty("isOwner")
    public boolean isOwner() {
        return isOwner;
    }

    @JsonProperty("isOwner")
    public void setOwner(boolean owner) {
        isOwner = owner;
    }

    @JsonProperty("isMember")
    public boolean isMember() {
        return isMember;
    }

    @JsonProperty("isMember")
    public void setMember(boolean member) {
        isMember = member;
    }

    @JsonProperty("isPro")
    public boolean isPro() {
        return isPro;
    }

    @JsonProperty("isPro")
    public void setPro(boolean pro) {
        isPro = pro;
    }

    public String getUserRole() {
        return userRole;
    }

    public void setUserRole(String userRole) {
        this.userRole = userRole;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<String> getStudioPermissions() { return studioPermissions; }
    public void setStudioPermissions(List<String> studioPermissions) { this.studioPermissions = studioPermissions; }
}
