package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.dto.ClassMemberDto;
import com.classroom.modules.classroom.dto.ClassroomDto;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.model.StaffAssignment;
import com.classroom.modules.classroom.model.StaffPermission;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.StaffPermissionRepository;
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
import org.mockito.ArgumentCaptor;
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
    private StaffPermissionRepository staffPermissionRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private OutboxService outboxService;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private EntitlementRepository entitlementRepository;
    @Mock
    private com.classroom.modules.audit.service.AuditService auditService;

    private ClassroomService classroomService;

    private Classroom activeClass;
    private Classroom inactiveClass;

    @BeforeEach
    void setUp() {
        // A real visibility policy over the mocked AccessPolicy, so member listings are filtered
        // by the same rule the profile endpoint uses.
        classroomService = new ClassroomService(classroomRepository, memberRepository, aboutRepository,
                staffAssignmentRepository, staffPermissionRepository, userRepository, outboxService, accessPolicy,
                new ProfileVisibilityPolicy(accessPolicy),
                new ProPolicy(accessPolicy, entitlementRepository),
                auditService);

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
        when(userRepository.findAllById(any())).thenReturn(List.of(user));

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
        // R16-08: visibility is applied by the repository query (anonymous = ACTIVE classes only).
        when(classroomRepository.findPubliclyVisible(any(org.springframework.data.domain.Pageable.class))).thenReturn(List.of(activeClass));

        List<ClassroomDto> results = classroomService.getAllClassrooms(null);

        assertEquals(1, results.size());
        assertEquals("class-active", results.get(0).getId());
    }

    @Test
    @DisplayName("Finding 2: getAllClassrooms includes inactive classroom for its OWNER")
    void testGetAllClassroomsIncludesInactiveForOwner() {
        when(classroomRepository.findVisibleToUser(eq("owner-1"), any(org.springframework.data.domain.Pageable.class))).thenReturn(List.of(activeClass, inactiveClass));

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
        // D-19: visibility is decided by the one rule in AccessPolicy (ClassroomService no longer re-implements it)
        when(accessPolicy.isClassVisibleToUser(activeClass, null)).thenReturn(true);

        ClassroomDto dto = classroomService.getById("class-active", null);

        assertNotNull(dto);
        assertEquals("class-active", dto.getId());
        assertEquals("ACTIVE", dto.getStatus());
    }

    @Test
    @DisplayName("R3-08: paginated getAllClassrooms caps size at 100 and pages through the visibility query")
    void testGetAllClassroomsPaginatedCapsSize() {
        when(classroomRepository.findPubliclyVisible(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(List.of(activeClass));

        List<ClassroomDto> results = classroomService.getAllClassrooms(null, 0, 500);

        assertEquals(1, results.size());
        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(classroomRepository).findPubliclyVisible(captor.capture());
        assertEquals(100, captor.getValue().getPageSize());
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

    @Test
    @DisplayName("R6-01: toDto splits staff grants — studioPermissions stays unscoped-only, "
            + "course-scoped grants surface separately in studioScopedPermissions")
    void toDtoSplitsUnscopedAndScopedStaffGrants() {
        when(classroomRepository.findById("class-active")).thenReturn(Optional.of(activeClass));
        when(accessPolicy.isClassVisibleToUser(activeClass, "staff-1")).thenReturn(true);
        when(accessPolicy.isMember("staff-1", "class-active")).thenReturn(true);

        ClassMember staffMember = new ClassMember("class-active", "staff-1", "STUDENT");
        when(memberRepository.findByClassIdAndUserId("class-active", "staff-1")).thenReturn(Optional.of(staffMember));

        StaffAssignment assignment = new StaffAssignment("class-active", "staff-1");
        assignment.setStatus("ACTIVE");
        when(staffAssignmentRepository.findByClassIdAndUserId("class-active", "staff-1"))
                .thenReturn(Optional.of(assignment));

        StaffPermission unscopedFeedCreate = new StaffPermission(assignment.getId(), "FEED", "CREATE", null);
        StaffPermission scopedCourseEdit = new StaffPermission(assignment.getId(), "COURSE", "EDIT", "course-X");
        StaffPermission scopedExamPublish = new StaffPermission(assignment.getId(), "EXAM", "PUBLISH", "course-X");
        when(staffPermissionRepository.findByAssignmentId(assignment.getId()))
                .thenReturn(List.of(unscopedFeedCreate, scopedCourseEdit, scopedExamPublish));

        ClassroomDto dto = classroomService.getById("class-active", "staff-1");

        assertEquals("STAFF", dto.getUserRole());
        assertEquals(List.of("FEED:CREATE"), dto.getStudioPermissions());
        assertEquals(2, dto.getStudioScopedPermissions().size());
        assertTrue(dto.getStudioScopedPermissions().stream()
                .anyMatch(p -> "COURSE".equals(p.getModule()) && "EDIT".equals(p.getAction()) && "course-X".equals(p.getCourseId())));
        assertTrue(dto.getStudioScopedPermissions().stream()
                .anyMatch(p -> "EXAM".equals(p.getModule()) && "PUBLISH".equals(p.getAction()) && "course-X".equals(p.getCourseId())));
        // Scoped grants must never leak into the class-wide field.
        assertFalse(dto.getStudioPermissions().contains("COURSE:EDIT"));
        assertFalse(dto.getStudioPermissions().contains("EXAM:PUBLISH"));
    }

    // ---- R13-02: PUT /classes/{id} (Studio "Cài đặt lớp") ----

    @Test
    @DisplayName("R13-02: updateClassroom() rejects a caller without CLASS:EDIT (non-owner/unauthorized staff, 403)")
    void updateClassroomRejectsUnauthorizedCaller() {
        doThrow(new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "no"))
                .when(accessPolicy).enforceManage("intruder", "class-active", "CLASS", "EDIT", null);

        com.classroom.modules.classroom.dto.UpdateClassroomRequest req = new com.classroom.modules.classroom.dto.UpdateClassroomRequest();
        req.setTitle("Hacked title");

        assertThrows(AppException.class, () -> classroomService.updateClassroom("class-active", req, "intruder"));
        verify(classroomRepository, never()).save(any());
    }

    @Test
    @DisplayName("R13-02: updateClassroom() persists title/description/coverImageUrl for an authorized caller")
    void updateClassroomPersistsFields() {
        when(classroomRepository.findById("class-active")).thenReturn(Optional.of(activeClass));
        when(classroomRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        com.classroom.modules.classroom.dto.UpdateClassroomRequest req = new com.classroom.modules.classroom.dto.UpdateClassroomRequest();
        req.setTitle("New Title");
        req.setDescription("New description");
        req.setCoverImageUrl("https://example.com/cover.png");

        ClassroomDto dto = classroomService.updateClassroom("class-active", req, "owner-1");

        assertEquals("New Title", dto.getTitle());
        assertEquals("New description", dto.getDescription());
        assertEquals("https://example.com/cover.png", dto.getCoverImageUrl());
        verify(auditService).record(eq("class-active"), eq("owner-1"), eq("CLASS_SETTINGS_UPDATE"), anyString(), anyString(), anyString());
    }

    // ---- R13-02: PUT /classes/{id}/status (archive/unarchive, OWNER-only) ----

    @Test
    @DisplayName("R13-02: updateClassroomStatus() rejects a non-owner (including staff with CLASS:EDIT) - archive is OWNER-only")
    void updateClassroomStatusRejectsNonOwner() {
        doThrow(new AppException(ErrorCode.FORBIDDEN, "no"))
                .when(accessPolicy).enforceOwner("staff-1", "class-active");

        com.classroom.modules.classroom.dto.UpdateClassroomStatusRequest req = new com.classroom.modules.classroom.dto.UpdateClassroomStatusRequest();
        req.setStatus("ARCHIVED");

        assertThrows(AppException.class, () -> classroomService.updateClassroomStatus("class-active", req, "staff-1"));
        verify(classroomRepository, never()).save(any());
    }

    @Test
    @DisplayName("R13-02: updateClassroomStatus() archives an ACTIVE class for its OWNER and audits the transition")
    void updateClassroomStatusArchivesForOwner() {
        when(classroomRepository.findById("class-active")).thenReturn(Optional.of(activeClass));
        when(classroomRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        com.classroom.modules.classroom.dto.UpdateClassroomStatusRequest req = new com.classroom.modules.classroom.dto.UpdateClassroomStatusRequest();
        req.setStatus("ARCHIVED");

        ClassroomDto dto = classroomService.updateClassroomStatus("class-active", req, "owner-1");

        assertEquals("ARCHIVED", dto.getStatus());
        verify(auditService).record(eq("class-active"), eq("owner-1"), eq("CLASS_ARCHIVE"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("R13-02: updateClassroomStatus() rejects a no-op transition to the same status")
    void updateClassroomStatusRejectsNoOpTransition() {
        when(classroomRepository.findById("class-active")).thenReturn(Optional.of(activeClass));

        com.classroom.modules.classroom.dto.UpdateClassroomStatusRequest req = new com.classroom.modules.classroom.dto.UpdateClassroomStatusRequest();
        req.setStatus("ACTIVE");

        assertThrows(AppException.class, () -> classroomService.updateClassroomStatus("class-active", req, "owner-1"));
    }

    // ---- R13-02: rejoin rules for REMOVED vs. BLOCKED members ----

    @Test
    @DisplayName("R13-02: joinClassroom() lets a REMOVED member rejoin on their own (reactivates to ACTIVE)")
    void joinReactivatesRemovedMember() {
        when(classroomRepository.findById("class-active")).thenReturn(Optional.of(activeClass));
        ClassMember removed = new ClassMember("class-active", "student-1", "STUDENT");
        removed.setState("REMOVED");
        when(memberRepository.findByClassIdAndUserId("class-active", "student-1")).thenReturn(Optional.of(removed));
        // D-19: the join itself re-reads the row FOR UPDATE (lock-first) before changing it
        when(memberRepository.findByClassIdAndUserIdForUpdate("class-active", "student-1")).thenReturn(Optional.of(removed));

        classroomService.joinClassroom("class-active", "student-1");

        ArgumentCaptor<ClassMember> captor = ArgumentCaptor.forClass(ClassMember.class);
        verify(memberRepository).save(captor.capture());
        assertEquals("ACTIVE", captor.getValue().getState());
    }

    @Test
    @DisplayName("R13-02: joinClassroom() never lets a BLOCKED member rejoin on their own")
    void joinRejectsBlockedMember() {
        when(classroomRepository.findById("class-active")).thenReturn(Optional.of(activeClass));
        ClassMember blocked = new ClassMember("class-active", "student-1", "STUDENT");
        blocked.setState("BLOCKED");
        when(memberRepository.findByClassIdAndUserId("class-active", "student-1")).thenReturn(Optional.of(blocked));

        assertThrows(AppException.class, () -> classroomService.joinClassroom("class-active", "student-1"));
        verify(memberRepository, never()).save(any());
    }

    // ----- R15-04: non-managers never see REMOVED/BLOCKED members; the headline count is ACTIVE-only -----

    private static ClassMember memberInState(String id, String userId, String state) {
        ClassMember m = new ClassMember("class-active", userId, "STUDENT");
        m.setId(id);
        m.setState(state);
        return m;
    }

    private void stubMixedStateMembers() {
        when(memberRepository.findByClassId("class-active")).thenReturn(List.of(
                memberInState("cm-1", "member-1", "ACTIVE"),
                memberInState("cm-2", "blocked-1", "BLOCKED"),
                memberInState("cm-3", "removed-1", "REMOVED")));
        lenient().when(userRepository.findAllById(any())).thenAnswer(inv -> {
            List<User> users = new java.util.ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                users.add(new User((String) id, id + "@test.local", "hash", "Name " + id, "STUDENT"));
            }
            return users;
        });
    }

    @Test
    @DisplayName("R15-04: an ordinary member only sees ACTIVE members in the listing")
    void testGetClassMembersHidesNonActiveFromPeers() {
        doNothing().when(accessPolicy).enforceMember("member-1", "class-active");
        stubMixedStateMembers();

        List<ClassMemberDto> members = classroomService.getClassMembers("class-active", "member-1");

        assertEquals(1, members.size());
        assertEquals("cm-1", members.get(0).getId());
        assertEquals("ACTIVE", members.get(0).getState());
    }

    @Test
    @DisplayName("R15-04: the owner and staff holding MEMBER:VIEW still see every member state")
    void testGetClassMembersShowsAllStatesToManagers() {
        doNothing().when(accessPolicy).enforceMember("owner-1", "class-active");
        doNothing().when(accessPolicy).enforceMember("staff-1", "class-active");
        when(accessPolicy.isOwner("owner-1", "class-active")).thenReturn(true);
        when(accessPolicy.canManage("staff-1", "class-active", "MEMBER", "VIEW", null)).thenReturn(true);
        stubMixedStateMembers();

        assertEquals(3, classroomService.getClassMembers("class-active", "owner-1").size());
        List<ClassMemberDto> forStaff = classroomService.getClassMembers("class-active", "staff-1");
        assertEquals(3, forStaff.size());
        assertTrue(forStaff.stream().anyMatch(d -> "BLOCKED".equals(d.getState())));
        assertTrue(forStaff.stream().anyMatch(d -> "REMOVED".equals(d.getState())));
    }

    @Test
    @DisplayName("R15-04: staff WITHOUT MEMBER:VIEW get the ACTIVE-only listing")
    void testGetClassMembersFiltersForStaffWithoutMemberView() {
        doNothing().when(accessPolicy).enforceMember("staff-2", "class-active");
        when(accessPolicy.canManage("staff-2", "class-active", "MEMBER", "VIEW", null)).thenReturn(false);
        stubMixedStateMembers();

        List<ClassMemberDto> members = classroomService.getClassMembers("class-active", "staff-2");

        assertEquals(1, members.size());
        assertEquals("ACTIVE", members.get(0).getState());
    }

    @Test
    @DisplayName("R15-04: the classroom card's memberCount counts ACTIVE members only")
    void testMemberCountCountsActiveOnly() {
        when(memberRepository.countByClassIdAndState("class-active", "ACTIVE")).thenReturn(4L);

        ClassroomDto dto = classroomService.toDto(activeClass, null);

        assertEquals(4L, dto.getMemberCount());
        verify(memberRepository, never()).countByClassId(anyString());
    }

    // ----- R16-01: isMember / userRole / memberState reflect the membership STATE, not row existence -----

    private ClassroomDto dtoForMemberInState(String state) {
        ClassMember row = new ClassMember("class-active", "student-1", "STUDENT");
        row.setState(state);
        when(memberRepository.findByClassIdAndUserId("class-active", "student-1")).thenReturn(Optional.of(row));
        return classroomService.toDto(activeClass, "student-1");
    }

    @Test
    @DisplayName("R16-01: an ACTIVE membership row is a member (STUDENT, memberState ACTIVE)")
    void toDtoActiveMemberIsMember() {
        ClassroomDto dto = dtoForMemberInState("ACTIVE");

        assertTrue(dto.isMember());
        assertEquals("STUDENT", dto.getUserRole());
        assertEquals("ACTIVE", dto.getMemberState());
    }

    @Test
    @DisplayName("R16-01: a REMOVED row is NOT a member - GUEST role, memberState REMOVED (may rejoin)")
    void toDtoRemovedRowIsNotMember() {
        ClassroomDto dto = dtoForMemberInState("REMOVED");

        assertFalse(dto.isMember(), "a removed member must not be presented as a member");
        assertEquals("GUEST", dto.getUserRole());
        assertEquals("REMOVED", dto.getMemberState());
        assertTrue(dto.getStudioPermissions().isEmpty());
    }

    @Test
    @DisplayName("R16-01: a BLOCKED row is NOT a member - GUEST role, memberState BLOCKED")
    void toDtoBlockedRowIsNotMember() {
        ClassroomDto dto = dtoForMemberInState("BLOCKED");

        assertFalse(dto.isMember());
        assertEquals("GUEST", dto.getUserRole());
        assertEquals("BLOCKED", dto.getMemberState());
    }

    @Test
    @DisplayName("R16-01: the legacy BANNED state (and any unknown state) is reported as BLOCKED, never as a rejoinable state")
    void toDtoLegacyBannedReportedAsBlocked() {
        assertEquals("BLOCKED", dtoForMemberInState("BANNED").getMemberState());
    }

    @Test
    @DisplayName("R16-01: a stale staff assignment does not resurrect a REMOVED member as STAFF")
    void toDtoRemovedMemberWithStaleStaffRowIsGuest() {
        ClassMember row = new ClassMember("class-active", "staff-9", "STUDENT");
        row.setState("REMOVED");
        when(memberRepository.findByClassIdAndUserId("class-active", "staff-9")).thenReturn(Optional.of(row));

        ClassroomDto dto = classroomService.toDto(activeClass, "staff-9");

        assertFalse(dto.isMember());
        assertEquals("GUEST", dto.getUserRole());
        verify(staffAssignmentRepository, never()).findByClassIdAndUserId(anyString(), anyString());
    }

    @Test
    @DisplayName("R16-01: no row -> memberState NONE; owner -> ACTIVE; anonymous -> NONE")
    void toDtoNoRowOwnerAndAnonymousMemberState() {
        when(memberRepository.findByClassIdAndUserId("class-active", "stranger")).thenReturn(Optional.empty());

        ClassroomDto stranger = classroomService.toDto(activeClass, "stranger");
        assertFalse(stranger.isMember());
        assertEquals("NONE", stranger.getMemberState());
        assertEquals("GUEST", stranger.getUserRole());

        ClassroomDto owner = classroomService.toDto(activeClass, "owner-1");
        assertTrue(owner.isMember());
        assertEquals("OWNER", owner.getUserRole());
        assertEquals("ACTIVE", owner.getMemberState());

        ClassroomDto anonymous = classroomService.toDto(activeClass, null);
        assertFalse(anonymous.isMember());
        assertEquals("NONE", anonymous.getMemberState());
    }

    @Test
    @DisplayName("R16-01: a REMOVED member that self-joins comes back ACTIVE (memberState ACTIVE, isMember true)")
    void joinRemovedMemberReturnsActiveDto() {
        when(classroomRepository.findById("class-active")).thenReturn(Optional.of(activeClass));
        ClassMember removed = new ClassMember("class-active", "student-1", "STUDENT");
        removed.setState("REMOVED");
        when(memberRepository.findByClassIdAndUserId("class-active", "student-1")).thenReturn(Optional.of(removed));
        when(memberRepository.findByClassIdAndUserIdForUpdate("class-active", "student-1")).thenReturn(Optional.of(removed));

        ClassroomDto dto = classroomService.joinClassroom("class-active", "student-1");

        assertTrue(dto.isMember());
        assertEquals("ACTIVE", dto.getMemberState());
    }

    // ----- R16-08: the class listing is capped and batch-loaded -----

    @Test
    @DisplayName("R16-08: with no page/size the listing is capped at the default page size (50), newest first")
    void listingDefaultsToBoundedPage() {
        when(classroomRepository.findPubliclyVisible(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(List.of());

        classroomService.getAllClassrooms(null);

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(classroomRepository).findPubliclyVisible(captor.capture());
        assertEquals(ClassroomService.DEFAULT_PAGE_SIZE, captor.getValue().getPageSize());
        assertEquals(50, captor.getValue().getPageSize());
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(org.springframework.data.domain.Sort.Direction.DESC,
                captor.getValue().getSort().getOrderFor("createdAt").getDirection());
    }

    @Test
    @DisplayName("R16-08: an authenticated listing uses the user-scoped visibility query and honours page/size")
    void listingForUserPassesPageAndClampsSize() {
        when(classroomRepository.findVisibleToUser(eq("student-1"), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(List.of());

        classroomService.getAllClassrooms("student-1", 2, 0);

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(classroomRepository).findVisibleToUser(eq("student-1"), captor.capture());
        assertEquals(2, captor.getValue().getPageNumber());
        assertEquals(1, captor.getValue().getPageSize(), "a size below 1 is raised to 1");
        verify(classroomRepository, never()).findPubliclyVisible(any());
    }

    @Test
    @DisplayName("R16-08: a page of classes is rendered with batched lookups (no per-class findById/count/membership queries)")
    void listingBatchLoadsOwnersCountsAndMemberships() {
        Classroom classA = new Classroom("class-a", "owner-a", "a", "Class A", "d");
        Classroom classB = new Classroom("class-b", "owner-b", "b", "Class B", "d");
        Classroom classC = new Classroom("class-c", "student-1", "c", "Class C", "d");
        when(classroomRepository.findVisibleToUser(eq("student-1"), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(List.of(classA, classB, classC));

        when(userRepository.findAllById(any())).thenReturn(List.of(
                new User("owner-a", "a@t.local", "h", "Owner A", "USER"),
                new User("owner-b", "b@t.local", "h", "Owner B", "USER"),
                new User("student-1", "s@t.local", "h", "Student One", "USER")));
        when(memberRepository.countActiveByClassIds(any())).thenReturn(List.<Object[]>of(
                new Object[]{"class-a", 3L}, new Object[]{"class-c", 1L}));

        // student-1: ACTIVE member of A (also staff there), REMOVED from B, owner of C.
        ClassMember activeInA = new ClassMember("class-a", "student-1", "STUDENT");
        ClassMember removedInB = new ClassMember("class-b", "student-1", "STUDENT");
        removedInB.setState("REMOVED");
        when(memberRepository.findByUserIdAndClassIdIn(eq("student-1"), any())).thenReturn(List.of(activeInA, removedInB));
        StaffAssignment staffInA = new StaffAssignment("class-a", "student-1");
        staffInA.setStatus("ACTIVE");
        when(staffAssignmentRepository.findByUserIdAndClassIdIn(eq("student-1"), any())).thenReturn(List.of(staffInA));
        when(staffPermissionRepository.findByAssignmentIdIn(any())).thenReturn(List.of(
                new StaffPermission(staffInA.getId(), "FEED", "CREATE", null)));
        when(entitlementRepository.findClassIdsWithActiveEntitlement(eq("student-1"), any(), any()))
                .thenReturn(List.of("class-a"));

        List<ClassroomDto> dtos = classroomService.getAllClassrooms("student-1");

        assertEquals(3, dtos.size());
        ClassroomDto a = dtos.get(0);
        assertEquals("Owner A", a.getOwnerName());
        assertEquals(3L, a.getMemberCount());
        assertTrue(a.isMember());
        assertEquals("STAFF", a.getUserRole());
        assertEquals(List.of("FEED:CREATE"), a.getStudioPermissions());
        assertTrue(a.isPro());

        ClassroomDto b = dtos.get(1);
        assertEquals("Owner B", b.getOwnerName());
        assertEquals(0L, b.getMemberCount());
        assertFalse(b.isMember());
        assertEquals("GUEST", b.getUserRole());
        assertEquals("REMOVED", b.getMemberState());
        assertFalse(b.isPro());

        ClassroomDto c = dtos.get(2);
        assertTrue(c.isOwner());
        assertEquals("OWNER", c.getUserRole());
        assertTrue(c.isPro());
        assertEquals(1L, c.getMemberCount());

        // The whole page cost a fixed number of queries - nothing per class.
        verify(userRepository, times(1)).findAllById(any());
        verify(userRepository, never()).findById(anyString());
        verify(memberRepository, times(1)).countActiveByClassIds(any());
        verify(memberRepository, never()).countByClassIdAndState(anyString(), anyString());
        verify(memberRepository, times(1)).findByUserIdAndClassIdIn(eq("student-1"), any());
        verify(memberRepository, never()).findByClassIdAndUserId(anyString(), anyString());
        verify(staffAssignmentRepository, times(1)).findByUserIdAndClassIdIn(eq("student-1"), any());
        verify(staffAssignmentRepository, never()).findByClassIdAndUserId(anyString(), anyString());
        verify(staffPermissionRepository, times(1)).findByAssignmentIdIn(any());
        verify(staffPermissionRepository, never()).findByAssignmentId(anyString());
        verify(entitlementRepository, times(1)).findClassIdsWithActiveEntitlement(eq("student-1"), any(), any());
        verify(entitlementRepository, never()).hasActiveProEntitlement(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("R16-08: getClassMembers batch-loads the roster's users once and resolves the viewer's privacy context once")
    void getClassMembersBatchLoadsUsersOnce() {
        doNothing().when(accessPolicy).enforceMember("member-1", "class-active");
        when(memberRepository.findByClassId("class-active")).thenReturn(List.of(
                memberInState("cm-1", "u-1", "ACTIVE"),
                memberInState("cm-2", "u-2", "ACTIVE"),
                memberInState("cm-3", "u-3", "ACTIVE")));
        when(accessPolicy.isMember("member-1", "class-active")).thenReturn(true);
        when(userRepository.findAllById(any())).thenAnswer(inv -> {
            List<User> users = new java.util.ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                User u = new User((String) id, id + "@t.local", "h", "Name " + id, "STUDENT");
                u.setProfileVisibility("CLASS");
                users.add(u);
            }
            return users;
        });

        List<ClassMemberDto> members = classroomService.getClassMembers("class-active", "member-1");

        assertEquals(3, members.size());
        assertTrue(members.stream().allMatch(m -> m.getUserId() != null && m.getUserFullName().startsWith("Name ")));
        verify(userRepository, times(1)).findAllById(any());
        verify(userRepository, never()).findById(anyString());
        // 3 rows x (id, name, avatar) used to cost 9 evaluations of the viewer's admin/peer status.
        verify(accessPolicy, times(1)).canManage("member-1", "class-active", "MEMBER", "VIEW", null);
        verify(accessPolicy, times(1)).isMember("member-1", "class-active");
    }

    // ----- R16-07: managers see who a REMOVED / BLOCKED member is; peers do not -----

    @Test
    @DisplayName("R16-07: the owner sees the real name of a PRIVATE REMOVED/BLOCKED member row; a MEMBER:VIEW manager too")
    void managersSeeIdentityOfNonActiveMembers() {
        doNothing().when(accessPolicy).enforceMember("owner-1", "class-active");
        when(accessPolicy.isOwner("owner-1", "class-active")).thenReturn(true);
        when(memberRepository.findByClassId("class-active")).thenReturn(List.of(
                memberInState("cm-2", "blocked-1", "BLOCKED"),
                memberInState("cm-3", "removed-1", "REMOVED")));
        when(userRepository.findAllById(any())).thenAnswer(inv -> {
            List<User> users = new java.util.ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                User u = new User((String) id, id + "@t.local", "h", "Name " + id, "STUDENT");
                u.setProfileVisibility("PRIVATE");
                users.add(u);
            }
            return users;
        });

        List<ClassMemberDto> members = classroomService.getClassMembers("class-active", "owner-1");

        assertEquals(2, members.size());
        assertEquals("Name blocked-1", members.get(0).getUserFullName());
        assertEquals("blocked-1", members.get(0).getUserId());
        assertEquals("Name removed-1", members.get(1).getUserFullName());
        assertEquals("removed-1", members.get(1).getUserId());
    }}
