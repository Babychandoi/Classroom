package com.classroom.modules.classroom.dto;

import java.time.Instant;

public class ClassMemberDto {
    private String id;
    private String userId;
    private String role;
    private String state;
    private Instant joinedAt;
    private String userFullName;
    private String userAvatarUrl;

    public ClassMemberDto() {
    }

    public ClassMemberDto(String id, String userId, String role, String state, Instant joinedAt, String userFullName, String userAvatarUrl) {
        this.id = id;
        this.userId = userId;
        this.role = role;
        this.state = state;
        this.joinedAt = joinedAt;
        this.userFullName = userFullName;
        this.userAvatarUrl = userAvatarUrl;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }

    public String getUserFullName() {
        return userFullName;
    }

    public void setUserFullName(String userFullName) {
        this.userFullName = userFullName;
    }

    public String getUserAvatarUrl() {
        return userAvatarUrl;
    }

    public void setUserAvatarUrl(String userAvatarUrl) {
        this.userAvatarUrl = userAvatarUrl;
    }
}
