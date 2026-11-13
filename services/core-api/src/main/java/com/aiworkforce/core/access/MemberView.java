package com.aiworkforce.core.access;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read model of a membership with the member's public profile and roles. */
public record MemberView(
        UUID membershipId,
        UUID organizationId,
        UUID userId,
        String email,
        String displayName,
        Membership.Status status,
        Instant joinedAt,
        Instant statusChangedAt,
        List<RoleRef> roles) {

    /** Short reference to a role. */
    public record RoleRef(UUID id, BuiltInRole code, String name) {
    }
}
