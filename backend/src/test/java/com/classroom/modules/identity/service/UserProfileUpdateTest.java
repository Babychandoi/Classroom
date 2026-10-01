package com.classroom.modules.identity.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
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
import com.classroom.modules.ranking.repository.LeaderboardEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * R9-07: UserService#updateProfile's avatarUrl is rendered as an {@code <img src>} across the app,
 * so it must be restricted to https URLs (an unrestricted value would accept javascript:/data:/
 * plain-http schemes), bounded in length, and syntactically valid - while still allowing a blank
 * value through to clear an existing avatar.
 */
@ExtendWith(MockitoExtension.class)
class UserProfileUpdateTest {

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
    private User user;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, classMemberRepository, accessPolicy, proPolicy,
                leaderboardEntryRepository, new ProfileVisibilityPolicy(accessPolicy), courseRepository,
                lessonRepository, lessonProgressRepository, examAttemptRepository, examRepository, learningPolicy);
        user = new User("user-1", "user@classroom.local", "hashed", "Test User", "STUDENT");
        user.setStatus("ACTIVE");
        user.setProfileVisibility("PUBLIC");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("R9-07: a valid https avatar URL is accepted and persisted as-is")
    void acceptsValidHttpsUrl() {
        UserProfileDto profile = userService.updateProfile("user-1", null, "https://cdn.example.com/avatar.png", null, null);
        assertEquals("https://cdn.example.com/avatar.png", profile.getAvatarUrl());
        assertEquals("https://cdn.example.com/avatar.png", user.getAvatarUrl());
    }

    @Test
    @DisplayName("R9-07: a blank avatarUrl clears the existing avatar instead of being rejected")
    void blankUrlClearsAvatar() {
        user.setAvatarUrl("https://cdn.example.com/old.png");
        UserProfileDto profile = userService.updateProfile("user-1", null, "  ", null, null);
        assertNull(profile.getAvatarUrl());
        assertNull(user.getAvatarUrl());
    }

    @ParameterizedTest
    @DisplayName("R9-07: non-https schemes are rejected with a Vietnamese 400 message")
    @ValueSource(strings = {
        "http://example.com/avatar.png",
        "javascript:alert(1)",
        "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
        "ftp://example.com/avatar.png",
        "not a url at all",
    })
    void rejectsNonHttpsSchemes(String malicious) {
        AppException ex = assertThrows(AppException.class,
                () -> userService.updateProfile("user-1", null, malicious, null, null));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertNull(user.getAvatarUrl(), "the rejected value must never be persisted");
    }

    @Test
    @DisplayName("R9-07: an overlong avatar URL (over 2048 chars) is rejected")
    void rejectsOverlongUrl() {
        String overlong = "https://example.com/" + "a".repeat(2048);
        AppException ex = assertThrows(AppException.class,
                () -> userService.updateProfile("user-1", null, overlong, null, null));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }
}
