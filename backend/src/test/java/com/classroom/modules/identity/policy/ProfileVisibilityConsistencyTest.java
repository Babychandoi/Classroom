package com.classroom.modules.identity.policy;

import com.classroom.modules.classroom.dto.ClassMemberDto;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassAboutRepository;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.classroom.repository.StaffPermissionRepository;
import com.classroom.modules.classroom.service.ClassroomService;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.exam.repository.ExamRepository;
import com.classroom.modules.identity.dto.UserProfileDto;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.identity.service.UserService;
import com.classroom.modules.outbox.service.OutboxService;
import com.classroom.modules.ranking.dto.LeaderboardEntryDto;
import com.classroom.modules.ranking.model.LeaderboardEntry;
import com.classroom.modules.ranking.repository.ExamRewardRuleRepository;
import com.classroom.modules.ranking.repository.LeaderboardEntryRepository;
import com.classroom.modules.ranking.repository.LeaderboardRecalcJobRepository;
import com.classroom.modules.ranking.repository.RankTierRepository;
import com.classroom.modules.ranking.service.LeaderboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

/**
 * Finding 1: the profile endpoint, the class member listing and the leaderboard expose the same
 * identity fields, so they are asserted together — a listing must never reveal what the profile
 * endpoint withholds.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ProfileVisibilityConsistencyTest {

    private static final String CLASS_ID = "class-1";
    private static final String VIEWER_ID = "peer-viewer";
    private static final String PRIVATE_USER_ID = "private-1";
    private static final String PUBLIC_USER_ID = "public-1";

    @Mock private UserRepository userRepository;
    @Mock private ClassMemberRepository classMemberRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private ProPolicy proPolicy;
    @Mock private LeaderboardEntryRepository leaderboardEntryRepository;
    @Mock private ClassroomRepository classroomRepository;
    @Mock private ClassAboutRepository aboutRepository;
    @Mock private StaffAssignmentRepository staffAssignmentRepository;
    @Mock private StaffPermissionRepository staffPermissionRepository;
    @Mock private OutboxService outboxService;
    @Mock private LeaderboardRecalcJobRepository recalcJobRepository;
    @Mock private RankTierRepository rankTierRepository;
    @Mock private ExamRewardRuleRepository rewardRuleRepository;
    @Mock private ExamAttemptRepository attemptRepository;
    @Mock private ExamRepository examRepository;

    private ProfileVisibilityPolicy visibilityPolicy;
    private UserService userService;
    private ClassroomService classroomService;
    private LeaderboardService leaderboardService;

    private User privateUser;
    private User publicUser;

    @BeforeEach
    void setUp() {
        visibilityPolicy = new ProfileVisibilityPolicy(accessPolicy);
        userService = new UserService(userRepository, classMemberRepository, accessPolicy, proPolicy,
                leaderboardEntryRepository, visibilityPolicy);
        classroomService = new ClassroomService(classroomRepository, classMemberRepository, aboutRepository,
                staffAssignmentRepository, staffPermissionRepository, userRepository, outboxService,
                accessPolicy, visibilityPolicy, proPolicy);
        leaderboardService = new LeaderboardService(leaderboardEntryRepository, recalcJobRepository,
                rankTierRepository, rewardRuleRepository, attemptRepository, examRepository, userRepository,
                accessPolicy, visibilityPolicy, null);

        privateUser = new User(PRIVATE_USER_ID, "private@classroom.local", "hashed", "Private Learner", "STUDENT");
        privateUser.setAvatarUrl("https://example.com/private.png");
        privateUser.setProfileVisibility("PRIVATE");

        publicUser = new User(PUBLIC_USER_ID, "public@classroom.local", "hashed", "Public Learner", "STUDENT");
        publicUser.setAvatarUrl("https://example.com/public.png");
        publicUser.setProfileVisibility("PUBLIC");

        when(accessPolicy.isMember(VIEWER_ID, CLASS_ID)).thenReturn(true);
        when(accessPolicy.isOwner(VIEWER_ID, CLASS_ID)).thenReturn(false);
        when(accessPolicy.isOwner(PRIVATE_USER_ID, CLASS_ID)).thenReturn(false);
        when(userRepository.findById(PRIVATE_USER_ID)).thenReturn(Optional.of(privateUser));
        when(userRepository.findById(PUBLIC_USER_ID)).thenReturn(Optional.of(publicUser));
        when(classMemberRepository.findByClassIdAndUserId(CLASS_ID, PRIVATE_USER_ID))
                .thenReturn(Optional.of(new ClassMember(CLASS_ID, PRIVATE_USER_ID, "STUDENT")));
    }

    @Test
    @DisplayName("A private learner is hidden on the profile endpoint, the member listing and the leaderboard alike")
    void privateLearnerIsHiddenEverywhere() {
        UserProfileDto profile = userService.getProfileForViewer(PRIVATE_USER_ID, VIEWER_ID, CLASS_ID);
        assertNull(profile.getFullName());
        assertNull(profile.getAvatarUrl());

        when(classMemberRepository.findByClassId(CLASS_ID))
                .thenReturn(List.of(new ClassMember(CLASS_ID, PRIVATE_USER_ID, "STUDENT")));
        List<ClassMemberDto> members = classroomService.getClassMembers(CLASS_ID, VIEWER_ID);
        assertEquals(1, members.size());
        assertEquals(ProfileVisibilityPolicy.ANONYMOUS_DISPLAY_NAME, members.get(0).getUserFullName());
        assertNull(members.get(0).getUserAvatarUrl());
        assertNull(members.get(0).getUserId());

        when(leaderboardEntryRepository.findByClassIdOrderByTotalPointsDesc(CLASS_ID))
                .thenReturn(List.of(new LeaderboardEntry(CLASS_ID, PRIVATE_USER_ID, 150, "SILVER")));
        List<LeaderboardEntryDto> board = leaderboardService.getLeaderboard(CLASS_ID, VIEWER_ID);
        assertEquals(1, board.size());
        assertEquals(ProfileVisibilityPolicy.ANONYMOUS_DISPLAY_NAME, board.get(0).getUserFullName());
        assertNull(board.get(0).getUserAvatarUrl());
        assertNull(board.get(0).getUserId());
        String serialized = assertDoesNotThrow(() -> new ObjectMapper().findAndRegisterModules().writeValueAsString(List.of(members.get(0), board.get(0))));
        assertFalse(serialized.contains(PRIVATE_USER_ID), "Serialized listing payloads must not contain the private user's stable ID");
        // The ranking itself stays complete.
        assertEquals(1, board.get(0).getRank());
        assertEquals(150, board.get(0).getTotalPoints());
        assertEquals("SILVER", board.get(0).getCurrentTier());
    }

    @Test
    @DisplayName("A public learner is still shown in full on every listing")
    void publicLearnerRemainsVisible() {
        when(classMemberRepository.findByClassId(CLASS_ID))
                .thenReturn(List.of(new ClassMember(CLASS_ID, PUBLIC_USER_ID, "STUDENT")));
        List<ClassMemberDto> members = classroomService.getClassMembers(CLASS_ID, VIEWER_ID);
        assertEquals("Public Learner", members.get(0).getUserFullName());
        assertEquals("https://example.com/public.png", members.get(0).getUserAvatarUrl());
        assertEquals(PUBLIC_USER_ID, members.get(0).getUserId());

        when(leaderboardEntryRepository.findByClassIdOrderByTotalPointsDesc(CLASS_ID))
                .thenReturn(List.of(new LeaderboardEntry(CLASS_ID, PUBLIC_USER_ID, 90, "BRONZE")));
        List<LeaderboardEntryDto> board = leaderboardService.getLeaderboard(CLASS_ID, VIEWER_ID);
        assertEquals("Public Learner", board.get(0).getUserFullName());
        assertEquals("https://example.com/public.png", board.get(0).getUserAvatarUrl());
        assertEquals(PUBLIC_USER_ID, board.get(0).getUserId());
    }

    @Test
    @DisplayName("A learner always sees their own identity, even with a private profile")
    void selfAlwaysSeesOwnIdentity() {
        when(accessPolicy.isMember(PRIVATE_USER_ID, CLASS_ID)).thenReturn(true);
        when(classMemberRepository.findByClassId(CLASS_ID))
                .thenReturn(List.of(new ClassMember(CLASS_ID, PRIVATE_USER_ID, "STUDENT")));

        List<ClassMemberDto> members = classroomService.getClassMembers(CLASS_ID, PRIVATE_USER_ID);
        assertEquals("Private Learner", members.get(0).getUserFullName());
        assertEquals("https://example.com/private.png", members.get(0).getUserAvatarUrl());
    }
}
