package com.classroom.modules.ranking.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "leaderboard_entries")
public class LeaderboardEntry {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "total_points", nullable = false)
    private int totalPoints = 0;

    @Column(name = "current_tier", length = 64)
    private String currentTier = "Tân thủ";

    @Column(name = "last_calculated_at", nullable = false)
    private Instant lastCalculatedAt;

    public LeaderboardEntry() {
        this.id = UUID.randomUUID().toString();
        this.lastCalculatedAt = Instant.now();
    }

    public LeaderboardEntry(String classId, String userId, int totalPoints, String currentTier) {
        this.id = UUID.randomUUID().toString();
        this.classId = classId;
        this.userId = userId;
        this.totalPoints = totalPoints;
        this.currentTier = currentTier;
        this.lastCalculatedAt = Instant.now();
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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
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
