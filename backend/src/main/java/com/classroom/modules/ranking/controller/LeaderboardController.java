package com.classroom.modules.ranking.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.ranking.dto.LeaderboardEntryDto;
import com.classroom.modules.ranking.dto.LeaderboardConfigRequest;
import com.classroom.modules.ranking.dto.LeaderboardConfigResponse;
import com.classroom.modules.ranking.service.LeaderboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/classes/{classId}/leaderboard")
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    public LeaderboardController(LeaderboardService leaderboardService) {
        this.leaderboardService = leaderboardService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<LeaderboardEntryDto>>> getLeaderboard(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) String examId) {
        String currentUserId = (principal != null) ? principal.getId() : null;
        List<LeaderboardEntryDto> leaderboard = leaderboardService.getLeaderboard(classId, currentUserId, examId);
        return ResponseEntity.ok(ApiResponse.ok(leaderboard));
    }

    /** Rank tiers of the class, ordered by minPoints ascending (members, owner, staff - same rule as GET /leaderboard). */
    @GetMapping("/tiers")
    public ResponseEntity<ApiResponse<List<com.classroom.modules.ranking.dto.RankTierDto>>> getTiers(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(leaderboardService.getTiers(classId, principal != null ? principal.getId() : null)));
    }

    @PostMapping("/rebuild")
    public ResponseEntity<ApiResponse<Map<String, String>>> rebuildLeaderboard(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal) {
        leaderboardService.rebuildLeaderboard(classId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "Đã tái tạo bảng xếp hạng thành công")));
    }

    @GetMapping("/configuration")
    public ResponseEntity<ApiResponse<LeaderboardConfigResponse>> getConfiguration(
            @PathVariable String classId, @CurrentUser UserPrincipal principal) {
        LeaderboardConfigResponse config = leaderboardService.getConfiguration(classId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(config));
    }

    @PutMapping("/configuration")
    public ResponseEntity<ApiResponse<Map<String, String>>> configure(
            @PathVariable String classId, @CurrentUser UserPrincipal principal,
            @RequestBody LeaderboardConfigRequest request) {
        leaderboardService.configure(classId, principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "Đã lưu cấu hình xếp hạng")));
    }
}
