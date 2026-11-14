package com.aiworkforce.core.access;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Role assigned to a membership. Composite foreign keys keep both in the same organization. */
@Entity
@Table(name = "membership_roles")
public class MembershipRole {

    @Embeddable
    public record Key(@Column(name = "membership_id") UUID membershipId, @Column(name = "role_id") UUID roleId)
            implements Serializable {
    }

    @EmbeddedId
    private Key id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by", updatable = false)
    private UUID assignedBy;

    protected MembershipRole() {
    }

    MembershipRole(Membership membership, Role role, UUID assignedBy, Instant now) {
        if (!membership.getOrganizationId().equals(role.getOrganizationId())) {
            throw new IllegalArgumentException("Role and membership belong to different organizations");
        }
        this.id = new Key(membership.getId(), role.getId());
        this.organizationId = membership.getOrganizationId();
        this.assignedAt = now;
        this.assignedBy = assignedBy;
    }

    public UUID getMembershipId() {
        return id.membershipId();
    }

    public UUID getRoleId() {
        return id.roleId();
    }
}
