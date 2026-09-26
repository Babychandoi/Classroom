package com.classroom.modules.ranking.dto;

import java.time.Instant;

public class LeaderboardEntryDto {
    private int rank;
    private String userId;
    private String userFullName;
    private String userAvatarUrl;
    private String classId;
    private int totalPoints;
    private String currentTier;
    private Instant lastCalculatedAt;

    public LeaderboardEntryDto() {}

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
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

    public String getClassId() {
        return classId;
    }

    public void setClassId(String classId) {
        this.classId = classId;
    }

    public int getTotalPoints() {
        return totalPoints;
    }

    public void setTotalPoints(int totalPoints) {
        this.totalPoints = totalPoints;
    }

    public String getCurrentTier() {
        return currentTier;
    }

    public void setCurrentTier(String currentTier) {
        this.currentTier = currentTier;
    }

    public Instant getLastCalculatedAt() {
        return lastCalculatedAt;
    }

    public void setLastCalculatedAt(Instant lastCalculatedAt) {
        this.lastCalculatedAt = lastCalculatedAt;
    }
}
