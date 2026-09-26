package com.classroom.modules.classroom.service;

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

    private StaffService staffService;

    private User targetUser;
    private StaffAssignment assignment;

    @BeforeEach
    void setUp() {
        staffService = new StaffService(staffAssignmentRepository, staffPermissionRepository,
                memberRepository, userRepository, accessPolicy, auditService, null, courseRepository);
        targetUser = new User("target-u1", "staff@test.local", "hashed", "Staff Target", "USER");
        assignment = new StaffAssignment("class-1", "target-u1");
        assignment.setId("assign-1");
    }

    @Test
    @DisplayName("Finding 7: Assigning staff permissions records transactional audit event")
    void testAssignStaffRecordsAudit() {
        when(userRepository.findById("target-u1")).thenReturn(Optional.of(targetUser));
        when(memberRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.empty());
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
        when(memberRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.empty());
        when(staffAssignmentRepository.findByClassIdAndUserId("class-1", "target-u1")).thenReturn(Optional.of(assignment));
        when(staffAssignmentRepository.save(any())).thenReturn(assignment);
        Course foreignCourse = new Course("class-2", "Other class", "FREE");
        foreignCourse.setId("foreign-course");
        when(courseRepository.findById("foreign-course")).thenReturn(Optional.of(foreignCourse));

        assertThrows(com.classroom.common.AppException.class, () -> staffService.assignStaff("class-1", "target-u1",
                List.of(new StaffPermissionDto("COURSE", "EDIT", "foreign-course")), "owner-1"));
        verify(staffPermissionRepository, never()).save(any());
    }
}
