package com.classroom.modules.identity.policy;

import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.identity.model.User;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Single source of truth for whether a viewer may see another user's identity
 * (display name and avatar).
 *
 * <p>The profile endpoint, the class member listing and the leaderboard all expose the
 * same fields, so they must agree: otherwise a peer can recover through a listing what the
 * profile endpoint withheld.</p>
 *
 * <p>Rankings stay complete — a private learner keeps their rank, points and tier — but their
 * identity is replaced by {@link #ANONYMOUS_DISPLAY_NAME} and a null avatar.</p>
 */
@Component
public class ProfileVisibilityPolicy {

    /** Display name substituted for a user whose identity is hidden from the viewer. */
    public static final String ANONYMOUS_DISPLAY_NAME = "Người dùng ẩn danh";

    private static final String PRIVATE = "PRIVATE";
    private static final String CLASS = "CLASS";
    private static final String PUBLIC = "PUBLIC";

    private final AccessPolicy accessPolicy;

    public ProfileVisibilityPolicy(AccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy;
    }

    /** Normalises a stored visibility value; an absent or unknown value is treated as PRIVATE. */
    public String normalize(String rawVisibility) {
        if (rawVisibility == null || rawVisibility.isBlank()) {
            return PRIVATE;
        }
        String normalized = rawVisibility.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case PUBLIC, CLASS, PRIVATE -> normalized;
            default -> PRIVATE;
        };
    }

    /**
     * Whether {@code viewerId} may see {@code targetUserId}'s name and avatar.
     *
     * @param classId the class providing the viewing context, or {@code null} outside a class
     */
    public boolean isIdentityVisible(String targetUserId, String rawVisibility, String viewerId, String classId) {
        if (viewerId != null && viewerId.equals(targetUserId)) {
            return true;
        }
        String visibility = normalize(rawVisibility);
        if (PUBLIC.equals(visibility)) {
            return true;
        }
        // The class owner (and staff granted MEMBER/VIEW) administers their own classroom, so a
        // learner's PRIVATE setting hides them from peers, never from the people responsible for
        // the class. Peer-to-peer privacy is unchanged; this override is scoped to one class.
        if (isClassAdministrator(viewerId, classId)) {
            return true;
        }
        if (!CLASS.equals(visibility)) {
            return false;
        }
        return viewerId != null && classId != null && !classId.isBlank()
                && (accessPolicy.isMember(viewerId, classId) || accessPolicy.isOwner(viewerId, classId));
    }

    /**
     * Whether {@code viewerId} administers {@code classId} — its owner, or staff holding the
     * MEMBER/VIEW permission. Shared by the profile endpoint, the member listing and the
     * leaderboard so all three agree on who may see a private learner's identity.
     */
    public boolean isClassAdministrator(String viewerId, String classId) {
        if (viewerId == null || classId == null || classId.isBlank()) {
            return false;
        }
        return accessPolicy.isOwner(viewerId, classId)
                || accessPolicy.canManage(viewerId, classId, "MEMBER", "VIEW", null);
    }

    /** Convenience overload for callers that already loaded the {@link User}. */
    public boolean isIdentityVisible(User targetUser, String viewerId, String classId) {
        return isIdentityVisible(targetUser.getId(), targetUser.getProfileVisibility(), viewerId, classId);
    }

    /** The name to expose for a target user, anonymised when their identity is not visible. */
    public String displayName(User targetUser, String viewerId, String classId) {
        return isIdentityVisible(targetUser, viewerId, classId)
                ? targetUser.getFullName()
                : ANONYMOUS_DISPLAY_NAME;
    }

    /** The avatar URL to expose for a target user, {@code null} when their identity is not visible. */
    public String avatarUrl(User targetUser, String viewerId, String classId) {
        return isIdentityVisible(targetUser, viewerId, classId) ? targetUser.getAvatarUrl() : null;
    }
}
