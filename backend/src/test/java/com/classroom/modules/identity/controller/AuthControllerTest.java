package com.classroom.modules.identity.controller;

import com.classroom.common.ApiResponse;
import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.config.JwtTokenProvider;
import com.classroom.modules.identity.dto.AuthResponse;
import com.classroom.modules.identity.dto.LoginRequest;
import com.classroom.modules.identity.dto.UserProfileDto;
import com.classroom.modules.identity.service.AuthService;
import com.classroom.modules.identity.service.RefreshTokenService;
import com.classroom.modules.identity.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * R8-06: cookie attributes (HttpOnly, SameSite=Strict, path-scoped) and the X-Requested-With guard
 * on /auth/refresh, exercised directly against the controller (same pattern as
 * MediaUploadIntentAuthorizationTest - no MockMvc/HTTP layer needed for these behaviors).
 */
@ExtendWith(MockitoExtension.class)
public class AuthControllerTest {

    @Mock
    private AuthService authService;
    @Mock
    private UserService userService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private JwtTokenProvider tokenProvider;

    private AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(authService, userService, refreshTokenService, tokenProvider);
    }

    @Test
    @DisplayName("login() sets an HttpOnly, SameSite=Strict cookie scoped to /api/v1/auth")
    void loginSetsRefreshCookieWithSecureAttributes() {
        AuthResponse response = new AuthResponse("access-jwt", "u1", "u1@test.local", "User One", "USER", null);
        RefreshTokenService.IssuedToken issued = new RefreshTokenService.IssuedToken("raw-refresh-token", Instant.now().plusSeconds(2_592_000));
        when(authService.login(any(LoginRequest.class))).thenReturn(new AuthService.AuthResult(response, issued));

        ResponseEntity<ApiResponse<AuthResponse>> result = controller.login(new LoginRequest("u1@test.local", "Password123!"));

        String cookie = result.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);
        assertTrue(cookie.contains("refresh_token=raw-refresh-token"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict"));
        assertTrue(cookie.contains("Path=/api/v1/auth"));
        assertFalse(cookie.contains("Secure"), "cookie-secure defaults to false so local http:// development still works");
        assertEquals("access-jwt", result.getBody().getData().getToken());
    }

    @Test
    @DisplayName("login() marks the cookie Secure when app.security.cookie-secure=true")
    void loginRespectsCookieSecureConfig() {
        ReflectionTestUtils.setField(controller, "cookieSecure", true);
        AuthResponse response = new AuthResponse("access-jwt", "u1", "u1@test.local", "User One", "USER", null);
        RefreshTokenService.IssuedToken issued = new RefreshTokenService.IssuedToken("raw-refresh-token", Instant.now().plusSeconds(2_592_000));
        when(authService.login(any(LoginRequest.class))).thenReturn(new AuthService.AuthResult(response, issued));

        ResponseEntity<ApiResponse<AuthResponse>> result = controller.login(new LoginRequest("u1@test.local", "Password123!"));

        assertTrue(result.getHeaders().getFirst(HttpHeaders.SET_COOKIE).contains("Secure"));
    }

    @Test
    @DisplayName("refresh() rejects a request missing the X-Requested-With header, without touching the token service")
    void refreshRejectsMissingRequestedWithHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("refresh_token", "some-token"));

        AppException ex = assertThrows(AppException.class, () -> controller.refresh(request, null));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    @DisplayName("refresh() rotates the cookie's token and returns a fresh access JWT + rotated cookie")
    void refreshRotatesTokenAndReturnsNewAccessJwt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("refresh_token", "old-raw-token"));

        RefreshTokenService.IssuedToken newIssued = new RefreshTokenService.IssuedToken("new-raw-token", Instant.now().plusSeconds(2_592_000));
        when(refreshTokenService.rotate("old-raw-token"))
                .thenReturn(new RefreshTokenService.RotationResult("u1", newIssued));
        UserProfileDto profile = new UserProfileDto();
        profile.setId("u1");
        profile.setEmail("u1@test.local");
        profile.setFullName("User One");
        profile.setRole("USER");
        when(userService.getProfile("u1")).thenReturn(profile);
        when(tokenProvider.generateToken("u1", "u1@test.local", "USER")).thenReturn("new-access-jwt");

        ResponseEntity<ApiResponse<AuthResponse>> result = controller.refresh(request, "XMLHttpRequest");

        assertEquals("new-access-jwt", result.getBody().getData().getToken());
        String cookie = result.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertTrue(cookie.contains("refresh_token=new-raw-token"));
    }

    @Test
    @DisplayName("refresh() propagates 401 from the token service for an invalid/reused/expired cookie")
    void refreshPropagatesRotationFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("refresh_token", "reused-token"));
        when(refreshTokenService.rotate("reused-token"))
                .thenThrow(new AppException(ErrorCode.UNAUTHORIZED, "Phiên đăng nhập đã bị thu hồi do phát hiện sử dụng lại token"));

        AppException ex = assertThrows(AppException.class, () -> controller.refresh(request, "XMLHttpRequest"));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    @DisplayName("logout() revokes the refresh token family and clears the cookie (Max-Age=0)")
    void logoutRevokesFamilyAndClearsCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("refresh_token", "raw-token"));

        ResponseEntity<ApiResponse<java.util.Map<String, String>>> result =
                controller.logout("Bearer access-jwt", request);

        verify(tokenProvider).revokeToken("access-jwt");
        verify(refreshTokenService).revokeByRawToken("raw-token");
        String cookie = result.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);
        assertTrue(cookie.contains("refresh_token="));
        assertTrue(cookie.contains("Max-Age=0"));
    }

    @Test
    @DisplayName("logout() still clears the cookie and revokes the refresh family with no Authorization header")
    void logoutWorksWithoutAccessToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("refresh_token", "raw-token"));

        controller.logout(null, request);

        verify(tokenProvider, never()).revokeToken(anyString());
        verify(refreshTokenService).revokeByRawToken("raw-token");
    }

    @Test
    @DisplayName("R12-01: logout() still clears the cookie when family revocation throws (e.g. a racing rotate())")
    void logoutClearsCookieEvenWhenRevocationThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("refresh_token", "raw-token"));
        doThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException("RefreshToken", "id-1"))
                .when(refreshTokenService).revokeByRawToken("raw-token");

        AuthController.LogoutRevocationException ex = assertThrows(
                AuthController.LogoutRevocationException.class, () -> controller.logout(null, request));

        // The exception carries the already-built expired cookie so the handler can still clear it.
        assertTrue(ex.expiredCookie().toString().contains("refresh_token="));
        assertTrue(ex.expiredCookie().toString().contains("Max-Age=0"));

        ResponseEntity<ApiResponse<Void>> handled = controller.handleLogoutRevocationFailure(ex, request);
        assertEquals(409, handled.getStatusCode().value());
        String cookie = handled.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);
        assertTrue(cookie.contains("refresh_token="));
        assertTrue(cookie.contains("Max-Age=0"));
        assertEquals("Dữ liệu đã được cập nhật bởi thao tác khác, vui lòng thử lại.", handled.getBody().getError().getMessage());
        assertNotNull(handled.getBody().getError().getRequestId(), "requestId must be populated, not null, even without an X-Request-Id header");
    }

    @Test
    @DisplayName("R13-12: logout() revocation failure from a generic (non-AppException, non-optimistic-lock) cause maps to 500 with the standard generic message, requestId set, and never echoes the raw exception message")
    void logoutRevocationFailureFromGenericCauseReturnsGenericMessageAndRequestId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("refresh_token", "raw-token"));
        request.addHeader("X-Request-Id", "req-123");
        doThrow(new IllegalStateException("some internal secret detail"))
                .when(refreshTokenService).revokeByRawToken("raw-token");

        AuthController.LogoutRevocationException ex = assertThrows(
                AuthController.LogoutRevocationException.class, () -> controller.logout(null, request));

        ResponseEntity<ApiResponse<Void>> handled = controller.handleLogoutRevocationFailure(ex, request);
        assertEquals(500, handled.getStatusCode().value());
        assertEquals("req-123", handled.getBody().getError().getRequestId());
        assertEquals("Đã xảy ra lỗi hệ thống, vui lòng thử lại sau.", handled.getBody().getError().getMessage());
        assertFalse(handled.getBody().getError().getMessage().contains("some internal secret detail"),
                "the raw cause message must never be echoed back to the client");
    }
}
