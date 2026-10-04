package com.classroom.modules.classroom.dto;

import com.classroom.modules.identity.model.User;

/**
 * D-27: a person as shown on a blog byline, as an event host or in an event's registrant list (managers only). Bylines and hosts are
 * editorial roles (owner / staff acting for the class, like {@code ownerName} on the class card) and a registrant shares their identity with
 * the organisers by registering, so these are not run through the peer-privacy rule of member listings - see docs/DECISIONS.md D-27.
 */
public record PersonSummaryDto(String id, String fullName, String avatarUrl) {

    /** Label used when the user row no longer exists. */
    public static final String UNKNOWN_NAME = "Người dùng";

    public static PersonSummaryDto of(User user, String userId) {
        if (user == null) {
            return new PersonSummaryDto(userId, UNKNOWN_NAME, null);
        }
        return new PersonSummaryDto(user.getId(), user.getFullName(), user.getAvatarUrl());
    }
}
