package com.classroom.modules.identity.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.identity.dto.UserProfileDto;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.ranking.repository.LeaderboardEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final ClassMemberRepository classMemberRepository;
    private final AccessPolicy accessPolicy;
    private final ProPolicy proPolicy;
    private final LeaderboardEntryRepository leaderboardEntryRepository;
    private final ProfileVisibilityPolicy profileVisibilityPolicy;

    public UserService(UserRepository userRepository,
                       ClassMemberRepository classMemberRepository,
                       AccessPolicy accessPolicy,
                       ProPolicy proPolicy,
                       LeaderboardEntryRepository leaderboardEntryRepository,
                       ProfileVisibilityPolicy profileVisibilityPolicy) {
        this.userRepository = userRepository;
        this.classMemberRepository = classMemberRepository;
        this.accessPolicy = accessPolicy;
        this.proPolicy = proPolicy;
        this.leaderboardEntryRepository = leaderboardEntryRepository;
        this.profileVisibilityPolicy = profileVisibilityPolicy;
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
                throw new AppException(ErrorCode.FORBIDDEN, "Bạn không thuộc lớp học này để xem hồ sơ thành viên");
            }

            Optional<ClassMember> targetMemberOpt = classMemberRepository.findByClassIdAndUserId(classId, targetUserId);
            boolean targetIsOwner = accessPolicy.isOwner(targetUserId, classId);
            if (targetMemberOpt.isEmpty() && !targetIsOwner) {
                throw new AppException(ErrorCode.NOT_FOUND, "Người dùng không thuộc lớp học này");
            }

            boolean isOwner = viewerId != null && accessPolicy.isOwner(viewerId, classId);
            boolean isStaffWithMemberView = viewerId != null && accessPolicy.canManage(viewerId, classId, "MEMBER", "VIEW", null);

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
            user.setAvatarUrl(avatarUrl.trim());
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
}
