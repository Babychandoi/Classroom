package com.classroom.modules.identity.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.identity.dto.AuthResponse;
import com.classroom.modules.identity.dto.LoginRequest;
import com.classroom.modules.identity.dto.RegisterRequest;
import com.classroom.modules.identity.dto.UserProfileDto;
import com.classroom.modules.identity.service.AuthService;
import com.classroom.modules.identity.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final com.classroom.config.JwtTokenProvider tokenProvider;

    public AuthController(AuthService authService, UserService userService, com.classroom.config.JwtTokenProvider tokenProvider) {
        this.authService = authService;
        this.userService = userService;
        this.tokenProvider = tokenProvider;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/auth/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<ApiResponse<Map<String, String>>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            tokenProvider.revokeToken(token);
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "Đăng xuất thành công")));
    }

    @GetMapping({"/me", "/auth/me"})
    public ResponseEntity<ApiResponse<UserProfileDto>> getCurrentUser(@CurrentUser UserPrincipal principal) {
        UserProfileDto profile = userService.getProfile(principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }
}
