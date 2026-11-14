package com.aiworkforce.core.invitation;

import com.aiworkforce.core.common.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Invitation to join an organization with one role. Only the SHA-256 hash of the token is
 * stored; the token itself exists only in the e-mail sent to the invitee.
 * Expiry is computed from {@code expiresAt} (no background job needed).
 */
@Entity
@Table(name = "organization_invitations")
public class OrganizationInvitation {

    public enum Status { PENDING, ACCEPTED, REVOKED }

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false, updatable = false)
    private String email;

    @Column(name = "role_id", nullable = false, updatable = false)
    private UUID roleId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "invited_by", nullable = false, updatable = false)
    private UUID invitedBy;

    @Column(name = "accepted_by")
    private UUID acceptedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    private long version;

    protected OrganizationInvitation() {
    }

    OrganizationInvitation(UUID organizationId, String email, UUID roleId, String tokenHash, UUID invitedBy,
            Instant now, Instant expiresAt) {
        this.id = Ids.newId();
        this.organizationId = organizationId;
        this.email = email;
        this.roleId = roleId;
        this.tokenHash = tokenHash;
        this.status = Status.PENDING;
        this.invitedBy = invitedBy;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Pending and not expired. */
    boolean isUsable(Instant now) {
        return status == Status.PENDING && !isExpired(now);
    }

    void accept(UUID userId, Instant now) {
        this.status = Status.ACCEPTED;
        this.acceptedBy = userId;
        this.acceptedAt = now;
    }

    void revoke(Instant now) {
        this.status = Status.REVOKED;
        this.revokedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getEmail() {
        return email;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public UUID getAcceptedBy() {
        return acceptedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
