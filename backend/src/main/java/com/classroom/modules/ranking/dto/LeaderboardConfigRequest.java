package com.classroom.modules.ranking.dto;

import java.math.BigDecimal;
import java.util.List;

public record LeaderboardConfigRequest(List<Tier> tiers, List<Reward> rewards) {
    public record Tier(String tierName, int minPoints, String badgeUrl, String description) {}
    public record Reward(String examId, BigDecimal minExamScore, int rewardPoints) {}
}
