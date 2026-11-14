package com.aiworkforce.core.agent;

import com.aiworkforce.core.common.persistence.TenantScoped;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Participants of workflow definitions, always queried within one organization. */
@TenantScoped
interface WorkflowParticipantRepository extends JpaRepository<WorkflowParticipant, UUID> {

    List<WorkflowParticipant> findByOrganizationIdAndDefinitionId(UUID organizationId, UUID definitionId);
}
