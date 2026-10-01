package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.dto.StaffAssignmentDto;
import com.classroom.modules.classroom.dto.StaffPermissionDto;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.StaffAssignment;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.classroom.repository.StaffPermissionRepository;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.outbox.service.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class StaffAuditSecurityTest {

    @Mock
    private StaffAssignmentRepository staffAssignmentRepository;
    @Mock
    private StaffPermissionRepository staffPermissionRepository;
    @Mock
    private ClassMemberRepository memberRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private AuditService auditService;
    @Mock
    private CourseRepository courseRepository;
    @Mock
    private OutboxService outboxService;

    private StaffService staffService;

    private User targetUser;
    private StaffAssignment assignment;

    @BeforeEach
    void setUp() {
        staffService = new StaffService(staffAssignmentRepository, staffPermissionRepository,
                memberRepository, userRepository, accessPolicy, auditService, outboxService, courseRepository);
        targetUser = new User("target-u1", "staff@test.local", "hashed", "Staff Target", "USER");
        assignment = new StaffAssignment("class-1", "target-u1");
        assignment.setId("assign-1");
    }

    @Test
    @DisplayName("Finding 7: Assigning staff permissions records transactional audit event")
    void testAssignStaffRecordsAudit() {
        when(userRepository.findById("target-u1")).thenReturn(Optional.of(targetUser));
        // R4-01: assignStaff now requires the target to already be an ACTIVE member.
        when(memberRepository.findByClassIdAndUserId("class-1", "target-u1"))
                .thenReturn(Optional.of(new ClassMember("class-1", "target-u1", "STUDENT")));
        when(staffAssignmentRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.of(assignment));
        when(staffAssignmentRepository.save(any())).thenReturn(assignment);

        List<StaffPermissionDto> perms = List.of(new StaffPermissionDto("COURSE", "CREATE", null));
        StaffAssignmentDto dto = staffService.assignStaff("class-1", "target-u1", perms, "owner-1");

        assertNotNull(dto);
        verify(accessPolicy).enforceOwner("owner-1", "class-1");
        verify(auditService).record(eq("class-1"), eq("owner-1"), eq("STAFF_PERMISSION_ASSIGN"), eq("STAFF_ASSIGNMENT"), eq("assign-1"), any());
    }

    @Test
    @DisplayName("Finding 7: Removing staff records transactional audit event")
    void testRemoveStaffRecordsAudit() {
        when(staffAssignmentRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.of(assignment));
        ClassMember member = new ClassMember("class-1", "target-u1", "STAFF");
        when(memberRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.of(member));

        staffService.removeStaff("class-1", "target-u1", "owner-1");

        verify(accessPolicy).enforceOwner("owner-1", "class-1");
        verify(staffPermissionRepository).deleteByAssignmentId("assign-1");
        verify(staffAssignmentRepository).delete(assignment);
        verify(auditService).record(eq("class-1"), eq("owner-1"), eq("STAFF_REMOVE"), eq("STAFF_ASSIGNMENT"), eq("target-u1"), any());
    }

    @Test
    void cannotGrantScopeFromAnotherClass() {
        when(userRepository.findById("target-u1")).thenReturn(Optional.of(targetUser));
        when(memberRepository.findByClassIdAndUserId("class-1", "target-u1"))
                .thenReturn(Optional.of(new ClassMember("class-1", "target-u1", "STUDENT")));
        when(staffAssignmentRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.of(assignment));
        when(staffAssignmentRepository.save(any())).thenReturn(assignment);
        Course foreignCourse = new Course("class-2", "Other class", "FREE");
        foreignCourse.setId("foreign-course");
        when(courseRepository.findById("foreign-course")).thenReturn(Optional.of(foreignCourse));

        assertThrows(com.classroom.common.AppException.class, () -> staffService.assignStaff("class-1", "target-u1",
                List.of(new StaffPermissionDto("COURSE", "EDIT", "foreign-course")), "owner-1"));
        verify(staffPermissionRepository, never()).save(any());
    }

    @Test
    @DisplayName("R3-07: owner cannot assign themselves as staff")
    void ownerCannotSelfAssignAsStaff() {
        when(userRepository.findById("owner-1")).thenReturn(Optional.of(targetUser));
        when(accessPolicy.isOwner("owner-1", "class-1")).thenReturn(true);

        assertThrows(com.classroom.common.AppException.class, () -> staffService.assignStaff("class-1", "owner-1",
                List.of(new StaffPermissionDto("COURSE", "CREATE", null)), "owner-1"));
        verify(staffAssignmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("R7-01: assignStaff rejects a course-scoped grant for a non-scopable module (e.g. STORE)")
    void assignStaffRejectsScopedGrantForNonScopableModule() {
        when(userRepository.findById("target-u1")).thenReturn(Optional.of(targetUser));
        when(memberRepository.findByClassIdAndUserId("class-1", "target-u1"))
                .thenReturn(Optional.of(new ClassMember("class-1", "target-u1", "STUDENT")));
        when(staffAssignmentRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.of(assignment));
        when(staffAssignmentRepository.save(any())).thenReturn(assignment);
        Course course = new Course("class-1", "Course A", "FREE");
        course.setId("course-A");
        when(courseRepository.findById("course-A")).thenReturn(Optional.of(course));

        AppException ex = assertThrows(AppException.class, () -> staffService.assignStaff("class-1", "target-u1",
                List.of(new StaffPermissionDto("STORE", "EDIT", "course-A")), "owner-1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(staffPermissionRepository, never()).save(any());
    }

    @Test
    @DisplayName("R7-01: assignStaff accepts a course-scoped grant for COURSE and EXAM (the scopable modules)")
    void assignStaffAcceptsScopedGrantForScopableModules() {
        when(userRepository.findById("target-u1")).thenReturn(Optional.of(targetUser));
        when(memberRepository.findByClassIdAndUserId("class-1", "target-u1"))
                .thenReturn(Optional.of(new ClassMember("class-1", "target-u1", "STUDENT")));
        when(staffAssignmentRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.of(assignment));
        when(staffAssignmentRepository.save(any())).thenReturn(assignment);
        Course course = new Course("class-1", "Course A", "FREE");
        course.setId("course-A");
        when(courseRepository.findById("course-A")).thenReturn(Optional.of(course));

        StaffAssignmentDto dto = staffService.assignStaff("class-1", "target-u1",
                List.of(new StaffPermissionDto("COURSE", "EDIT", "course-A"),
                        new StaffPermissionDto("EXAM", "CREATE", "course-A")), "owner-1");

        assertNotNull(dto);
        verify(staffPermissionRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("R3-07: null permission module is rejected instead of NPE-ing")
    void assignStaffRejectsNullModule() {
        when(userRepository.findById("target-u1")).thenReturn(Optional.of(targetUser));
        when(memberRepository.findByClassIdAndUserId("class-1", "target-u1"))
                .thenReturn(Optional.of(new ClassMember("class-1", "target-u1", "STUDENT")));
        when(staffAssignmentRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.of(assignment));
        when(staffAssignmentRepository.save(any())).thenReturn(assignment);

        assertThrows(com.classroom.common.AppException.class, () -> staffService.assignStaff("class-1", "target-u1",
                List.of(new StaffPermissionDto(null, "CREATE", null)), "owner-1"));
        verify(staffPermissionRepository, never()).save(any());
    }

    @Test
    @DisplayName("R3-07: null permission action is rejected instead of NPE-ing")
    void assignStaffRejectsNullAction() {
        when(userRepository.findById("target-u1")).thenReturn(Optional.of(targetUser));
        when(memberRepository.findByClassIdAndUserId("class-1", "target-u1"))
                .thenReturn(Optional.of(new ClassMember("class-1", "target-u1", "STUDENT")));
        when(staffAssignmentRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.of(assignment));
        when(staffAssignmentRepository.save(any())).thenReturn(assignment);

        assertThrows(com.classroom.common.AppException.class, () -> staffService.assignStaff("class-1", "target-u1",
                List.of(new StaffPermissionDto("COURSE", null, null)), "owner-1"));
        verify(staffPermissionRepository, never()).save(any());
    }

    @Test
    @DisplayName("R3-07: removing staff that was never assigned does not re-emit MEMBER_JOINED")
    void removeStaffNoOpDoesNotEmitMemberJoined() {
        when(staffAssignmentRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.empty());
        when(memberRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.empty());

        staffService.removeStaff("class-1", "target-u1", "owner-1");

        verify(staffAssignmentRepository, never()).delete(any());
        verify(outboxService, never()).recordEvent(anyString(), anyString(), eq("MEMBER_JOINED"), any());
        verify(auditService).record(eq("class-1"), eq("owner-1"), eq("STAFF_REMOVE"), eq("STAFF_ASSIGNMENT"), eq("target-u1"), any());
    }

    @Test
    @DisplayName("R3-07: removing an actual staff assignment does emit MEMBER_JOINED")
    void removeStaffActualAssignmentEmitsMemberJoined() {
        when(staffAssignmentRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.of(assignment));
        ClassMember member = new ClassMember("class-1", "target-u1", "STAFF");
        when(memberRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.of(member));

        staffService.removeStaff("class-1", "target-u1", "owner-1");

        verify(staffAssignmentRepository).delete(assignment);
        verify(outboxService).recordEvent(eq("CLASSROOM"), eq("class-1"), eq("MEMBER_JOINED"), any());
    }

    // --- R4-01: assignStaff must never auto-create membership ---

    @Test
    @DisplayName("R4-01: assignStaff rejects a user who is not a member of the class, instead of auto-adding them")
    void assignStaffRejectsNonMember() {
        when(userRepository.findById("target-u1")).thenReturn(Optional.of(targetUser));
        when(memberRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> staffService.assignStaff("class-1", "target-u1",
                List.of(new StaffPermissionDto("COURSE", "CREATE", null)), "owner-1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(memberRepository, never()).save(any());
        verify(staffAssignmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("R4-01: assignStaff rejects a member whose membership is not ACTIVE")
    void assignStaffRejectsInactiveMember() {
        when(userRepository.findById("target-u1")).thenReturn(Optional.of(targetUser));
        ClassMember inactiveMember = new ClassMember("class-1", "target-u1", "STUDENT");
        inactiveMember.setState("LEFT");
        when(memberRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.of(inactiveMember));

        AppException ex = assertThrows(AppException.class, () -> staffService.assignStaff("class-1", "target-u1",
                List.of(new StaffPermissionDto("COURSE", "CREATE", null)), "owner-1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(memberRepository, never()).save(any());
        verify(staffAssignmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("R4-01: assignStaff succeeds for an existing ACTIVE member without creating a new membership row")
    void assignStaffSucceedsForActiveMemberWithoutCreatingMembership() {
        when(userRepository.findById("target-u1")).thenReturn(Optional.of(targetUser));
        ClassMember activeMember = new ClassMember("class-1", "target-u1", "STUDENT");
        when(memberRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.of(activeMember));
        when(staffAssignmentRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.of(assignment));
        when(staffAssignmentRepository.save(any())).thenReturn(assignment);

        StaffAssignmentDto dto = staffService.assignStaff("class-1", "target-u1",
                List.of(new StaffPermissionDto("COURSE", "CREATE", null)), "owner-1");

        assertNotNull(dto);
        assertEquals("STAFF", activeMember.getRole());
        // The existing member row is updated in place (role -> STAFF); no new ClassMember is ever
        // constructed and saved on the caller's behalf.
        verify(memberRepository).save(activeMember);
    }
}
