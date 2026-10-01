package com.classroom.modules.classroom.policy;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.model.StaffAssignment;
import com.classroom.modules.classroom.model.StaffPermission;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AccessPolicyTest {

    @Mock
    private ClassroomRepository classroomRepository;
    @Mock
    private ClassMemberRepository memberRepository;
    @Mock
    private StaffAssignmentRepository staffAssignmentRepository;
    @Mock
    private StaffPermissionRepository staffPermissionRepository;

    @InjectMocks
    private AccessPolicy accessPolicy;

    private Classroom classA;
    private Classroom classB;

    @Test void missingStaffAssignmentNeedsNoMembershipRead() {
        assertFalse(accessPolicy.isActiveStaff("learner", "class-a-id"));
        verifyNoInteractions(classroomRepository, memberRepository);
    }

    @Test void activeAssignmentCannotBypassRemovedBlockedOrExpiredMembership() {
        var assignment = new StaffAssignment("class-a-id", "staff"); assignment.setStatus("ACTIVE");
        when(staffAssignmentRepository.findByClassIdAndUserId("class-a-id", "staff")).thenReturn(Optional.of(assignment));
        when(classroomRepository.findById("class-a-id")).thenReturn(Optional.of(classA));
        var member = new ClassMember("class-a-id", "staff", "STAFF");
        when(memberRepository.findByClassIdAndUserId("class-a-id", "staff")).thenReturn(Optional.of(member));
        member.setState("ACTIVE"); assertTrue(accessPolicy.isActiveStaff("staff", "class-a-id"));
        for (String state : List.of("REMOVED", "BLOCKED", "EXPIRED")) {
            member.setState(state); assertFalse(accessPolicy.isActiveStaff("staff", "class-a-id"), state);
        }
        member.setState("ACTIVE"); member.setAccessExpiresAt(java.time.Instant.now().minusSeconds(1));
        assertFalse(accessPolicy.isActiveStaff("staff", "class-a-id"));
    }

    @BeforeEach
    void setUp() {
        classA = new Classroom("class-a-id", "owner-1-id", "class-a", "Class A", "Desc A");
        classB = new Classroom("class-b-id", "owner-2-id", "class-b", "Class B", "Desc B");
    }

    @Test
    @DisplayName("UT-01: OWNER of class A has access to class A, but is denied for class B")
    void testOwnerClassroomIsolation() {
        when(classroomRepository.findById("class-a-id")).thenReturn(Optional.of(classA));
        when(classroomRepository.findById("class-b-id")).thenReturn(Optional.of(classB));

        // Owner 1 accessing class A
        assertTrue(accessPolicy.isOwner("owner-1-id", "class-a-id"));
        assertTrue(accessPolicy.canManage("owner-1-id", "class-a-id", "COURSE", "EDIT", null));

        // Owner 1 trying to access class B
        assertFalse(accessPolicy.isOwner("owner-1-id", "class-b-id"));
        assertThrows(AppException.class, () -> accessPolicy.enforceOwner("owner-1-id", "class-b-id"));
    }

    @Test
    @DisplayName("UT-02: STAFF with only EXAM_GRADE cannot edit prices, change staff permissions or manage other modules")
    void testStaffPermissionBoundaries() {
        String staffUserId = "staff-user-id";
        StaffAssignment assignment = new StaffAssignment("class-a-id", staffUserId);
        assignment.setId("assign-1");
        assignment.setStatus("ACTIVE");

        StaffPermission gradePerm = new StaffPermission("assign-1", "EXAM", "GRADE", null);

        when(classroomRepository.findById("class-a-id")).thenReturn(Optional.of(classA));
        ClassMember activeMember = new ClassMember("class-a-id", staffUserId, "STAFF");
        activeMember.setState("ACTIVE");
        when(memberRepository.findByClassIdAndUserId("class-a-id", staffUserId)).thenReturn(Optional.of(activeMember));
        when(staffAssignmentRepository.findByClassIdAndUserId("class-a-id", staffUserId))
                .thenReturn(Optional.of(assignment));
        when(staffPermissionRepository.findByAssignmentId("assign-1"))
                .thenReturn(List.of(gradePerm));

        // Allowed to GRADE exams
        assertTrue(accessPolicy.canManage(staffUserId, "class-a-id", "EXAM", "GRADE", null));

        // DENIED to EDIT prices / STORE
        assertFalse(accessPolicy.canManage(staffUserId, "class-a-id", "STORE", "CREATE", null));

        // DENIED to manage other STAFF permissions
        assertFalse(accessPolicy.canManage(staffUserId, "class-a-id", "STAFF", "EDIT", null));

        assertThrows(AppException.class, () ->
                accessPolicy.enforceManage(staffUserId, "class-a-id", "STORE", "CREATE", null)
        );
    }

    @Test
    @DisplayName("Answer-key permission honors course scope even when wildcard grants coexist")
    void answerKeyPermissionHonorsCourseScope() {
        String staff = "scoped-staff";
        StaffAssignment assignment = new StaffAssignment("class-a-id", staff);
        assignment.setId("scoped-assignment");
        assignment.setStatus("ACTIVE");
        ClassMember activeMember = new ClassMember("class-a-id", staff, "STAFF");
        activeMember.setState("ACTIVE");
        when(classroomRepository.findById("class-a-id")).thenReturn(Optional.of(classA));
        when(memberRepository.findByClassIdAndUserId("class-a-id", staff)).thenReturn(Optional.of(activeMember));
        when(staffAssignmentRepository.findByClassIdAndUserId("class-a-id", staff)).thenReturn(Optional.of(assignment));
        when(staffPermissionRepository.findByAssignmentId("scoped-assignment")).thenReturn(List.of(
                new StaffPermission("scoped-assignment", "EXAM", "*", null),
                new StaffPermission("scoped-assignment", "EXAM", "EDIT", "course-a")));

        assertTrue(accessPolicy.canAccessAnswerKey(staff, "class-a-id", "course-a"));
        assertFalse(accessPolicy.canAccessAnswerKey(staff, "class-a-id", "course-b"));
        assertFalse(accessPolicy.canAccessAnswerKey(staff, "class-a-id", null));
    }
}
