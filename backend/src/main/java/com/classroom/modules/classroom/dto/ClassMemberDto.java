package com.classroom.modules.classroom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public class ClassMemberDto {
    private String id;
    private String userId;
    private String role;
    private String state;
    private Instant joinedAt;
    /** D-28: when a PENDING join request was made (null for every other state). */
    private Instant requestedAt;
    /** D-19: Studio roster only - when the member's paid access ends (null = no expiry). Never on the peer-visible member listing. */
    private Instant accessExpiresAt;
    private String userFullName;
    private String userAvatarUrl;
    /** R13-02: Studio member management listing — email is only ever populated for a caller who
     * already passes MEMBER:VIEW (see MemberService), never on the public getClassMembers listing. */
    private String userEmail;
    /** R13-02: FREE/PRO tier, derived from ProPolicy (paid entitlement or OWNER), never a client-set flag. */
    private boolean isPro;

    public ClassMemberDto() {
    }

    public ClassMemberDto(String id, String userId, String role, String state, Instant joinedAt, String userFullName, String userAvatarUrl) {
        this.id = id;
        this.userId = userId;
        this.role = role;
        this.state = state;
        this.joinedAt = joinedAt;
        this.userFullName = userFullName;
        this.userAvatarUrl = userAvatarUrl;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }

    public Instant getAccessExpiresAt() {
        return accessExpiresAt;
    }

    public void setAccessExpiresAt(Instant accessExpiresAt) {
        this.accessExpiresAt = accessExpiresAt;
    }

    public String getUserFullName() {
        return userFullName;
    }

    public void setUserFullName(String userFullName) {
        this.userFullName = userFullName;
    }

    public String getUserAvatarUrl() {
        return userAvatarUrl;
    }

    public void setUserAvatarUrl(String userAvatarUrl) {
        this.userAvatarUrl = userAvatarUrl;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    /**
     * R13-02: matches ClassroomDto's isPro()/setPro() @JsonProperty("isPro") convention — without
     * it, Jackson's default bean-naming for an "is"-prefixed boolean getter serializes this field
     * as "pro" instead of "isPro", inconsistent with the rest of this API's boolean flags.
     */
    @JsonProperty("isPro")
    public boolean isPro() {
        return isPro;
    }

    @JsonProperty("isPro")
    public void setPro(boolean pro) {
        isPro = pro;
    }
}
