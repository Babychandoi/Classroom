package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.modules.audit.model.AuditEvent;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.dto.ClassMemberDto;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.StaffAssignment;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.classroom.repository.StaffPermissionRepository;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.exam.model.ExamAttempt;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.outbox.service.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * R13-02: authz (OWNER/granted-staff vs. unauthorized) and cross-module effects of Studio member
 * management (remove/block/unblock) — see MemberService's Javadoc for the state machine and the
 * spec sections it implements (FR-14 / sitemap /studio/classes/:id/members).
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock private ClassMemberRepository memberRepository;
    @Mock private StaffAssignmentRepository staffAssignmentRepository;
    @Mock private StaffPermissionRepository staffPermissionRepository;
    @Mock private UserRepository userRepository;
    @Mock private ExamAttemptRepository examAttemptRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private ProPolicy proPolicy;
    @Mock private AuditService auditService;
    @Mock private OutboxService outboxService;

    private MemberService memberService;

    private static final String CLASS_ID = "class-1";
    private static final String OWNER_ID = "owner-1";
    private static final String TARGET_ID = "member-1";

    @BeforeEach
    void setUp() {
        memberService = new MemberService(memberRepository, staffAssignmentRepository, staffPermissionRepository,
                userRepository, examAttemptRepository, accessPolicy, proPolicy, auditService, outboxService);
    }

    private ClassMember activeMember() {
        ClassMember m = new ClassMember(CLASS_ID, TARGET_ID, "STUDENT");
        m.setId("cm-1");
        return m;
    }

    @Test
    @DisplayName("removeMember() rejects a caller without MEMBER:EDIT (403 via AccessPolicy)")
    void removeMemberRejectsUnauthorizedCaller() {
        doThrow(new AppException(com.classroom.common.ErrorCode.STAFF_PERMISSION_DENIED, "no"))
                .when(accessPolicy).enforceManage("intruder", CLASS_ID, "MEMBER", "EDIT", null);

        assertThrows(AppException.class, () -> memberService.removeMember(CLASS_ID, TARGET_ID, "intruder"));
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("removeMember() rejects removing the class OWNER")
    void removeMemberRejectsOwnerTarget() {
        when(accessPolicy.isOwner(TARGET_ID, CLASS_ID)).thenReturn(true);

        AppException ex = assertThrows(AppException.class,
                () -> memberService.removeMember(CLASS_ID, TARGET_ID, OWNER_ID));
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("removeMember(): sets state REMOVED, revokes staff assignment, cancels IN_PROGRESS attempts, emits MEMBER_REMOVED, audits")
    void removeMemberAppliesCrossModuleEffects() {
        ClassMember member = activeMember();
        when(memberRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID)).thenReturn(Optional.of(member));

        StaffAssignment assignment = new StaffAssignment(CLASS_ID, TARGET_ID);
        assignment.setId("assign-1");
        when(staffAssignmentRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID)).thenReturn(Optional.of(assignment));

        ExamAttempt inProgress = new ExamAttempt("exam-1", TARGET_ID, CLASS_ID, java.time.Instant.now().plusSeconds(600), false);
        when(examAttemptRepository.findByClassIdAndUserIdAndStatusForUpdate(CLASS_ID, TARGET_ID, "IN_PROGRESS"))
                .thenReturn(List.of(inProgress));

        memberService.removeMember(CLASS_ID, TARGET_ID, OWNER_ID);

        ArgumentCaptor<ClassMember> memberCaptor = ArgumentCaptor.forClass(ClassMember.class);
        verify(memberRepository).save(memberCaptor.capture());
        assertEquals("REMOVED", memberCaptor.getValue().getState());

        verify(staffPermissionRepository).deleteByAssignmentId("assign-1");
        verify(staffAssignmentRepository).delete(assignment);

        ArgumentCaptor<ExamAttempt> attemptCaptor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(examAttemptRepository).save(attemptCaptor.capture());
        assertEquals("CANCELLED", attemptCaptor.getValue().getStatus());

        verify(outboxService).recordEvent(eq("CLASSROOM"), eq(CLASS_ID), eq("MEMBER_REMOVED"), any());
        verify(auditService).record(eq(CLASS_ID), eq(OWNER_ID), eq("MEMBER_REMOVE"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("removeMember() rejects a member who is not currently ACTIVE")
    void removeMemberRejectsNonActiveMember() {
        ClassMember member = activeMember();
        member.setState("REMOVED");
        when(memberRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID)).thenReturn(Optional.of(member));

        assertThrows(AppException.class, () -> memberService.removeMember(CLASS_ID, TARGET_ID, OWNER_ID));
    }

    @Test
    @DisplayName("blockMember(): sets state BLOCKED and applies the same cross-module effects as removal")
    void blockMemberAppliesEffects() {
        ClassMember member = activeMember();
        when(memberRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID)).thenReturn(Optional.of(member));
        when(examAttemptRepository.findByClassIdAndUserIdAndStatusForUpdate(CLASS_ID, TARGET_ID, "IN_PROGRESS"))
                .thenReturn(List.of());

        memberService.blockMember(CLASS_ID, TARGET_ID, OWNER_ID);

        ArgumentCaptor<ClassMember> captor = ArgumentCaptor.forClass(ClassMember.class);
        verify(memberRepository).save(captor.capture());
        assertEquals("BLOCKED", captor.getValue().getState());
        verify(auditService).record(eq(CLASS_ID), eq(OWNER_ID), eq("MEMBER_BLOCK"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("unblockMember(): restores a BLOCKED member back to ACTIVE and emits MEMBER_JOINED")
    void unblockMemberRestoresActiveState() {
        ClassMember member = activeMember();
        member.setState("BLOCKED");
        when(memberRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID)).thenReturn(Optional.of(member));
        when(accessPolicy.isOwner(OWNER_ID, CLASS_ID)).thenReturn(true);

        memberService.unblockMember(CLASS_ID, TARGET_ID, OWNER_ID);

        ArgumentCaptor<ClassMember> captor = ArgumentCaptor.forClass(ClassMember.class);
        verify(memberRepository).save(captor.capture());
        assertEquals("ACTIVE", captor.getValue().getState());
        verify(outboxService).recordEvent(eq("CLASSROOM"), eq(CLASS_ID), eq("MEMBER_JOINED"), any());
    }

    private static final String DELEGATE_ID = "delegate-1";

    private ClassMember blockedMember() {
        ClassMember member = activeMember();
        member.setState("BLOCKED");
        when(memberRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID)).thenReturn(Optional.of(member));
        return member;
    }

    private void lastRestrictionBy(String actorId, String action) {
        when(auditService.findLatestEvent(eq(CLASS_ID), eq("CLASS_MEMBER"), eq("cm-1"), any()))
                .thenReturn(Optional.of(new AuditEvent(CLASS_ID, actorId, action, "CLASS_MEMBER", "cm-1", "{}")));
    }

    @Test
    @DisplayName("R19-10: a MEMBER:EDIT delegate cannot undo a block the OWNER imposed")
    void delegateCannotUnblockAMemberTheOwnerBlocked() {
        blockedMember();
        lastRestrictionBy(OWNER_ID, "MEMBER_BLOCK");
        when(accessPolicy.isOwner(DELEGATE_ID, CLASS_ID)).thenReturn(false);
        when(accessPolicy.isOwner(OWNER_ID, CLASS_ID)).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> memberService.unblockMember(CLASS_ID, TARGET_ID, DELEGATE_ID));

        assertEquals(com.classroom.common.ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(memberRepository, never()).save(any());
        verify(outboxService, never()).recordEvent(anyString(), anyString(), eq("MEMBER_JOINED"), any());
    }

    @Test
    @DisplayName("R19-10: a delegate cannot bring back a member (e.g. a former staff member) the OWNER removed")
    void delegateCannotRestoreAMemberTheOwnerRemoved() {
        ClassMember member = activeMember();
        member.setState("REMOVED");
        when(memberRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID)).thenReturn(Optional.of(member));
        lastRestrictionBy(OWNER_ID, "MEMBER_REMOVE");
        when(accessPolicy.isOwner(DELEGATE_ID, CLASS_ID)).thenReturn(false);
        when(accessPolicy.isOwner(OWNER_ID, CLASS_ID)).thenReturn(true);

        assertThrows(AppException.class, () -> memberService.unblockMember(CLASS_ID, TARGET_ID, DELEGATE_ID));
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("R19-10: without any audit record of who restricted the member, a delegate cannot lift it (fail closed)")
    void delegateCannotUnblockWithoutAnAuditTrail() {
        blockedMember();
        when(auditService.findLatestEvent(eq(CLASS_ID), eq("CLASS_MEMBER"), eq("cm-1"), any())).thenReturn(Optional.empty());

        assertThrows(AppException.class, () -> memberService.unblockMember(CLASS_ID, TARGET_ID, DELEGATE_ID));
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("R19-10: a delegate MAY lift a restriction another delegate imposed")
    void delegateCanUnblockWhatADelegateBlocked() {
        blockedMember();
        lastRestrictionBy("other-delegate", "MEMBER_BLOCK");
        when(accessPolicy.isOwner(DELEGATE_ID, CLASS_ID)).thenReturn(false);
        when(accessPolicy.isOwner("other-delegate", CLASS_ID)).thenReturn(false);

        memberService.unblockMember(CLASS_ID, TARGET_ID, DELEGATE_ID);

        ArgumentCaptor<ClassMember> captor = ArgumentCaptor.forClass(ClassMember.class);
        verify(memberRepository).save(captor.capture());
        assertEquals("ACTIVE", captor.getValue().getState());
        verify(auditService).record(eq(CLASS_ID), eq(DELEGATE_ID), eq("MEMBER_UNBLOCK"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("R19-10: the OWNER can always lift a restriction, whoever imposed it, without consulting the trail")
    void ownerCanAlwaysUnblock() {
        blockedMember();
        when(accessPolicy.isOwner(OWNER_ID, CLASS_ID)).thenReturn(true);

        memberService.unblockMember(CLASS_ID, TARGET_ID, OWNER_ID);

        verify(auditService, never()).findLatestEvent(anyString(), anyString(), anyString(), any());
        ArgumentCaptor<ClassMember> captor = ArgumentCaptor.forClass(ClassMember.class);
        verify(memberRepository).save(captor.capture());
        assertEquals("ACTIVE", captor.getValue().getState());
    }

    @Test
    @DisplayName("unblockMember() rejects a member who is already ACTIVE")
    void unblockMemberRejectsAlreadyActive() {
        ClassMember member = activeMember();
        when(memberRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID)).thenReturn(Optional.of(member));

        assertThrows(AppException.class, () -> memberService.unblockMember(CLASS_ID, TARGET_ID, OWNER_ID));
    }

    @Test
    @DisplayName("removeMember() rejects a caller who is OWNER/staff of a DIFFERENT class (cross-class authz)")
    void removeMemberRejectsCrossClassCaller() {
        // A caller who legitimately manages "class-other" must not be able to act on "class-1" —
        // AccessPolicy.enforceManage is always evaluated against the classId path segment actually
        // being acted on, never a class the caller happens to have rights in elsewhere.
        doThrow(new AppException(com.classroom.common.ErrorCode.STAFF_PERMISSION_DENIED, "no"))
                .when(accessPolicy).enforceManage("owner-of-other-class", CLASS_ID, "MEMBER", "EDIT", null);

        assertThrows(AppException.class,
                () -> memberService.removeMember(CLASS_ID, TARGET_ID, "owner-of-other-class"));
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("getStudioMembers() requires MEMBER:VIEW and returns PRO/state per member")
    void getStudioMembersRequiresViewPermission() {
        when(memberRepository.searchStudioRoster(eq(CLASS_ID), eq(""), eq(""), eq("%"), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(activeMember())));
        when(userRepository.findAllById(any())).thenReturn(List.of(
                new User(TARGET_ID, "m@test.local", "hashed", "Member One", "USER")));
        when(proPolicy.proUserIds(eq(CLASS_ID), any(), any())).thenReturn(java.util.Set.of(TARGET_ID));

        var result = memberService.getStudioMembers(CLASS_ID, OWNER_ID);

        assertEquals(1, result.getMembers().size());
        assertEquals(1, result.getTotal());
        assertTrue(result.getMembers().get(0).isPro());
        assertEquals("ACTIVE", result.getMembers().get(0).getState());
        assertEquals("Member One", result.getMembers().get(0).getUserFullName());
        verify(accessPolicy).enforceManage(OWNER_ID, CLASS_ID, "MEMBER", "VIEW", null);
        // R20-03: no per-member lookups for the page
        verify(userRepository, never()).findById(anyString());
        verify(proPolicy, never()).isPro(anyString(), anyString());
    }

    // ----- R14-01: a delegate holding only MEMBER:EDIT must not be able to remove/block STAFF -----

    private ClassMember staffMember() {
        ClassMember m = new ClassMember(CLASS_ID, TARGET_ID, "STAFF");
        m.setId("cm-staff");
        return m;
    }

    @Test
    @DisplayName("R14-01: removeMember on a STAFF target by a non-owner (MEMBER:EDIT only) is FORBIDDEN and changes nothing")
    void removeStaffByNonOwnerIsForbidden() {
        when(memberRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID)).thenReturn(Optional.of(staffMember()));
        doThrow(new AppException(com.classroom.common.ErrorCode.FORBIDDEN, "Chỉ chủ lớp học"))
                .when(accessPolicy).enforceOwner("staff-editor", CLASS_ID);

        AppException ex = assertThrows(AppException.class,
                () -> memberService.removeMember(CLASS_ID, TARGET_ID, "staff-editor"));
        assertEquals(com.classroom.common.ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(memberRepository, never()).save(any());
        verify(staffAssignmentRepository, never()).delete(any());
        verify(staffPermissionRepository, never()).deleteByAssignmentId(anyString());
        verify(outboxService, never()).recordEvent(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("R14-01: blockMember on a target with an ACTIVE staff assignment by a non-owner is FORBIDDEN even if the member role says STUDENT")
    void blockStaffAssignmentByNonOwnerIsForbidden() {
        // Role column already reset to STUDENT but an assignment row still exists -> still staff.
        when(memberRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID)).thenReturn(Optional.of(activeMember()));
        StaffAssignment assignment = new StaffAssignment(CLASS_ID, TARGET_ID);
        assignment.setId("assign-1");
        when(staffAssignmentRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID)).thenReturn(Optional.of(assignment));
        doThrow(new AppException(com.classroom.common.ErrorCode.FORBIDDEN, "Chỉ chủ lớp học"))
                .when(accessPolicy).enforceOwner("staff-editor", CLASS_ID);

        AppException ex = assertThrows(AppException.class,
                () -> memberService.blockMember(CLASS_ID, TARGET_ID, "staff-editor"));
        assertEquals(com.classroom.common.ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(memberRepository, never()).save(any());
        verify(staffAssignmentRepository, never()).delete(any());
    }

    @Test
    @DisplayName("R14-01: the OWNER may still remove a STAFF member (assignment + permissions revoked)")
    void ownerMayRemoveStaff() {
        ClassMember staff = staffMember();
        when(memberRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID)).thenReturn(Optional.of(staff));
        StaffAssignment assignment = new StaffAssignment(CLASS_ID, TARGET_ID);
        assignment.setId("assign-1");
        when(staffAssignmentRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID)).thenReturn(Optional.of(assignment));
        when(examAttemptRepository.findByClassIdAndUserIdAndStatusForUpdate(CLASS_ID, TARGET_ID, "IN_PROGRESS")).thenReturn(List.of());

        memberService.removeMember(CLASS_ID, TARGET_ID, OWNER_ID);

        verify(accessPolicy).enforceOwner(OWNER_ID, CLASS_ID);
        verify(staffPermissionRepository).deleteByAssignmentId("assign-1");
        verify(staffAssignmentRepository).delete(assignment);
        assertEquals("REMOVED", staff.getState());
    }

    @Test
    @DisplayName("R14-01: a MEMBER:EDIT delegate may still remove/block a plain student without owner rights")
    void delegateMayRemovePlainStudent() {
        ClassMember student = activeMember();
        when(memberRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID)).thenReturn(Optional.of(student));
        when(examAttemptRepository.findByClassIdAndUserIdAndStatusForUpdate(CLASS_ID, TARGET_ID, "IN_PROGRESS")).thenReturn(List.of());

        memberService.removeMember(CLASS_ID, TARGET_ID, "staff-editor");

        verify(accessPolicy, never()).enforceOwner(anyString(), anyString());
        assertEquals("REMOVED", student.getState());
    }

    @Test
    @DisplayName("R14-01: removing/blocking yourself through the Studio endpoint is rejected with 400")
    void selfTargetIsRejected() {
        AppException removeEx = assertThrows(AppException.class,
                () -> memberService.removeMember(CLASS_ID, "staff-editor", "staff-editor"));
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, removeEx.getErrorCode());
        AppException blockEx = assertThrows(AppException.class,
                () -> memberService.blockMember(CLASS_ID, "staff-editor", "staff-editor"));
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, blockEx.getErrorCode());
        verify(memberRepository, never()).save(any());
        verify(staffAssignmentRepository, never()).delete(any());
    }
}
