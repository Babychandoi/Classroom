package com.classroom.modules.classroom.policy;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.model.StaffAssignment;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.classroom.repository.StaffPermissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/** D-19 rules of {@link AccessPolicy} in isolation: what "visible", "member" and "expired" mean, and what a denied caller is told. */
@ExtendWith(MockitoExtension.class)
class AccessPolicyVisibilityAndExpiryTest {

    @Mock private ClassroomRepository classroomRepository;
    @Mock private ClassMemberRepository memberRepository;
    @Mock private StaffAssignmentRepository staffAssignmentRepository;
    @Mock private StaffPermissionRepository staffPermissionRepository;
    @InjectMocks private AccessPolicy policy;

    private Classroom publicClass;
    private Classroom privateClass;

    @BeforeEach
    void setUp() {
        publicClass = new Classroom("pub", "owner", "pub", "Public", "d");
        privateClass = new Classroom("priv", "owner", "priv", "Private", "d");
        privateClass.setVisibility("PRIVATE");
        lenient().when(classroomRepository.findById("pub")).thenReturn(Optional.of(publicClass));
        lenient().when(classroomRepository.findById("priv")).thenReturn(Optional.of(privateClass));
        lenient().when(classroomRepository.findById("gone")).thenReturn(Optional.empty());
    }

    private void member(String classId, String userId, String state, Instant expiresAt) {
        ClassMember m = new ClassMember(classId, userId, "STUDENT");
        m.setState(state);
        m.setAccessExpiresAt(expiresAt);
        lenient().when(memberRepository.findByClassIdAndUserId(classId, userId)).thenReturn(Optional.of(m));
    }

    private static Instant in(long seconds) {
        return Instant.now().plus(seconds, ChronoUnit.SECONDS);
    }

    @Test
    @DisplayName("a missing or unknown visibility reads as PUBLIC; only the exact value PRIVATE (any case) makes a class private")
    void unknownVisibilityIsPublic() {
        Classroom c = new Classroom();
        c.setVisibility(null);
        assertFalse(c.isPrivate());
        c.setVisibility("weird");
        assertFalse(c.isPrivate());
        c.setVisibility("private");
        assertTrue(c.isPrivate());
    }

    @Test
    @DisplayName("public + ACTIVE is visible to everybody; private or archived only to owner, active staff and members (ACTIVE or EXPIRED)")
    void visibilityRule() {
        assertTrue(policy.isClassVisibleToUser(publicClass, null));
        assertTrue(policy.isClassVisibleToUser(publicClass, "stranger"));
        assertFalse(policy.isClassVisibleToUser(privateClass, null));
        assertFalse(policy.isClassVisibleToUser(privateClass, "stranger"));
        assertTrue(policy.isClassVisibleToUser(privateClass, "owner"));

        member("priv", "active", "ACTIVE", null);
        member("priv", "expired", "EXPIRED", in(-60));
        member("priv", "lapsed", "ACTIVE", in(-60));
        member("priv", "removed", "REMOVED", null);
        member("priv", "blocked", "BLOCKED", null);
        assertTrue(policy.isClassVisibleToUser(privateClass, "active"));
        assertTrue(policy.isClassVisibleToUser(privateClass, "expired"));
        assertTrue(policy.isClassVisibleToUser(privateClass, "lapsed"));
        assertFalse(policy.isClassVisibleToUser(privateClass, "removed"));
        assertFalse(policy.isClassVisibleToUser(privateClass, "blocked"));

        StaffAssignment staff = new StaffAssignment("priv", "staff");
        staff.setStatus("ACTIVE");
        lenient().when(staffAssignmentRepository.findByClassIdAndUserId("priv", "staff")).thenReturn(Optional.of(staff));
        assertTrue(policy.isClassVisibleToUser(privateClass, "staff"));

        publicClass.setStatus("ARCHIVED");
        assertFalse(policy.isClassVisibleToUser(publicClass, null));
        assertFalse(policy.isClassVisibleToUser(publicClass, "stranger"));
    }

