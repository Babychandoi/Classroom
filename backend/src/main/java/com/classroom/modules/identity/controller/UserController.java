package com.classroom.modules.identity.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.identity.dto.UserProfileDto;
import com.classroom.modules.identity.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserProfileDto>> getUserProfile(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) String classId) {
        String viewerId = (principal != null) ? principal.getId() : null;
        UserProfileDto profile = userService.getProfileForViewer(id, viewerId, classId);
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<UserProfileDto>> updateProfile(
            @CurrentUser UserPrincipal principal,
            @RequestBody Map<String, String> body) {
        UserProfileDto updated = userService.updateProfile(
                principal.getId(),
                body.get("fullName"),
                body.get("avatarUrl"),
                body.get("bio"),
                body.get("profileVisibility")
        );
        return ResponseEntity.ok(ApiResponse.ok(updated));
    }
}
