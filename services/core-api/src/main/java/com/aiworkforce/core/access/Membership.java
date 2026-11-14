package com.aiworkforce.core.access;

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
 * A user's membership in one organization. Only ACTIVE memberships grant access;
 * SUSPENDED and REMOVED ones are kept for history and re-activation.
 */
@Entity
@Table(name = "memberships")
public class Membership {

    public enum Status { ACTIVE, SUSPENDED, REMOVED }

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Membership() {
    }

    Membership(UUID organizationId, UUID userId, Instant now) {
        this.id = Ids.newId();
        this.organizationId = organizationId;
        this.userId = userId;
        this.status = Status.ACTIVE;
        this.joinedAt = now;
        this.statusChangedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void changeStatus(Status next, Instant now) {
        this.status = next;
        this.statusChangedAt = now;
        this.updatedAt = now;
    }

    /** Re-admission of a previously removed member (through a new invitation). */
    void rejoin(Instant now) {
        changeStatus(Status.ACTIVE, now);
        this.joinedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Instant getStatusChangedAt() {
        return statusChangedAt;
    }
}
