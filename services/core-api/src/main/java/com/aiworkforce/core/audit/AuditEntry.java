package com.aiworkforce.core.audit;

import java.time.Instant;
import java.util.UUID;

/** Read model of an audit event. {@code details} is a JSON object without secrets. */
public record AuditEntry(UUID id, UUID organizationId, UUID actorUserId, String action, String targetType,
        UUID targetId, String details, String requestId, Instant occurredAt) {

    static AuditEntry of(AuditEvent e) {
        return new AuditEntry(e.getId(), e.getOrganizationId(), e.getActorUserId(), e.getAction(), e.getTargetType(),
                e.getTargetId(), e.getDetails(), e.getRequestId(), e.getOccurredAt());
    }
}
