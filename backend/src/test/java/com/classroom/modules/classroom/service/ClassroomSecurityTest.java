package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.dto.ClassMemberDto;
import com.classroom.modules.classroom.dto.ClassroomDto;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.model.StaffAssignment;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.classroom.repository.ClassAboutRepository;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.outbox.service.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ClassroomSecurityTest {

    @Mock
    private ClassroomRepository classroomRepository;
    @Mock
    private ClassMemberRepository memberRepository;
    @Mock
    private ClassAboutRepository aboutRepository;
    @Mock
    private StaffAssignmentRepository staffAssignmentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private OutboxService outboxService;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private EntitlementRepository entitlementRepository;

    private ClassroomService classroomService;

    private Classroom activeClass;
    private Classroom inactiveClass;

    @BeforeEach
    void setUp() {
        // A real visibility policy over the mocked AccessPolicy, so member listings are filtered
        // by the same rule the profile endpoint uses.
        classroomService = new ClassroomService(classroomRepository, memberRepository, aboutRepository,
                staffAssignmentRepository, null, userRepository, outboxService, accessPolicy,
                new ProfileVisibilityPolicy(accessPolicy),
                new ProPolicy(accessPolicy, entitlementRepository));

        activeClass = new Classroom("class-active", "owner-1", "active-slug", "Active Class", "Description");
        activeClass.setStatus("ACTIVE");

        inactiveClass = new Classroom("class-inactive", "owner-1", "inactive-slug", "Inactive Class", "Description");
        inactiveClass.setStatus("INACTIVE");
    }

    @Test
    @DisplayName("Finding 1: getClassMembers rejects unauthenticated or non-member callers")
    void testGetClassMembersRejectsNonMember() {
        doThrow(new AppException(ErrorCode.FORBIDDEN, "Bạn không phải là thành viên của lớp học này"))
                .when(accessPolicy).enforceMember("attacker-1", "class-active");

        AppException ex = assertThrows(AppException.class, () ->
                classroomService.getClassMembers("class-active", "attacker-1")
        );
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(memberRepository, never()).findByClassId(any());
    }

    @Test
    @DisplayName("Finding 1: getClassMembers returns privacy-filtered ClassMemberDto for verified members")
    void testGetClassMembersReturnsDto() {
        doNothing().when(accessPolicy).enforceMember("member-1", "class-active");

        ClassMember m1 = new ClassMember("class-active", "member-1", "STUDENT");
        m1.setId("cm-1");
        when(memberRepository.findByClassId("class-active")).thenReturn(List.of(m1));

        User user = new User("member-1", "student@test.local", "hash", "Nguyen Van A", "STUDENT");
        user.setAvatarUrl("https://avatar.test/1.png");
        when(userRepository.findById("member-1")).thenReturn(Optional.of(user));

        List<ClassMemberDto> members = classroomService.getClassMembers("class-active", "member-1");

        assertNotNull(members);
        assertEquals(1, members.size());
        ClassMemberDto dto = members.get(0);
        assertEquals("cm-1", dto.getId());
        assertEquals("member-1", dto.getUserId());
        assertEquals("STUDENT", dto.getRole());
        assertEquals("ACTIVE", dto.getState());
        assertEquals("Nguyen Van A", dto.getUserFullName());
        assertEquals("https://avatar.test/1.png", dto.getUserAvatarUrl());
    }

    @Test
    @DisplayName("Finding 2: getAllClassrooms filters out inactive/private classrooms for public visitors")
    void testGetAllClassroomsFiltersInactiveForPublic() {
        when(classroomRepository.findAll()).thenReturn(List.of(activeClass, inactiveClass));

        List<ClassroomDto> results = classroomService.getAllClassrooms(null);

        assertEquals(1, results.size());
        assertEquals("class-active", results.get(0).getId());
    }

    @Test
    @DisplayName("Finding 2: getAllClassrooms includes inactive classroom for its OWNER")
    void testGetAllClassroomsIncludesInactiveForOwner() {
        when(classroomRepository.findAll()).thenReturn(List.of(activeClass, inactiveClass));

        List<ClassroomDto> results = classroomService.getAllClassrooms("owner-1");

        assertEquals(2, results.size());
    }

    @Test
    @DisplayName("Finding 2: getById rejects inactive classroom for public visitor with UNAUTHORIZED")
    void testGetByIdRejectsInactiveForPublic() {
        when(classroomRepository.findById("class-inactive")).thenReturn(Optional.of(inactiveClass));

        AppException ex = assertThrows(AppException.class, () ->
                classroomService.getById("class-inactive", null)
        );
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    @DisplayName("Finding 2: getById rejects inactive classroom for non-member student with FORBIDDEN")
    void testGetByIdRejectsInactiveForNonMemberStudent() {
        when(classroomRepository.findById("class-inactive")).thenReturn(Optional.of(inactiveClass));

        AppException ex = assertThrows(AppException.class, () ->
                classroomService.getById("class-inactive", "student-1")
        );
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    @DisplayName("Finding 2: getById allows active classroom for public visitors")
    void testGetByIdAllowsActiveForPublic() {
        when(classroomRepository.findById("class-active")).thenReturn(Optional.of(activeClass));

        ClassroomDto dto = classroomService.getById("class-active", null);

        assertNotNull(dto);
        assertEquals("class-active", dto.getId());
        assertEquals("ACTIVE", dto.getStatus());
    }

    @Test
    @DisplayName("Join by known ID is rejected when classroom is inactive")
    void testJoinInactiveClassDenied() {
        when(classroomRepository.findById("class-inactive")).thenReturn(Optional.of(inactiveClass));
        assertThrows(AppException.class, () -> classroomService.joinClassroom("class-inactive", "student-1"));
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("Self-service join cannot reactivate an administratively inactive membership")
    void inactiveMembershipCannotSelfReactivate() {
        when(classroomRepository.findById("class-active")).thenReturn(Optional.of(activeClass));
        ClassMember banned = new ClassMember("class-active", "student-1", "STUDENT");
        banned.setState("BANNED");
        when(memberRepository.findByClassIdAndUserId("class-active", "student-1")).thenReturn(Optional.of(banned));

        assertThrows(AppException.class, () -> classroomService.joinClassroom("class-active", "student-1"));
        verify(memberRepository, never()).save(any());
    }
}
