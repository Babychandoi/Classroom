package com.classroom.modules.admin.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.identity.service.RefreshTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * D-29: "the last ACTIVE platform admin is never banned or demoted" (409). Through HTTP the caller is always an ACTIVE admin other than the
 * target, so the rule only bites when two admins act on each other concurrently: the second one, once it gets the row locks on the ACTIVE
 * admins, finds itself banned / demoted and the target the only one left. That moment is reproduced here: the locked admin set is just the
 * target. Nothing is written, nothing is revoked, nothing is audited.
 */
class AdminUserServiceLastAdminTest {

    private NamedParameterJdbcTemplate jdbc;
    private UserRepository users;
    private RefreshTokenService refreshTokens;
    private AuditService audit;
    private AdminUserService service;
    private User lastAdmin;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        users = mock(UserRepository.class);
        refreshTokens = mock(RefreshTokenService.class);
        audit = mock(AuditService.class);
        service = new AdminUserService(jdbc, users, refreshTokens, audit, mock(AdminAuditService.class), new ObjectMapper());
        lastAdmin = new User("admin-last", "last@admin.test", "hash", "Last admin", "PLATFORM_ADMIN");
        when(users.findById("admin-last")).thenReturn(Optional.of(lastAdmin));
        when(jdbc.queryForList(contains("FOR UPDATE"), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of("admin-last"));
    }

    @Test
    @DisplayName("banning the last ACTIVE platform admin is 409 and changes nothing")
    void lastAdminCannotBeBanned() {
        AppException e = assertThrows(AppException.class, () -> service.ban("admin-last", "admin-who-was-just-banned", "Đua nhau khóa"));
        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
        assertEquals("ACTIVE", lastAdmin.getStatus());
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(refreshTokens, audit);
    }

    @Test
    @DisplayName("demoting the last ACTIVE platform admin is 409 and changes nothing")
    void lastAdminCannotBeDemoted() {
        AppException e = assertThrows(AppException.class, () -> service.changeRole("admin-last", "someone-else", "USER", "Đua nhau hạ quyền"));
        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
        assertEquals("PLATFORM_ADMIN", lastAdmin.getRole());
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("with a second ACTIVE admin left the same ban goes through (and revokes the sessions)")
    void notTheLastAdmin() {
        when(jdbc.queryForList(contains("FOR UPDATE"), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of("admin-last", "admin-other"));
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());
        // the final read of the row is not part of this rule; it fails as "not found" against the mocked JDBC
        assertThrows(AppException.class, () -> service.ban("admin-last", "admin-other", "Hợp lệ"));
        assertEquals("BANNED", lastAdmin.getStatus());
        verify(refreshTokens).revokeAllForUser("admin-last");
        verify(audit).record(eq(null), eq("admin-other"), eq("ADMIN_USER_BAN"), eq("USER"), eq("admin-last"), contains("\"reason\":\"Hợp lệ\""));
    }
}
