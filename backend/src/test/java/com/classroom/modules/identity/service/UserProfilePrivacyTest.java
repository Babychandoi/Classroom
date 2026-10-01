package com.classroom.modules.identity.service;

import com.classroom.common.AppException;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.identity.dto.UserProfileDto;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.policy.LearningPolicy;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.repository.LessonProgressRepository;
import com.classroom.modules.learning.repository.LessonRepository;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.exam.repository.ExamRepository;
import com.classroom.modules.ranking.model.LeaderboardEntry;
import com.classroom.modules.ranking.repository.LeaderboardEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class UserProfilePrivacyTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private ClassMemberRepository classMemberRepository;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private ProPolicy proPolicy;
    @Mock
    private LeaderboardEntryRepository leaderboardEntryRepository;
    @Mock
    private CourseRepository courseRepository;
    @Mock
    private LessonRepository lessonRepository;
    @Mock
    private LessonProgressRepository lessonProgressRepository;
    @Mock
    private ExamAttemptRepository examAttemptRepository;
    @Mock
    private ExamRepository examRepository;
    @Mock
    private LearningPolicy learningPolicy;

    private UserService userService;

    private User targetUser;

    @BeforeEach
    void setUp() {
        // A real policy over the mocked AccessPolicy: the visibility rule under test is the
        // shared one, not a stubbed stand-in.
        userService = new UserService(userRepository, classMemberRepository, accessPolicy, proPolicy,
                leaderboardEntryRepository, new ProfileVisibilityPolicy(accessPolicy), courseRepository,
                lessonRepository, lessonProgressRepository, examAttemptRepository, examRepository, learningPolicy);
        targetUser = new User("target-1", "target@classroom.local", "hashed", "Target User", "STUDENT");
        targetUser.setAvatarUrl("https://example.com/avatar.png");
        targetUser.setBio("Hello world");
        targetUser.setStatus("ACTIVE");
        targetUser.setProfileVisibility("PUBLIC");
    }

    @Test
    @DisplayName("Finding 1: Self profile access returns all private account fields")
    void testSelfProfileReturnsFullData() {
        when(userRepository.findById("target-1")).thenReturn(Optional.of(targetUser));

        UserProfileDto profile = userService.getProfileForViewer("target-1", "target-1", null);

        assertNotNull(profile);
        assertEquals("target-1", profile.getId());
        assertEquals("target@classroom.local", profile.getEmail());
        assertEquals("STUDENT", profile.getRole());
        assertEquals("ACTIVE", profile.getStatus());
        assertNotNull(profile.getCreatedAt());
        assertEquals("Target User", profile.getFullName());
    }

    @Test
    @DisplayName("Finding 1: Peer viewing another user without class context omits private account fields")
    void testPeerViewWithoutClassContextOmitsPrivateFields() {
        when(userRepository.findById("target-1")).thenReturn(Optional.of(targetUser));

        UserProfileDto profile = userService.getProfileForViewer("target-1", "peer-viewer", null);

        assertNotNull(profile);
        assertEquals("target-1", profile.getId());
        assertEquals("Target User", profile.getFullName());
        assertEquals("https://example.com/avatar.png", profile.getAvatarUrl());
        assertEquals("Hello world", profile.getBio());

        // Private fields must be null
        assertNull(profile.getEmail());
        assertNull(profile.getRole());
        assertNull(profile.getStatus());
        assertNull(profile.getCreatedAt());
    }

    @Test
    @DisplayName("Finding 1: Peer viewing another user in class context omits private account data but includes class progress")
    void testPeerInClassContextOmitsPrivateAccountData() {
        when(userRepository.findById("target-1")).thenReturn(Optional.of(targetUser));
        when(accessPolicy.isMember("peer-viewer", "class-1")).thenReturn(true);
        when(accessPolicy.isOwner("peer-viewer", "class-1")).thenReturn(false);

        ClassMember member = new ClassMember("class-1", "target-1", "STUDENT");
        when(classMemberRepository.findByClassIdAndUserId("class-1", "target-1")).thenReturn(Optional.of(member));
        when(accessPolicy.isOwner("target-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("peer-viewer", "class-1", "MEMBER", "VIEW", null)).thenReturn(false);
        when(proPolicy.isPro("target-1", "class-1")).thenReturn(true);

        LeaderboardEntry entry = new LeaderboardEntry("class-1", "target-1", 150, "SILVER");
        when(leaderboardEntryRepository.findByClassIdAndUserId("class-1", "target-1")).thenReturn(Optional.of(entry));

        UserProfileDto profile = userService.getProfileForViewer("target-1", "peer-viewer", "class-1");

        assertNotNull(profile);
        assertEquals("STUDENT", profile.getMembershipRole());
        assertTrue(profile.isPro());
        assertEquals(150, profile.getTotalPoints());
        assertEquals("SILVER", profile.getRankTier());

        // Private fields must be omitted for peer student
        assertNull(profile.getEmail());
        assertNull(profile.getRole());
        assertNull(profile.getStatus());
        assertNull(profile.getCreatedAt());
    }

    @Test
    @DisplayName("Private profiles omit identifying and class-journey fields in both general and class views")
    void privateProfileIsHiddenInGeneralAndClassViews() {
        targetUser.setProfileVisibility("PRIVATE");
        when(userRepository.findById("target-1")).thenReturn(Optional.of(targetUser));

        UserProfileDto general = userService.getProfileForViewer("target-1", "peer-viewer", null);
        assertNull(general.getFullName());
        assertNull(general.getAvatarUrl());
        assertNull(general.getBio());

        when(accessPolicy.isMember("peer-viewer", "class-1")).thenReturn(true);
        when(accessPolicy.isOwner("peer-viewer", "class-1")).thenReturn(false);
        when(classMemberRepository.findByClassIdAndUserId("class-1", "target-1"))
                .thenReturn(Optional.of(new ClassMember("class-1", "target-1", "STUDENT")));
        when(accessPolicy.isOwner("target-1", "class-1")).thenReturn(false);
        UserProfileDto classView = userService.getProfileForViewer("target-1", "peer-viewer", "class-1");
        assertNull(classView.getFullName());
        assertNull(classView.getMembershipRole());
        assertEquals(0, classView.getTotalPoints());
    }

    @Test
    @DisplayName("CLASS visibility requires a class context where the viewer is an active member")
    void classVisibilityRequiresClassMembershipContext() {
        targetUser.setProfileVisibility("CLASS");
        when(userRepository.findById("target-1")).thenReturn(Optional.of(targetUser));
        assertNull(userService.getProfileForViewer("target-1", "peer-viewer", null).getFullName());

        when(accessPolicy.isMember("peer-viewer", "class-1")).thenReturn(true);
        when(accessPolicy.isOwner("peer-viewer", "class-1")).thenReturn(false);
        when(classMemberRepository.findByClassIdAndUserId("class-1", "target-1"))
                .thenReturn(Optional.of(new ClassMember("class-1", "target-1", "STUDENT")));
        when(accessPolicy.isOwner("target-1", "class-1")).thenReturn(false);
        assertEquals("Target User", userService.getProfileForViewer("target-1", "peer-viewer", "class-1").getFullName());
    }

    @Test
    @DisplayName("Finding 1: Class non-member viewer is rejected with FORBIDDEN")
    void testNonMemberViewerRejected() {
        when(userRepository.findById("target-1")).thenReturn(Optional.of(targetUser));
        when(accessPolicy.isMember("outsider", "class-1")).thenReturn(false);
        when(accessPolicy.isOwner("outsider", "class-1")).thenReturn(false);

        assertThrows(AppException.class, () -> userService.getProfileForViewer("target-1", "outsider", "class-1"));
    }

    @Test
    @DisplayName("Finding 1: Target user not in class throws NOT_FOUND")
    void testTargetNotInClassThrowsNotFound() {
        when(userRepository.findById("target-1")).thenReturn(Optional.of(targetUser));
        when(accessPolicy.isMember("owner-1", "class-1")).thenReturn(true);
        when(classMemberRepository.findByClassIdAndUserId("class-1", "target-1")).thenReturn(Optional.empty());
        when(accessPolicy.isOwner("target-1", "class-1")).thenReturn(false);

        assertThrows(AppException.class, () -> userService.getProfileForViewer("target-1", "owner-1", "class-1"));
    }

    @Test
    @DisplayName("Class owner sees a PRIVATE learner's identity, email and class journey in their own class")
    void ownerSeesPrivateLearnerInOwnClass() {
        targetUser.setProfileVisibility("PRIVATE");
        when(userRepository.findById("target-1")).thenReturn(Optional.of(targetUser));
        when(accessPolicy.isOwner("owner-1", "class-1")).thenReturn(true);
        when(classMemberRepository.findByClassIdAndUserId("class-1", "target-1"))
                .thenReturn(Optional.of(new ClassMember("class-1", "target-1", "STUDENT")));
        when(accessPolicy.isOwner("target-1", "class-1")).thenReturn(false);
        // R4-01/R16-07: the class-admin privacy override requires the target to have a membership
        // row in the class (any state).
        when(accessPolicy.hasMembershipRecord("target-1", "class-1")).thenReturn(true);
        when(proPolicy.isPro("target-1", "class-1")).thenReturn(false);
        when(leaderboardEntryRepository.findByClassIdAndUserId("class-1", "target-1"))
                .thenReturn(Optional.of(new LeaderboardEntry("class-1", "target-1", 150, "SILVER")));

        UserProfileDto profile = userService.getProfileForViewer("target-1", "owner-1", "class-1");

        assertEquals("Target User", profile.getFullName());
        assertEquals("target@classroom.local", profile.getEmail());
        assertEquals("STUDENT", profile.getMembershipRole());
        assertEquals(150, profile.getTotalPoints());
        assertEquals("SILVER", profile.getRankTier());
    }

    @Test
    @DisplayName("Staff holding MEMBER/VIEW see a PRIVATE learner; the override does not leak outside the class")
    void staffWithMemberViewSeePrivateLearnerOnlyInThatClass() {
        targetUser.setProfileVisibility("PRIVATE");
        when(userRepository.findById("target-1")).thenReturn(Optional.of(targetUser));
        when(accessPolicy.isMember("staff-1", "class-1")).thenReturn(true);
        when(accessPolicy.isOwner("staff-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("staff-1", "class-1", "MEMBER", "VIEW", null)).thenReturn(true);
        when(classMemberRepository.findByClassIdAndUserId("class-1", "target-1"))
                .thenReturn(Optional.of(new ClassMember("class-1", "target-1", "STUDENT")));
        when(accessPolicy.isOwner("target-1", "class-1")).thenReturn(false);
        // R4-01/R16-07: the class-admin privacy override requires the target to have a membership
        // row in the class (any state).
        when(accessPolicy.hasMembershipRecord("target-1", "class-1")).thenReturn(true);
        when(proPolicy.isPro("target-1", "class-1")).thenReturn(false);

        UserProfileDto inClass = userService.getProfileForViewer("target-1", "staff-1", "class-1");
        assertEquals("Target User", inClass.getFullName());
        assertEquals("target@classroom.local", inClass.getEmail());

        // Without a class context there is no class to administer, so privacy still applies.
        UserProfileDto general = userService.getProfileForViewer("target-1", "staff-1", null);
        assertNull(general.getFullName());
        assertNull(general.getEmail());
    }

    // ----- R15-04: a REMOVED/BLOCKED member's profile is only reachable by class administrators -----

    @Test
    @DisplayName("R15-04: an ordinary member gets 404 for a REMOVED/BLOCKED member's profile, never a state leak")
    void peerCannotOpenNonActiveMemberProfile() {
        when(userRepository.findById("target-1")).thenReturn(Optional.of(targetUser));
        when(accessPolicy.isMember("peer-1", "class-1")).thenReturn(true);

        for (String state : new String[]{"REMOVED", "BLOCKED", "BANNED"}) {
            ClassMember inactive = new ClassMember("class-1", "target-1", "STUDENT");
            inactive.setState(state);
            when(classMemberRepository.findByClassIdAndUserId("class-1", "target-1")).thenReturn(Optional.of(inactive));

            AppException ex = assertThrows(AppException.class,
                    () -> userService.getProfileForViewer("target-1", "peer-1", "class-1"), state);
            assertEquals(com.classroom.common.ErrorCode.NOT_FOUND, ex.getErrorCode(), state);
        }
    }

    @Test
    @DisplayName("R15-04: the owner and staff holding MEMBER:VIEW can still open a BLOCKED member's profile")
    void classAdministratorsStillOpenNonActiveMemberProfile() {
        when(userRepository.findById("target-1")).thenReturn(Optional.of(targetUser));
        ClassMember blocked = new ClassMember("class-1", "target-1", "STUDENT");
        blocked.setState("BLOCKED");
        when(classMemberRepository.findByClassIdAndUserId("class-1", "target-1")).thenReturn(Optional.of(blocked));
        when(accessPolicy.isMember("owner-1", "class-1")).thenReturn(true);
        when(accessPolicy.isOwner("owner-1", "class-1")).thenReturn(true);
        when(accessPolicy.isOwner("target-1", "class-1")).thenReturn(false);
        // The service asks canManage for the owner too (owner holds every permission via isOwner).
        lenient().when(accessPolicy.canManage("owner-1", "class-1", "MEMBER", "VIEW", null)).thenReturn(true);
        when(accessPolicy.isMember("staff-1", "class-1")).thenReturn(true);
        when(accessPolicy.isOwner("staff-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("staff-1", "class-1", "MEMBER", "VIEW", null)).thenReturn(true);

        assertEquals("target-1", userService.getProfileForViewer("target-1", "owner-1", "class-1").getId());
        assertEquals("target-1", userService.getProfileForViewer("target-1", "staff-1", "class-1").getId());
    }

    @Test
    @DisplayName("R15-04: staff WITHOUT MEMBER:VIEW are treated like any peer for a non-ACTIVE member")
    void staffWithoutMemberViewCannotOpenNonActiveMemberProfile() {
        when(userRepository.findById("target-1")).thenReturn(Optional.of(targetUser));
        ClassMember removed = new ClassMember("class-1", "target-1", "STUDENT");
        removed.setState("REMOVED");
        when(classMemberRepository.findByClassIdAndUserId("class-1", "target-1")).thenReturn(Optional.of(removed));
        when(accessPolicy.isMember("staff-2", "class-1")).thenReturn(true);
        when(accessPolicy.canManage("staff-2", "class-1", "MEMBER", "VIEW", null)).thenReturn(false);

        AppException ex = assertThrows(AppException.class,
                () -> userService.getProfileForViewer("target-1", "staff-2", "class-1"));
        assertEquals(com.classroom.common.ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    // ----- R16-07: administrators see the identity of a REMOVED/BLOCKED member; nobody else does -----

    private void stubRosterRow(String state) {
        targetUser.setProfileVisibility("PRIVATE");
        when(userRepository.findById("target-1")).thenReturn(Optional.of(targetUser));
        ClassMember row = new ClassMember("class-1", "target-1", "STUDENT");
        row.setState(state);
        when(classMemberRepository.findByClassIdAndUserId("class-1", "target-1")).thenReturn(Optional.of(row));
        when(accessPolicy.isOwner("target-1", "class-1")).thenReturn(false);
        // The member is no longer ACTIVE but still has a roster row.
        when(accessPolicy.hasMembershipRecord("target-1", "class-1")).thenReturn(true);
    }

    @Test
    @DisplayName("R16-07: the owner sees a PRIVATE BLOCKED/REMOVED member's name, avatar and email in the class-admin context")
    void ownerSeesIdentityOfPrivateNonActiveMember() {
        for (String state : new String[]{"BLOCKED", "REMOVED"}) {
            stubRosterRow(state);
            when(accessPolicy.isOwner("owner-1", "class-1")).thenReturn(true);
            when(accessPolicy.isMember("owner-1", "class-1")).thenReturn(true);

            UserProfileDto profile = userService.getProfileForViewer("target-1", "owner-1", "class-1");

            assertEquals("Target User", profile.getFullName(), state);
            assertEquals("https://example.com/avatar.png", profile.getAvatarUrl(), state);
            assertEquals("target@classroom.local", profile.getEmail(), state);
        }
    }

    @Test
    @DisplayName("R16-07: staff holding MEMBER:VIEW see a PRIVATE REMOVED member's identity too")
    void memberViewStaffSeeIdentityOfPrivateRemovedMember() {
        stubRosterRow("REMOVED");
        when(accessPolicy.isMember("staff-1", "class-1")).thenReturn(true);
        when(accessPolicy.isOwner("staff-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("staff-1", "class-1", "MEMBER", "VIEW", null)).thenReturn(true);

        UserProfileDto profile = userService.getProfileForViewer("target-1", "staff-1", "class-1");

        assertEquals("Target User", profile.getFullName());
    }

    @Test
    @DisplayName("R16-07: the override never reaches a user with no roster row at all (an outsider stays hidden)")
    void classAdminStillCannotSeeOutsiderIdentity() {
        targetUser.setProfileVisibility("PRIVATE");
        when(accessPolicy.hasMembershipRecord("target-1", "class-1")).thenReturn(false);
        when(accessPolicy.isOwner("owner-1", "class-1")).thenReturn(true);

        ProfileVisibilityPolicy policy = new ProfileVisibilityPolicy(accessPolicy);

        assertFalse(policy.isIdentityVisible(targetUser, "owner-1", "class-1"));
    }

    @Test
    @DisplayName("R16-07: ordinary peers are unchanged - a PRIVATE removed member stays hidden (and unreachable) for them")
    void peersStillCannotSeePrivateNonActiveMember() {
        targetUser.setProfileVisibility("PRIVATE");
        when(accessPolicy.isOwner("peer-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("peer-1", "class-1", "MEMBER", "VIEW", null)).thenReturn(false);

        ProfileVisibilityPolicy policy = new ProfileVisibilityPolicy(accessPolicy);

        assertFalse(policy.isIdentityVisible(targetUser, "peer-1", "class-1"));
        verify(accessPolicy, never()).hasMembershipRecord(anyString(), anyString());
    }}
