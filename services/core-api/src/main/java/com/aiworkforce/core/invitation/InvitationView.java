package com.aiworkforce.core.invitation;

import java.time.Instant;
import java.util.UUID;

/** Read model of an invitation. It never contains the token or its hash. */
public record InvitationView(UUID id, UUID organizationId, String email, UUID roleId,
        OrganizationInvitation.Status status, Instant expiresAt, UUID invitedBy, Instant createdAt) {

    static InvitationView of(OrganizationInvitation i) {
        return new InvitationView(i.getId(), i.getOrganizationId(), i.getEmail(), i.getRoleId(), i.getStatus(),
                i.getExpiresAt(), i.getInvitedBy(), i.getCreatedAt());
    }
}
