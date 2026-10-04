package com.classroom.modules.ranking.dto;

/**
 * A rank tier as members see it (GET /classes/{classId}/leaderboard/tiers). {@code name} is the contract field; {@code tierName},
 * {@code badgeUrl} and {@code description} carry the same names and values as the tiers of GET .../leaderboard/configuration, so a client
 * can share one type for both.
 */
public record RankTierDto(String name, String tierName, int minPoints, String badgeUrl, String description) {}
