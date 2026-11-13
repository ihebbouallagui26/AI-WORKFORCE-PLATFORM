package com.aiworkforce.core.audit;

import com.aiworkforce.core.common.persistence.TenantScoped;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Audit trail, read per organization. */
@TenantScoped
interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    Page<AuditEvent> findByOrganizationIdOrderByOccurredAtDesc(UUID organizationId, Pageable pageable);
}
