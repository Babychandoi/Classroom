package com.classroom.modules.identity.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.identity.dto.AuthResponse;
import com.classroom.modules.identity.dto.LoginRequest;
import com.classroom.modules.identity.dto.RegisterRequest;
import com.classroom.modules.identity.dto.UserProfileDto;
import com.classroom.modules.identity.service.AuthService;
import com.classroom.modules.identity.service.RefreshTokenService;
import com.classroom.modules.identity.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /**
     * R8-06: path-scoped to /api/v1/auth so the refresh cookie is never sent on ordinary API
     * calls, only on the auth endpoints that actually need it.
     */
    public static final String REFRESH_COOKIE_NAME = "refresh_token";
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final UserService userService;
    private final RefreshTokenService refreshTokenService;
    private final com.classroom.config.JwtTokenProvider tokenProvider;

    /**
     * R8-06: Secure is forced on whenever the deployment is HTTPS; local plain-HTTP development
     * must still work (a Secure cookie is silently dropped by browsers over http://), so this is
     * configurable rather than hardcoded. infra/.env / application-docker.properties should set
     * this to true for any deployment actually served over HTTPS.
     */
    @Value("${app.security.cookie-secure:false}")
    private boolean cookieSecure;

    public AuthController(AuthService authService, UserService userService,
                           RefreshTokenService refreshTokenService,
                           com.classroom.config.JwtTokenProvider tokenProvider) {
        this.authService = authService;
        this.userService = userService;
        this.refreshTokenService = refreshTokenService;
        this.tokenProvider = tokenProvider;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthService.AuthResult result = authService.login(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(result.refreshToken().rawToken(), result.refreshToken().expiresAt()).toString())
                .body(ApiResponse.ok(result.response()));
    }

    @PostMapping("/auth/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        AuthService.AuthResult result = authService.register(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(result.refreshToken().rawToken(), result.refreshToken().expiresAt()).toString())
                .body(ApiResponse.ok(result.response()));
    }

    /**
     * R8-06: rotates the refresh token from the HttpOnly cookie and returns a fresh access JWT so
     * the frontend can bootstrap a session on page load/reload/new tab without ever persisting the
     * access token itself. CSRF: the cookie is SameSite=Strict and path-limited to /api/v1/auth,
     * and this response only ever returns a token in the JSON body (never has a state-changing
     * side effect a cross-site GET/form-post could trigger for attacker benefit - a forged POST
     * here just rotates the legitimate user's own session and returns the new token to the
     * attacker's page, which cannot read a cross-origin response body). The extra
     * X-Requested-With header requirement below blocks a simple cross-site <form> POST (which
     * cannot set custom headers) from even reaching this far, as defense in depth.
     */
    @PostMapping("/auth/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            HttpServletRequest request,
            @RequestHeader(value = "X-Requested-With", required = false) String requestedWith) {
        if (!"XMLHttpRequest".equals(requestedWith)) {
            throw new com.classroom.common.AppException(com.classroom.common.ErrorCode.UNAUTHORIZED, "Yêu cầu làm mới phiên không hợp lệ");
        }
        String rawToken = readRefreshCookie(request);
        RefreshTokenService.RotationResult rotation = refreshTokenService.rotate(rawToken);
        UserProfileDto profile = userService.getProfile(rotation.userId());
        String accessToken = tokenProvider.generateToken(rotation.userId(), profile.getEmail(), profile.getRole());
        AuthResponse response = new AuthResponse(accessToken, profile.getId(), profile.getEmail(), profile.getFullName(), profile.getRole(), profile.getAvatarUrl());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(rotation.issued().rawToken(), rotation.issued().expiresAt()).toString())
                .body(ApiResponse.ok(response));
    }

    /**
     * R12-01: revocation and clearing the cookie must not depend on each other's success. A racing
     * rotate() can still surface some other failure out of the revoke call (e.g. a transient
     * OptimisticLockingFailureException despite the bulk-update fix in RefreshTokenService); whatever
     * happens there, the browser must still lose its refresh cookie for this response - a client
     * that believes it logged out but kept a live cookie is worse than a revocation that must be
     * retried server-side. The cookie is always attached, even when revocation throws; the
     * exception (if any) is then rethrown so GlobalExceptionHandler still reports the failure
     * (mapped to 409 for an optimistic-locking conflict - see GlobalExceptionHandler).
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<ApiResponse<Map<String, String>>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {
        ResponseCookie expiredCookie = buildExpiredRefreshCookie();
        try {
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                tokenProvider.revokeToken(token);
            }
            refreshTokenService.revokeByRawToken(readRefreshCookie(request));
        } catch (RuntimeException ex) {
            throw new LogoutRevocationException(ex, expiredCookie);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredCookie.toString())
                .body(ApiResponse.ok(Map.of("message", "Đăng xuất thành công")));
    }

    /**
     * R12-01: carries the already-built expired cookie alongside whatever revocation failure
     * occurred, so a handler upstream of GlobalExceptionHandler (see
     * {@link #handleLogoutRevocationFailure}) can still attach Set-Cookie to the error response
     * while preserving the original exception's error mapping (e.g. 409 for an optimistic-locking
     * conflict).
     */
    static final class LogoutRevocationException extends RuntimeException {
        private final ResponseCookie expiredCookie;

        LogoutRevocationException(RuntimeException cause, ResponseCookie expiredCookie) {
            super(cause);
            this.expiredCookie = expiredCookie;
        }

        RuntimeException unwrap() {
            return (RuntimeException) getCause();
        }

        ResponseCookie expiredCookie() {
            return expiredCookie;
        }
    }

    /**
     * R13-12: previously returned the raw {@code cause.getMessage()} for any non-AppException cause
     * (e.g. a generic RuntimeException) with requestId always null and nothing logged - unlike every
     * path through {@link com.classroom.common.GlobalExceptionHandler}, which logs with the
     * request's requestId and never echoes an internal exception message back to the client. This
     * now mirrors that handler exactly: an AppException cause keeps its own error code/message; an
     * OptimisticLockingFailureException cause maps to 409 CONFLICT with the same generic Vietnamese
     * copy GlobalExceptionHandler uses; anything else maps to 500 with the standard generic message
     * and is logged (at the same level GlobalExceptionHandler logs its generic 500 path), never
     * echoing the raw cause message to the client.
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(LogoutRevocationException.class)
    public ResponseEntity<ApiResponse<Void>> handleLogoutRevocationFailure(
            LogoutRevocationException ex, HttpServletRequest request) {
        RuntimeException cause = ex.unwrap();
        String requestId = com.classroom.common.GlobalExceptionHandler.resolveRequestId(request);
        String message;
        com.classroom.common.ErrorCode errorCode;
        if (cause instanceof com.classroom.common.AppException appEx) {
            errorCode = appEx.getErrorCode();
            message = appEx.getMessage() != null ? appEx.getMessage() : errorCode.getDefaultMessage();
            log.warn("Logout revocation failed with application exception [{}]: {} (Request ID: {})",
                    errorCode.getCode(), message, requestId);
        } else if (cause instanceof org.springframework.dao.OptimisticLockingFailureException) {
            errorCode = com.classroom.common.ErrorCode.CONFLICT;
            message = "Dữ liệu đã được cập nhật bởi thao tác khác, vui lòng thử lại.";
            log.warn("Logout revocation hit an optimistic locking conflict (Request ID: {}): {}",
                    requestId, cause.getMessage());
        } else {
            errorCode = com.classroom.common.ErrorCode.INTERNAL_SERVER_ERROR;
            message = "Đã xảy ra lỗi hệ thống, vui lòng thử lại sau.";
            log.error("Unhandled logout revocation failure (Request ID: {})", requestId, cause);
        }
        return ResponseEntity.status(errorCode.getHttpStatus())
                .header(HttpHeaders.SET_COOKIE, ex.expiredCookie().toString())
                .body(ApiResponse.error(errorCode.getCode(), message, requestId));
    }

    @GetMapping({"/me", "/auth/me"})
    public ResponseEntity<ApiResponse<UserProfileDto>> getCurrentUser(@CurrentUser UserPrincipal principal) {
        UserProfileDto profile = userService.getProfile(principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }

    private String readRefreshCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (REFRESH_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseCookie buildRefreshCookie(String rawToken, java.time.Instant expiresAt) {
        Duration maxAge = Duration.between(java.time.Instant.now(), expiresAt);
        return ResponseCookie.from(REFRESH_COOKIE_NAME, rawToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build();
    }

    private ResponseCookie buildExpiredRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .build();
    }
}
