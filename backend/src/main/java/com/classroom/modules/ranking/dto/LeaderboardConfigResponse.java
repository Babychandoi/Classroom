package com.classroom.modules.ranking.dto;

import java.math.BigDecimal;
import java.util.List;

/** R8-05: current leaderboard configuration (tiers + per-exam reward rules) for the Studio editor. */
public record LeaderboardConfigResponse(List<Tier> tiers, List<Reward> rewards) {
    public record Tier(String tierName, int minPoints, String badgeUrl, String description) {}
    public record Reward(String examId, BigDecimal minExamScore, int rewardPoints) {}
}
