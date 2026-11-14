package com.aiworkforce.core.agent;

import com.aiworkforce.core.common.persistence.TenantScoped;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** The append-only journal of workflow runs, always queried within one organization. */
@TenantScoped
interface WorkflowMessageRepository extends JpaRepository<WorkflowMessage, UUID> {

    Page<WorkflowMessage> findByOrganizationIdAndRunId(UUID organizationId, UUID runId, Pageable pageable);
}
