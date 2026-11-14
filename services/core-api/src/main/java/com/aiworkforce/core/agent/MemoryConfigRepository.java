package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.AgentParts.MemoryConfig;
import com.aiworkforce.core.common.persistence.TenantScoped;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Memory policies of agents, always queried within one organization. */
@TenantScoped
interface MemoryConfigRepository extends JpaRepository<MemoryConfig, UUID> {

    Optional<MemoryConfig> findByOrganizationIdAndAgentId(UUID organizationId, UUID agentId);
}
