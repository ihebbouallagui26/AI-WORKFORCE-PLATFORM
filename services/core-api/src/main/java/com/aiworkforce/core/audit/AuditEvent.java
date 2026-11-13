package com.aiworkforce.core.audit;

import com.aiworkforce.core.common.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Immutable audit record. The runtime database role cannot update or delete these rows. */
@Entity
@Immutable
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    private UUID id;

    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(nullable = false, updatable = false)
    private String action;

    @Column(name = "target_type", updatable = false)
    private String targetType;

    @Column(name = "target_id", updatable = false)
    private UUID targetId;

    @Column(nullable = false, updatable = false)
    private String details;

    @Column(name = "request_id", updatable = false)
    private String requestId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AuditEvent() {
    }

    AuditEvent(UUID organizationId, UUID actorUserId, String action, String targetType, UUID targetId,
            String details, String requestId, Instant occurredAt) {
        this.id = Ids.newId();
        this.organizationId = organizationId;
        this.actorUserId = actorUserId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.details = details;
        this.requestId = requestId;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getActorUserId() { return actorUserId; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public UUID getTargetId() { return targetId; }
    public String getDetails() { return details; }
    public String getRequestId() { return requestId; }
    public Instant getOccurredAt() { return occurredAt; }
}
