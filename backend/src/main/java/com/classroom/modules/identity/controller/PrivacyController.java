package com.classroom.modules.identity.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.identity.service.PrivacyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;

@RestController
@org.springframework.validation.annotation.Validated
@RequestMapping("/api/v1/privacy")
public class PrivacyController {
    private final PrivacyService privacy;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.classroom.config.SharedRateLimitStore limits;
    private void limit(String userId) {
        if (limits != null && limits.acquire("privacy:" + userId, 60) > 0)
            throw new com.classroom.common.AppException(com.classroom.common.ErrorCode.RATE_LIMITED, "Quá nhiều lần xác nhận. Thử lại sau một phút.");
    }
    public PrivacyController(PrivacyService privacy) { this.privacy = privacy; }
    public record Request(@NotBlank @Size(max = 200) String password, @Size(max = 2000) String reason) {}
    public record Resolution(@NotBlank String status, @NotBlank @Size(max = 2000) String resolution) {}
    @PostMapping("/me/export")
    public ResponseEntity<ApiResponse<Map<String,Object>>> export(@CurrentUser UserPrincipal user, @Valid @RequestBody Request request,
            @RequestParam(defaultValue="0") @Min(0) @Max(10000000) int offset) {
        limit(user.getId());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.ok(privacy.export(user.getId(), request.password(), offset)));
    }
    @GetMapping("/me/requests")
    public ApiResponse<List<Map<String,Object>>> mine(@CurrentUser UserPrincipal user) { return ApiResponse.ok(privacy.requests(user.getId(), false)); }
    @PostMapping("/me/deletion-requests")
    public ApiResponse<Map<String,Object>> request(@CurrentUser UserPrincipal user, @Valid @RequestBody Request request) { limit(user.getId()); return ApiResponse.ok(privacy.requestDeletion(user.getId(), request.password(), request.reason())); }
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @GetMapping("/requests")
    public ApiResponse<List<Map<String,Object>>> all(@CurrentUser UserPrincipal user) { return ApiResponse.ok(privacy.requests(user.getId(), true)); }
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @PutMapping("/requests/{userId}")
    public ApiResponse<Map<String,Object>> resolve(@CurrentUser UserPrincipal admin, @PathVariable String userId, @Valid @RequestBody Resolution request) { return ApiResponse.ok(privacy.resolve(userId, admin.getId(), request.status(), request.resolution())); }
}
