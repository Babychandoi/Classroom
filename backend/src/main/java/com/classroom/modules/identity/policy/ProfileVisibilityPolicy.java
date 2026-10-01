package com.classroom.modules.identity.policy;

import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.identity.model.User;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

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
        return viewerContext(viewerId, classId).isIdentityVisible(targetUserId, rawVisibility,
                () -> classId != null && !classId.isBlank() && accessPolicy.hasMembershipRecord(targetUserId, classId));
    }

    /**
     * R16-08: a viewer-in-class context for listings. The viewer-level facts (is the viewer a class
     * administrator? is the viewer a member of the class?) cost several queries each, and they are the
     * same for every row of a listing, so they are resolved lazily <em>once</em> here instead of once
     * per row and per field (name, avatar, id) as the per-user methods below do.
     */
    public ViewerContext viewerContext(String viewerId, String classId) {
        return new ViewerContext(viewerId, classId);
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

    /** What a listing may show of one person to one viewer; {@code userId} is null when the identity is hidden. */
    public record Identity(boolean visible, String userId, String displayName, String avatarUrl) {}

    /**
     * The identity rules for one viewer inside one class. Not thread-safe; create one per request /
     * listing via {@link #viewerContext(String, String)}.
     */
    public final class ViewerContext {
        private final String viewerId;
        private final String classId;
        private Boolean administrator;
        private Boolean classPeer;
        /** R20-03: ids whose "has a roster row" answer was resolved in one batch, and the subset that does. */
        private Set<String> prefetchedIds;
        private Set<String> prefetchedWithRecord;

        private ViewerContext(String viewerId, String classId) {
            this.viewerId = viewerId;
            this.classId = classId;
        }

        /** Whether the viewer administers the class (owner, or staff holding MEMBER:VIEW); resolved once. */
        public boolean isClassAdministrator() {
            if (administrator == null) {
                administrator = ProfileVisibilityPolicy.this.isClassAdministrator(viewerId, classId);
            }
            return administrator;
        }

        /**
         * R20-03: the caller already knows whether the viewer is a member of (or owns) the class - e.g. the feed resolved it for its
         * own visibility rules - so the context does not query it again.
         */
        public ViewerContext withKnownClassPeer(boolean knownClassPeer) {
            this.classPeer = knownClassPeer;
            return this;
        }

        /**
         * R20-03: resolves, in ONE query, which of {@code targetUserIds} have a roster row in the class. Only a class administrator's
         * view ever asks that question (the override in {@link #isIdentityVisible}), so for every other viewer this costs nothing.
         * Without it a listing rendered for an administrator issued one {@code hasMembershipRecord} query per row and field.
         */
        public ViewerContext prefetchMembershipRecords(Collection<String> targetUserIds) {
            if (targetUserIds == null || targetUserIds.isEmpty() || !isClassAdministrator()) {
                return this;
            }
            Set<String> ids = new HashSet<>();
            for (String id : targetUserIds) {
                if (id != null) ids.add(id);
            }
            prefetchedWithRecord = accessPolicy.usersWithMembershipRecord(classId, ids);
            prefetchedIds = ids;
            return this;
        }

        private boolean hasMembershipRecord(String targetUserId) {
            if (prefetchedIds != null && prefetchedIds.contains(targetUserId)) {
                return prefetchedWithRecord.contains(targetUserId);
            }
            return classId != null && !classId.isBlank() && accessPolicy.hasMembershipRecord(targetUserId, classId);
        }

        /**
         * R20-03: everything a DTO needs about one person, decided once (the per-call methods above rebuilt a context and re-ran the
         * administrator/peer queries for every name, avatar and id of every row).
         */
        public Identity identityOf(User target) {
            boolean visible = isIdentityVisible(target.getId(), target.getProfileVisibility(),
                    () -> hasMembershipRecord(target.getId()));
            return new Identity(visible, visible ? target.getId() : null,
                    displayName(target, visible), avatarUrl(target, visible));
        }

        private boolean classPeer() {
            if (classPeer == null) {
                classPeer = viewerId != null && classId != null && !classId.isBlank()
                        && (accessPolicy.isMember(viewerId, classId) || accessPolicy.isOwner(viewerId, classId));
            }
            return classPeer;
        }

        /**
         * @param targetHasMembershipRecord lazily answers whether the target has any {@code class_members}
         *                                  row (any state) in this class; a listing that already holds the
         *                                  row passes {@code () -> true}
         */
        public boolean isIdentityVisible(String targetUserId, String rawVisibility,
                                         BooleanSupplier targetHasMembershipRecord) {
            if (viewerId != null && viewerId.equals(targetUserId)) {
                return true;
            }
            String visibility = normalize(rawVisibility);
            if (PUBLIC.equals(visibility)) {
                return true;
            }
            // The class owner (and staff granted MEMBER/VIEW) administers their own classroom, so a
            // learner's PRIVATE setting hides them from peers, never from the people responsible for
            // the class. Peer-to-peer privacy is unchanged; this override is scoped to one class, and
            // only applies while the target has a membership row in it (R4-01) — an owner must not be
            // able to read an arbitrary outsider's PRIVATE profile just by holding class-admin rights
            // over some unrelated class. R16-07: the row may be in ANY state (REMOVED/BLOCKED too): the
            // administrators who removed or blocked a person still need to see who it is in the Studio
            // and member views; only ordinary peers lose sight of a person who left the class.
            if (isClassAdministrator() && targetHasMembershipRecord.getAsBoolean()) {
                return true;
            }
            if (!CLASS.equals(visibility)) {
                return false;
            }
            return classPeer();
        }

        public boolean isIdentityVisible(User target, BooleanSupplier targetHasMembershipRecord) {
            return isIdentityVisible(target.getId(), target.getProfileVisibility(), targetHasMembershipRecord);
        }

        public String displayName(User target, boolean visible) {
            return visible ? target.getFullName() : ANONYMOUS_DISPLAY_NAME;
        }

        public String avatarUrl(User target, boolean visible) {
            return visible ? target.getAvatarUrl() : null;
        }
    }
}
