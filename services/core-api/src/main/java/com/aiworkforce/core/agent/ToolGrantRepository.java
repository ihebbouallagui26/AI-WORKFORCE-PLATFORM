package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.AgentParts.ToolGrant;
import com.aiworkforce.core.common.persistence.TenantScoped;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tool grants of agents, always queried within one organization. */
@TenantScoped
interface ToolGrantRepository extends JpaRepository<ToolGrant, ToolGrant.Key> {

    List<ToolGrant> findByOrganizationIdAndIdAgentId(UUID organizationId, UUID agentId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ToolGrant g where g.organizationId = :organizationId and g.id.agentId = :agentId")
    void deleteByOrganizationAndAgent(@Param("organizationId") UUID organizationId, @Param("agentId") UUID agentId);
}
