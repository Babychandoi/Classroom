package com.classroom.config;

import com.classroom.modules.identity.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterSecurityTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private record View(String status) implements UserRepository.AuthenticationView {
        public String getId() { return "user-1"; }
        public String getEmail() { return "user@example.test"; }
        public String getFullName() { return "Learner"; }
        public String getRole() { return "STUDENT"; }
        public String getStatus() { return status; }
    }
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/v1/privacy/me/requests");
        request.addHeader("Authorization", "Bearer fixture-token"); return request;
    }
    @Test void currentDatabaseRoleIsUsedInsteadOfTokenClaims() throws Exception {
        var jwt = mock(JwtTokenProvider.class); var users = mock(UserRepository.class);
        when(jwt.validateToken("fixture-token")).thenReturn(true);
        when(jwt.getUserIdFromToken("fixture-token")).thenReturn("user-1");
        when(users.findAuthenticationById("user-1")).thenReturn(Optional.of(new View("ACTIVE")));
        new JwtAuthenticationFilter(jwt, users).doFilterInternal(request(), new MockHttpServletResponse(), mock(FilterChain.class));
        var principal = (UserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertEquals("STUDENT", principal.getRole());
        verify(jwt, never()).getRoleFromToken(anyString());
        verify(users, never()).findById(anyString());
    }
    @Test void removedOrInactiveAccountCannotAuthenticateWithAnExistingToken() throws Exception {
        var jwt = mock(JwtTokenProvider.class); var users = mock(UserRepository.class);
        when(jwt.validateToken("fixture-token")).thenReturn(true);
        when(jwt.getUserIdFromToken("fixture-token")).thenReturn("user-1");
        for (var view : java.util.List.of(Optional.of(new View("INACTIVE")), Optional.of(new View("DELETED")), Optional.<View>empty())) {
            when(users.findAuthenticationById("user-1")).thenReturn(view.map(v -> (UserRepository.AuthenticationView) v));
            new JwtAuthenticationFilter(jwt, users).doFilterInternal(request(), new MockHttpServletResponse(), mock(FilterChain.class));
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }
    @Test void unavailableDatabaseNeverFallsBackToTokenRole() {
        var jwt = mock(JwtTokenProvider.class); var users = mock(UserRepository.class); var chain = mock(FilterChain.class);
        when(jwt.validateToken("fixture-token")).thenReturn(true);
        when(jwt.getUserIdFromToken("fixture-token")).thenReturn("user-1");
        when(users.findAuthenticationById("user-1")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Unavailable"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> new JwtAuthenticationFilter(jwt, users).doFilterInternal(request(), new MockHttpServletResponse(), chain));
        assertNull(SecurityContextHolder.getContext().getAuthentication()); verifyNoInteractions(chain);
    }
}