    @Test
    @DisplayName("isMember: ACTIVE and not lapsed; the date is checked even when the stored state is still ACTIVE; no date means no expiry")
    void memberRule() {
        member("pub", "running", "ACTIVE", in(3600));
        member("pub", "forever", "ACTIVE", null);
        member("pub", "lapsed", "ACTIVE", in(-1));
        member("pub", "expired", "EXPIRED", in(-3600));
        member("pub", "removed", "REMOVED", null);
        assertTrue(policy.isMember("running", "pub"));
        assertTrue(policy.isMember("forever", "pub"));
        assertTrue(policy.isMember("owner", "pub"), "the owner needs no row");
        assertFalse(policy.isMember("lapsed", "pub"));
        assertFalse(policy.isMember("expired", "pub"));
        assertFalse(policy.isMember("removed", "pub"));
        assertFalse(policy.isMember(null, "pub"));

        assertTrue(policy.isMembershipExpired("lapsed", "pub"));
        assertTrue(policy.isMembershipExpired("expired", "pub"));
        assertFalse(policy.isMembershipExpired("running", "pub"));
        assertFalse(policy.isMembershipExpired("removed", "pub"));
        assertFalse(policy.isMembershipExpired("owner", "pub"));
        assertFalse(policy.isMembershipExpired(null, "pub"));
    }

    @Test
    @DisplayName("enforceMember tells a denied caller: 404 for an unknown or hidden private class, MEMBERSHIP_EXPIRED for a lapsed member, else the plain 403")
    void whatADeniedCallerIsTold() {
        member("pub", "lapsed", "ACTIVE", in(-1));
        member("priv", "expired", "EXPIRED", in(-3600));

        AppException unknown = assertThrows(AppException.class, () -> policy.enforceMember("stranger", "gone"));
        AppException hidden = assertThrows(AppException.class, () -> policy.enforceMember("stranger", "priv"));
        assertEquals(ErrorCode.NOT_FOUND, unknown.getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, hidden.getErrorCode());
        assertEquals(unknown.getMessage(), hidden.getMessage(), "a private class and a missing one are answered identically");

        assertEquals(ErrorCode.MEMBERSHIP_EXPIRED, assertThrows(AppException.class, () -> policy.enforceMember("lapsed", "pub")).getErrorCode());
        assertEquals(ErrorCode.MEMBERSHIP_EXPIRED, assertThrows(AppException.class, () -> policy.enforceMember("expired", "priv")).getErrorCode());
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(AppException.class, () -> policy.enforceMember("stranger", "pub")).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(AppException.class, () -> policy.enforceMember(null, "priv")).getErrorCode());
    }

    @Test
    @DisplayName("isHiddenPrivateClass / isMissingOrHiddenPrivateClass: the two questions the 404 paths ask")
    void hiddenPrivate() {
        assertTrue(policy.isHiddenPrivateClass(privateClass, null));
        assertTrue(policy.isHiddenPrivateClass(privateClass, "stranger"));
        assertFalse(policy.isHiddenPrivateClass(privateClass, "owner"));
        assertFalse(policy.isHiddenPrivateClass(publicClass, null), "a public class is never 'hidden private'");
        assertFalse(policy.isHiddenPrivateClass(null, "x"));

        assertTrue(policy.isMissingOrHiddenPrivateClass("gone", "x"));
        assertTrue(policy.isMissingOrHiddenPrivateClass(null, "x"));
        assertTrue(policy.isMissingOrHiddenPrivateClass("priv", null));
        assertFalse(policy.isMissingOrHiddenPrivateClass("priv", "owner"));
        assertFalse(policy.isMissingOrHiddenPrivateClass("pub", null));
    }

    @Test
    @DisplayName("ClassMember.effectiveState: a stored ACTIVE with a passed date is EXPIRED; everything else is itself")
    void effectiveState() {
        ClassMember m = new ClassMember("c", "u", "STUDENT");
        Instant now = Instant.now();
        assertEquals("ACTIVE", m.effectiveState(now));
        m.setAccessExpiresAt(now.minusSeconds(1));
        assertEquals("EXPIRED", m.effectiveState(now));
        assertFalse(m.isActiveAt(now));
        m.setAccessExpiresAt(now.plusSeconds(1));
        assertEquals("ACTIVE", m.effectiveState(now));
        m.setState("REMOVED");
        m.setAccessExpiresAt(now.minusSeconds(1));
        assertEquals("REMOVED", m.effectiveState(now), "REMOVED / BLOCKED semantics are unchanged");
    }
}
