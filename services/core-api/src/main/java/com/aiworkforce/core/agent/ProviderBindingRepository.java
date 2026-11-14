package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.AgentParts.ProviderBinding;
import com.aiworkforce.core.common.persistence.TenantScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Provider bindings of agents, always queried within one organization. */
@TenantScoped
interface ProviderBindingRepository extends JpaRepository<ProviderBinding, UUID> {

    List<ProviderBinding> findByOrganizationIdAndAgentIdOrderByPriorityAsc(UUID organizationId, UUID agentId);

    Optional<ProviderBinding> findByOrganizationIdAndAgentIdAndId(UUID organizationId, UUID agentId, UUID id);

    Optional<ProviderBinding> findByOrganizationIdAndAgentIdAndRole(UUID organizationId, UUID agentId, BindingRole role);

    boolean existsByOrganizationIdAndAgentIdAndConnectionIdAndModelId(UUID organizationId, UUID agentId,
            UUID connectionId, String modelId);

    /** ACTIVE agents that use the connection as their PRIMARY provider. */
    @Query("""
            select count(distinct b.agentId) from ProviderBinding b, Agent a
            where b.organizationId = :organizationId and b.connectionId = :connectionId
              and b.role = com.aiworkforce.core.agent.BindingRole.PRIMARY
              and a.organizationId = :organizationId and a.id = b.agentId
              and a.status = com.aiworkforce.core.agent.AgentStatus.ACTIVE""")
    long countActivePrimaryUsersByOrganization(@Param("organizationId") UUID organizationId,
            @Param("connectionId") UUID connectionId);
}
