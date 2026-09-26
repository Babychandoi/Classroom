package com.classroom.modules.identity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public class UserProfileDto {
    private String id;
    private String email;
    private String fullName;
    private String avatarUrl;
    private String bio;
    private String profileVisibility;
    private String role;
    private String status;
    private Instant createdAt;

    // Class specific context (if viewed inside a class)
    private String membershipRole; // OWNER, STAFF, STUDENT, GUEST
    private boolean isPro;
    private int totalPoints;
    private String rankTier;

    public UserProfileDto() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getProfileVisibility() { return profileVisibility; }

    public void setProfileVisibility(String profileVisibility) { this.profileVisibility = profileVisibility; }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
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

    public String getMembershipRole() {
        return membershipRole;
    }

    public void setMembershipRole(String membershipRole) {
        this.membershipRole = membershipRole;
    }

    @JsonProperty("isPro")
    public boolean isPro() {
        return isPro;
    }

    @JsonProperty("isPro")
    public void setPro(boolean pro) {
        isPro = pro;
    }

    public int getTotalPoints() {
        return totalPoints;
    }

    public void setTotalPoints(int totalPoints) {
        this.totalPoints = totalPoints;
    }

    public String getRankTier() {
        return rankTier;
    }

    public void setRankTier(String rankTier) {
        this.rankTier = rankTier;
    }
}
