package com.classroom.modules.identity.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.exam.model.Exam;
import com.classroom.modules.exam.model.ExamAttempt;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.exam.repository.ExamRepository;
import com.classroom.modules.identity.dto.UserJourneyDto;
import com.classroom.modules.identity.dto.UserProfileDto;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.policy.LearningPolicy;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.repository.LessonProgressRepository;
import com.classroom.modules.learning.repository.LessonRepository;
import com.classroom.modules.ranking.repository.LeaderboardEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final ClassMemberRepository classMemberRepository;
    private final AccessPolicy accessPolicy;
    private final ProPolicy proPolicy;
    private final LeaderboardEntryRepository leaderboardEntryRepository;
    private final ProfileVisibilityPolicy profileVisibilityPolicy;
    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;
    private final LessonProgressRepository lessonProgressRepository;
    private final ExamAttemptRepository examAttemptRepository;
    private final ExamRepository examRepository;
    private final LearningPolicy learningPolicy;

    public UserService(UserRepository userRepository,
                       ClassMemberRepository classMemberRepository,
                       AccessPolicy accessPolicy,
                       ProPolicy proPolicy,
                       LeaderboardEntryRepository leaderboardEntryRepository,
                       ProfileVisibilityPolicy profileVisibilityPolicy,
                       CourseRepository courseRepository,
                       LessonRepository lessonRepository,
                       LessonProgressRepository lessonProgressRepository,
                       ExamAttemptRepository examAttemptRepository,
                       ExamRepository examRepository,
                       LearningPolicy learningPolicy) {
        this.userRepository = userRepository;
        this.classMemberRepository = classMemberRepository;
        this.accessPolicy = accessPolicy;
        this.proPolicy = proPolicy;
        this.leaderboardEntryRepository = leaderboardEntryRepository;
        this.profileVisibilityPolicy = profileVisibilityPolicy;
        this.courseRepository = courseRepository;
        this.lessonRepository = lessonRepository;
        this.lessonProgressRepository = lessonProgressRepository;
        this.examAttemptRepository = examAttemptRepository;
        this.examRepository = examRepository;
        this.learningPolicy = learningPolicy;
    }

    @Transactional(readOnly = true)
    public User findById(String id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng"));
    }

    /**
     * Returns full profile for the user themselves or administrative contexts.
     */
    @Transactional(readOnly = true)
    public UserProfileDto getProfile(String id) {
        User user = findById(id);
        UserProfileDto dto = new UserProfileDto();
        dto.setId(user.getId());
        dto.setEmail(user.getEmail());
        dto.setFullName(user.getFullName());
        dto.setAvatarUrl(user.getAvatarUrl());
        dto.setBio(user.getBio());
        dto.setProfileVisibility(user.getProfileVisibility());
        dto.setRole(user.getRole());
        dto.setStatus(user.getStatus());
        dto.setCreatedAt(user.getCreatedAt());
        return dto;
    }

    /**
     * D-19: why a viewer who is not in the class cannot see a member-only page - the class does not exist or is a PRIVATE one hidden from them
     * (404, nothing revealed), their paid access lapsed (403 MEMBERSHIP_EXPIRED), or they simply are not a member (the usual 403).
     */
    private AppException notAMember(String viewerId, String classId, String message) {
        if (accessPolicy.isMissingOrHiddenPrivateClass(classId, viewerId)) {
            return new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học");
        }
        if (accessPolicy.isMembershipExpired(viewerId, classId)) {
            return new AppException(ErrorCode.MEMBERSHIP_EXPIRED);
        }
        return new AppException(ErrorCode.FORBIDDEN, message);
    }

    /**
     * Privacy-aware profile retrieval (Finding 1).
     * Omits private account fields (email, role, status, createdAt) for peer viewers
     * and enforces class-scoped membership and permissions when classId is specified.
     */
    @Transactional(readOnly = true)
    public UserProfileDto getProfileForViewer(String targetUserId, String viewerId, String classId) {
        User targetUser = findById(targetUserId);

        boolean isSelf = (viewerId != null && viewerId.equals(targetUserId));
        String visibility = profileVisibilityPolicy.normalize(targetUser.getProfileVisibility());

        // Finding 1: one rule, shared with the class member listing and the leaderboard, so a
        // listing can never expose what this endpoint withholds.
        boolean visibleToViewer = profileVisibilityPolicy.isIdentityVisible(targetUser, viewerId, classId);

        UserProfileDto dto = new UserProfileDto();
        dto.setId(targetUser.getId());
        if (visibleToViewer) {
            dto.setFullName(targetUser.getFullName());
            dto.setAvatarUrl(targetUser.getAvatarUrl());
            dto.setBio(targetUser.getBio());
        }
        if (isSelf) dto.setProfileVisibility(visibility);

        if (isSelf) {
            dto.setEmail(targetUser.getEmail());
            dto.setRole(targetUser.getRole());
            dto.setStatus(targetUser.getStatus());
            dto.setCreatedAt(targetUser.getCreatedAt());
        }

        if (classId != null && !classId.isBlank()) {
            if (viewerId != null && !accessPolicy.isMember(viewerId, classId) && !accessPolicy.isOwner(viewerId, classId)) {
                throw notAMember(viewerId, classId, "Bạn không thuộc lớp học này để xem hồ sơ thành viên");
            }

            Optional<ClassMember> targetMemberOpt = classMemberRepository.findByClassIdAndUserId(classId, targetUserId);
            boolean targetIsOwner = accessPolicy.isOwner(targetUserId, classId);
            if (targetMemberOpt.isEmpty() && !targetIsOwner) {
                throw new AppException(ErrorCode.NOT_FOUND, "Người dùng không thuộc lớp học này");
            }

            boolean isOwner = viewerId != null && accessPolicy.isOwner(viewerId, classId);
            boolean isStaffWithMemberView = viewerId != null && accessPolicy.canManage(viewerId, classId, "MEMBER", "VIEW", null);

            // R15-04: a REMOVED/BLOCKED member no longer belongs to the class. Only class
            // administrators (OWNER / STAFF with MEMBER:VIEW) may still open that profile; for
            // everyone else it is a plain 404, indistinguishable from a user who never joined, so the
            // moderation state is not disclosed. (The journey endpoint applies the same rule, R14-11.)
            java.time.Instant activeNow = java.time.Instant.now();
            boolean targetActive = targetIsOwner
                    || targetMemberOpt.map(m -> m.isActiveAt(activeNow)).orElse(false);
            if (!targetActive && !isOwner && !isStaffWithMemberView) {
                throw new AppException(ErrorCode.NOT_FOUND, "Người dùng không thuộc lớp học này");
            }

            String role = targetIsOwner ? "OWNER" : targetMemberOpt.map(ClassMember::getRole).orElse("STUDENT");
            if (visibleToViewer) {
                dto.setMembershipRole(role);
                dto.setPro(proPolicy.isPro(targetUserId, classId));

                leaderboardEntryRepository.findByClassIdAndUserId(classId, targetUserId).ifPresent(entry -> {
                    dto.setTotalPoints(entry.getTotalPoints());
                    dto.setRankTier(entry.getCurrentTier());
                });
            }

            if (isOwner || isStaffWithMemberView || isSelf) {
                dto.setEmail(targetUser.getEmail());
            } else {
                dto.setEmail(null);
                dto.setRole(null);
                dto.setStatus(null);
                dto.setCreatedAt(null);
            }
        } else {
            if (!isSelf) {
                dto.setEmail(null);
                dto.setRole(null);
                dto.setStatus(null);
                dto.setCreatedAt(null);
            }
        }

        if (!isSelf && !visibleToViewer) {
            dto.setEmail(null);
            dto.setRole(null);
            dto.setStatus(null);
            dto.setCreatedAt(null);
        }

        return dto;
    }

    @Transactional
    public UserProfileDto updateProfile(String id, String fullName, String avatarUrl, String bio, String profileVisibility) {
        User user = findById(id);
        if (fullName != null && !fullName.isBlank()) {
            user.setFullName(fullName.trim());
        }
        if (avatarUrl != null) {
            user.setAvatarUrl(validateAvatarUrl(avatarUrl.trim()));
        }
        if (bio != null) {
            user.setBio(bio.trim());
        }
        if (profileVisibility != null) {
            String normalized = profileVisibility.trim().toUpperCase(java.util.Locale.ROOT);
            if (!java.util.Set.of("PRIVATE", "CLASS", "PUBLIC").contains(normalized)) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Chế độ hiển thị hồ sơ không hợp lệ");
            }
            user.setProfileVisibility(normalized);
        }
        userRepository.save(user);
        return getProfile(id);
    }

    /**
     * R13-05 (FR-12/D-05): a member's learning/exam journey in one class — per-course completed/
     * total lessons and published exam results (title, score, submission date).
     *
     * <p>Visibility follows the same rule as the profile endpoint ({@link ProfileVisibilityPolicy}):
     * the member themselves, a class administrator (OWNER or STAFF holding MEMBER:VIEW), or a peer
     * the target has opted into (PUBLIC always, CLASS for fellow members) may see it — a PRIVATE
     * learner's activity is hidden from ordinary peers exactly like their identity is, so a listing
     * can never recover through this endpoint what the profile endpoint withholds. Batch-loads
     * course lesson/progress counts and exam titles instead of querying per row (no N+1).</p>
     */
    @Transactional(readOnly = true)
    public UserJourneyDto getJourney(String targetUserId, String viewerId, String classId) {
        if (classId == null || classId.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thiếu classId để xem hành trình học tập");
        }
        User targetUser = findById(targetUserId);

        if (viewerId != null && !accessPolicy.isMember(viewerId, classId) && !accessPolicy.isOwner(viewerId, classId)) {
            throw notAMember(viewerId, classId, "Bạn không thuộc lớp học này để xem hành trình học tập");
        }
        // R14-11: only an ACTIVE member (or the owner) has a journey in the class; a REMOVED/BLOCKED
        // member no longer belongs to it, so their activity must not stay retrievable by peers.
        java.time.Instant journeyNow = java.time.Instant.now();
        boolean targetIsMember = classMemberRepository.findByClassIdAndUserId(classId, targetUserId)
                .filter(m -> m.isActiveAt(journeyNow)).isPresent()
                || accessPolicy.isOwner(targetUserId, classId);
        if (!targetIsMember) {
            throw new AppException(ErrorCode.NOT_FOUND, "Người dùng không thuộc lớp học này");
        }
        if (!profileVisibilityPolicy.isIdentityVisible(targetUser, viewerId, classId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Hành trình học tập của thành viên này không được chia sẻ công khai");
        }

        UserJourneyDto dto = new UserJourneyDto();
        dto.setUserId(targetUserId);
        dto.setClassId(classId);

        // Per-course progress: only courses the target can actually learn (mirrors LearnTab/
        // CourseController's own learner-visible listing) are meaningful for a "journey".
        //
        // R15-01: a course is listed only when BOTH the target's rights (they can view + learn it,
        // so there is progress to report) AND the viewer's own rights (the viewer may see that
        // course at all) allow it. Checking the target alone leaked the titles/lesson counts of an
        // OWNER/staff target's DRAFT (or paid archived) courses to ordinary peers, because the
        // owner can always "learn" everything. canViewCourse(viewer) hides DRAFT courses from any
        // viewer without COURSE:PREVIEW/EDIT (or ownership) and archived paid courses from viewers
        // without an entitlement; a viewer without an identity sees no courses.
        boolean viewingSelf = viewerId != null && viewerId.equals(targetUserId);
        List<Course> courses = courseRepository.findByClassIdOrderByPositionAsc(classId);
        List<Course> visibleCourses = new ArrayList<>();
        for (Course course : courses) {
            boolean targetCanSee = learningPolicy.canViewCourse(targetUserId, course)
                    && learningPolicy.canLearn(targetUserId, course);
            if (targetCanSee && (viewingSelf || learningPolicy.canViewCourse(viewerId, course))) {
                visibleCourses.add(course);
            }
        }
        List<String> courseIds = visibleCourses.stream().map(Course::getId).toList();
        Map<String, Long> totalByCourse = new HashMap<>();
        Map<String, Long> completedByCourse = new HashMap<>();
        if (!courseIds.isEmpty()) {
            // R14-03: learner-visible lessons only (non-archived lesson in a non-archived section) on
            // both sides, so the journey percentage agrees with the Learn tab and can reach 100%.
            lessonRepository.countVisibleByCourseIdIn(courseIds).forEach(row -> totalByCourse.put(row.getCourseId(), row.getTotal()));
            lessonProgressRepository.countCompletedVisibleByUserAndCourseIdIn(targetUserId, courseIds)
                    .forEach(row -> completedByCourse.put(row.getCourseId(), row.getTotal()));
        }
        List<UserJourneyDto.CourseProgress> courseProgress = new ArrayList<>();
        for (Course course : visibleCourses) {
            long total = totalByCourse.getOrDefault(course.getId(), 0L);
            long completed = Math.min(completedByCourse.getOrDefault(course.getId(), 0L), total);
            courseProgress.add(new UserJourneyDto.CourseProgress(course.getId(), course.getTitle(), (int) completed, (int) total));
        }
        dto.setCourses(courseProgress);

        // Published exam results: best (already-recalculated-into-leaderboard) rule doesn't apply
        // here - this lists every published attempt so the viewer can see the journey, not just the
        // best one used for ranking.
        List<ExamAttempt> published = examAttemptRepository.findByClassIdAndUserIdAndStatusAndIsPreviewFalse(
                classId, targetUserId, "PUBLISHED");
        List<String> examIds = published.stream().map(ExamAttempt::getExamId).distinct().toList();
        Map<String, String> examTitleById = new HashMap<>();
        if (!examIds.isEmpty()) {
            for (Exam exam : examRepository.findAllById(examIds)) {
                examTitleById.put(exam.getId(), exam.getTitle());
            }
        }
        List<UserJourneyDto.ExamResult> examResults = published.stream()
                .sorted(Comparator.comparing(ExamAttempt::getSubmittedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(attempt -> new UserJourneyDto.ExamResult(
                        attempt.getExamId(),
                        examTitleById.getOrDefault(attempt.getExamId(), attempt.getExamId()),
                        attempt.getScore(),
                        attempt.getSubmittedAt()))
                .toList();
        dto.setExamResults(examResults);

        return dto;
    }

    /**
     * R9-07: an avatar URL is rendered as an {@code <img src>} across the app (member profile,
     * navbar, etc.), so an unrestricted value here is an open door to non-https schemes
     * (javascript:, data: with attacker-controlled markup in some contexts, arbitrary internal
     * http:// URLs for SSRF-via-browser-render) and to unbounded strings ending up in storage.
     * Only https URLs are accepted (plain http would also downgrade the page's own HTTPS to a
     * mixed-content warning at best); an empty/blank value is allowed through as a way to clear the
     * avatar back to none.
     */
    private static final int MAX_AVATAR_URL_LENGTH = 2048;

    private String validateAvatarUrl(String avatarUrl) {
        if (avatarUrl.isEmpty()) {
            return null;
        }
        if (avatarUrl.length() > MAX_AVATAR_URL_LENGTH) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Đường dẫn ảnh đại diện quá dài (tối đa " + MAX_AVATAR_URL_LENGTH + " ký tự)");
        }
        java.net.URI uri;
        try {
            uri = new java.net.URI(avatarUrl);
        } catch (java.net.URISyntaxException e) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Đường dẫn ảnh đại diện không hợp lệ");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Đường dẫn ảnh đại diện phải là địa chỉ https hợp lệ");
        }
        return avatarUrl;
    }
}
