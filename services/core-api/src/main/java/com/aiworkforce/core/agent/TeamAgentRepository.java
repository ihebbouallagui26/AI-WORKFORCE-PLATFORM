package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.AgentParts.TeamAgentMember;
import com.aiworkforce.core.common.persistence.TenantScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Agents in teams, always queried within one organization. */
@TenantScoped
interface TeamAgentRepository extends JpaRepository<TeamAgentMember, UUID> {

    List<TeamAgentMember> findByOrganizationIdAndTeamIdOrderByJoinedAtDesc(UUID organizationId, UUID teamId);

    List<TeamAgentMember> findByOrganizationIdAndTeamIdAndLeftAtIsNullOrderByJoinedAtAsc(UUID organizationId, UUID teamId);

    List<TeamAgentMember> findByOrganizationIdAndAgentIdAndLeftAtIsNull(UUID organizationId, UUID agentId);

    Optional<TeamAgentMember> findByOrganizationIdAndTeamIdAndAgentIdAndLeftAtIsNull(UUID organizationId, UUID teamId,
            UUID agentId);

    boolean existsByOrganizationIdAndTeamIdAndAgentIdAndLeftAtIsNull(UUID organizationId, UUID teamId, UUID agentId);

    /** Rows of {@code [teamId, count]} of current agent members whose agent is not archived. */
    @Query("""
            select m.teamId, count(m) from TeamAgentMember m, Agent a
            where m.organizationId = :organizationId and m.leftAt is null
              and a.organizationId = :organizationId and a.id = m.agentId
              and a.status <> com.aiworkforce.core.agent.AgentStatus.ARCHIVED
            group by m.teamId""")
    List<Object[]> countCurrentByOrganizationGroupedByTeam(@Param("organizationId") UUID organizationId);
}
