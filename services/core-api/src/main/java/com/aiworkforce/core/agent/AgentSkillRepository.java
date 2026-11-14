package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.AgentParts.AgentSkill;
import com.aiworkforce.core.common.persistence.TenantScoped;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Skill assignments of agents, always queried within one organization. */
@TenantScoped
interface AgentSkillRepository extends JpaRepository<AgentSkill, AgentSkill.Key> {

    List<AgentSkill> findByOrganizationIdAndIdAgentId(UUID organizationId, UUID agentId);

    boolean existsByOrganizationIdAndIdSkillId(UUID organizationId, UUID skillId);

    /** Rows of {@code [agentId, count]}. */
    @Query("""
            select s.id.agentId, count(s) from AgentSkill s
            where s.organizationId = :organizationId and s.id.agentId in :agentIds group by s.id.agentId""")
    List<Object[]> countByOrganizationGroupedByAgent(@Param("organizationId") UUID organizationId,
            @Param("agentIds") Collection<UUID> agentIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AgentSkill s where s.organizationId = :organizationId and s.id.agentId = :agentId")
    void deleteByOrganizationAndAgent(@Param("organizationId") UUID organizationId, @Param("agentId") UUID agentId);
}
