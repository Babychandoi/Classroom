package com.classroom.modules.classroom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public class ClassroomDto {
    public static final String MEMBER_STATE_ACTIVE = "ACTIVE";
    public static final String MEMBER_STATE_REMOVED = "REMOVED";
    public static final String MEMBER_STATE_BLOCKED = "BLOCKED";
    public static final String MEMBER_STATE_NONE = "NONE";
    /** D-19: a member of a PAID class whose paid access has lapsed - may read About / Store / the paywall only, everything else 403 MEMBERSHIP_EXPIRED. */
    public static final String MEMBER_STATE_EXPIRED = "EXPIRED";
    /** D-28: the caller asked to join a class with requireApproval and waits for a Studio decision - NOT a member. */
    public static final String MEMBER_STATE_PENDING = "PENDING";

    private String id;
    private String ownerId;
    private String ownerName;
    private String slug;
    private String title;
    private String description;
    private String coverImageUrl;
    /** D-27: short-lived presigned GET of the uploaded cover (media purpose CLASS_COVER), or null. UI: coverUrl ?? coverImageUrl ?? tile. */
    private String coverUrl;
    /** D-27: the uploaded cover's media id (Studio settings), or null. */
    private String coverMediaId;
    /** D-27: the owner's avatar, shown next to {@link #ownerName} (same exposure as the name). */
    private String ownerAvatarUrl;
    /** D-27: SCHEDULED events of the class that have not ended yet. */
    private long upcomingEventCount;
    /** D-28: one of ClassCategories.ALL, or null. */
    private String category;
    /** D-28: the uploaded square avatar (media purpose CLASS_AVATAR) and its short-lived presigned GET, or null. */
    private String avatarMediaId;
    private String avatarUrl;
    /** D-28: CSS object-position ("50% 30%") of the cover / avatar, null = centre. */
    private String coverPosition;
    private String avatarPosition;
    /** D-28: joins by id wait for approval (memberState PENDING). */
    private boolean requireApproval;
    /** D-28: PENDING join requests - only for callers with MEMBER:VIEW (owner included), 0 for everybody else. */
    private long pendingRequestCount;
    private String status;
    private long memberCount;
    private boolean isOwner;
    private boolean isMember;
    private boolean isPro;
    private String userRole; // OWNER, STAFF, STUDENT, GUEST
    /**
     * R16-01: the caller's membership lifecycle in this class - {@link #MEMBER_STATE_ACTIVE},
     * {@link #MEMBER_STATE_REMOVED} (may rejoin on their own), {@link #MEMBER_STATE_BLOCKED} (only a
     * Studio unblock restores access), {@link #MEMBER_STATE_EXPIRED} (D-19: paid access lapsed - renew to come back) or
     * {@link #MEMBER_STATE_NONE} (never joined / anonymous). Only ACTIVE (or the owner) makes {@code isMember} true, so a
     * removed/blocked/expired person is no longer presented as a member with the STUDENT role.
     */
    private String memberState = MEMBER_STATE_NONE;
    /** D-19: PUBLIC (listed, joinable) or PRIVATE (only visible to owner / staff / members; joined by invite). */
    private String visibility = "PUBLIC";
    /** D-19: FREE or PAID. A PAID class is joined by buying {@link #accessProduct}. */
    private String accessType = "FREE";
    /**
     * D-19: when the CALLER's paid access ends ({@code null} = no expiry: free class, owner/staff, grandfathered or lifetime member, or not a
     * member). For {@link #MEMBER_STATE_EXPIRED} it is the moment the access lapsed.
     */
    private Instant accessExpiresAt;
    /** D-19: what a PAID class sells (price, currency, duration); {@code null} for a FREE class. */
    private ClassAccessProductDto accessProduct;
    private Instant createdAt;
    private List<String> studioPermissions = List.of();
    private List<StudioScopedPermission> studioScopedPermissions = List.of();

    public ClassroomDto() {}

    /**
     * R6-01: a course-scoped staff grant, kept separate from {@link #studioPermissions} (which is
     * class-wide only, see {@link com.classroom.modules.classroom.service.ClassroomService}). The
     * frontend uses this to authorize per-course actions (e.g. edit/publish course X) without
     * mistaking a scoped grant for a class-wide one.
     */
    public static class StudioScopedPermission {
        private String module;
        private String action;
        private String courseId;

        public StudioScopedPermission() {}

        public StudioScopedPermission(String module, String action, String courseId) {
            this.module = module;
            this.action = action;
            this.courseId = courseId;
        }

        public String getModule() {
            return module;
        }

        public void setModule(String module) {
            this.module = module;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getCourseId() {
            return courseId;
        }

        public void setCourseId(String courseId) {
            this.courseId = courseId;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCoverImageUrl() {
        return coverImageUrl;
    }

    public void setCoverImageUrl(String coverImageUrl) {
        this.coverImageUrl = coverImageUrl;
    }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getAvatarMediaId() { return avatarMediaId; }
    public void setAvatarMediaId(String avatarMediaId) { this.avatarMediaId = avatarMediaId; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public String getCoverPosition() { return coverPosition; }
    public void setCoverPosition(String coverPosition) { this.coverPosition = coverPosition; }
    public String getAvatarPosition() { return avatarPosition; }
    public void setAvatarPosition(String avatarPosition) { this.avatarPosition = avatarPosition; }
    public boolean isRequireApproval() { return requireApproval; }
    public void setRequireApproval(boolean requireApproval) { this.requireApproval = requireApproval; }
    public long getPendingRequestCount() { return pendingRequestCount; }
    public void setPendingRequestCount(long pendingRequestCount) { this.pendingRequestCount = pendingRequestCount; }

    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public String getCoverMediaId() { return coverMediaId; }
    public void setCoverMediaId(String coverMediaId) { this.coverMediaId = coverMediaId; }
    public String getOwnerAvatarUrl() { return ownerAvatarUrl; }
    public void setOwnerAvatarUrl(String ownerAvatarUrl) { this.ownerAvatarUrl = ownerAvatarUrl; }
    public long getUpcomingEventCount() { return upcomingEventCount; }
    public void setUpcomingEventCount(long upcomingEventCount) { this.upcomingEventCount = upcomingEventCount; }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(long memberCount) {
        this.memberCount = memberCount;
    }

    @JsonProperty("isOwner")
    public boolean isOwner() {
        return isOwner;
    }

    @JsonProperty("isOwner")
    public void setOwner(boolean owner) {
        isOwner = owner;
    }

    @JsonProperty("isMember")
    public boolean isMember() {
        return isMember;
    }

    @JsonProperty("isMember")
    public void setMember(boolean member) {
        isMember = member;
    }

    @JsonProperty("isPro")
    public boolean isPro() {
        return isPro;
    }

    @JsonProperty("isPro")
    public void setPro(boolean pro) {
        isPro = pro;
    }

    public String getUserRole() {
        return userRole;
    }

    public void setUserRole(String userRole) {
        this.userRole = userRole;
    }

    public String getMemberState() {
        return memberState;
    }

    public void setMemberState(String memberState) {
        this.memberState = memberState;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public String getAccessType() {
        return accessType;
    }

    public void setAccessType(String accessType) {
        this.accessType = accessType;
    }

    public Instant getAccessExpiresAt() {
        return accessExpiresAt;
    }

    public void setAccessExpiresAt(Instant accessExpiresAt) {
        this.accessExpiresAt = accessExpiresAt;
    }

    public ClassAccessProductDto getAccessProduct() {
        return accessProduct;
    }

    public void setAccessProduct(ClassAccessProductDto accessProduct) {
        this.accessProduct = accessProduct;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<String> getStudioPermissions() { return studioPermissions; }
    public void setStudioPermissions(List<String> studioPermissions) { this.studioPermissions = studioPermissions; }

    public List<StudioScopedPermission> getStudioScopedPermissions() { return studioScopedPermissions; }
    public void setStudioScopedPermissions(List<StudioScopedPermission> studioScopedPermissions) { this.studioScopedPermissions = studioScopedPermissions; }
}
