package com.classroom.modules.ranking.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "rank_tiers")
public class RankTier {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(name = "tier_name", nullable = false, length = 64)
    private String tierName;

    @Column(name = "min_points", nullable = false)
    private int minPoints = 0;

    @Column(name = "badge_url", length = 512)
    private String badgeUrl;

    @Column(columnDefinition = "TEXT")
    private String description;

    public RankTier() {
        this.id = UUID.randomUUID().toString();
    }

    public RankTier(String classId, String tierName, int minPoints, String badgeUrl, String description) {
        this.id = UUID.randomUUID().toString();
        this.classId = classId;
        this.tierName = tierName;
        this.minPoints = minPoints;
        this.badgeUrl = badgeUrl;
        this.description = description;
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

    public String getTierName() {
        return tierName;
    }

    public void setTierName(String tierName) {
        this.tierName = tierName;
    }

    public int getMinPoints() {
        return minPoints;
    }

    public void setMinPoints(int minPoints) {
        this.minPoints = minPoints;
    }

    public String getBadgeUrl() {
        return badgeUrl;
    }

    public void setBadgeUrl(String badgeUrl) {
        this.badgeUrl = badgeUrl;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
