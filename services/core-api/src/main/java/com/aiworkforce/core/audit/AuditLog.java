package com.aiworkforce.core.audit;

import com.aiworkforce.core.common.logging.RequestIdFilter;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records security-relevant changes in the same transaction as the change itself, so an
 * audited action and its audit record are committed (or rolled back) together.
 * Details must never contain tokens, secrets or credentials.
 */
@Service
public class AuditLog {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AuditEventRepository events;
    private final Clock clock;

    public AuditLog(AuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID organizationId, UUID actorUserId, String action, String targetType, UUID targetId,
            Map<String, ?> details) {
        events.save(new AuditEvent(organizationId, actorUserId, action, targetType, targetId,
                JSON.writeValueAsString(details == null ? Map.of() : details),
                MDC.get(RequestIdFilter.MDC_KEY), Instant.now(clock)));
    }

    /** Callers must have checked the organization access and the audit permission first. */
    @Transactional(readOnly = true)
    public Page<AuditEntry> findForOrganization(UUID organizationId, Pageable pageable) {
        return events.findByOrganizationIdOrderByOccurredAtDesc(organizationId, pageable).map(AuditEntry::of);
    }
}
