package com.aiworkforce.core.agent;

import com.aiworkforce.core.provider.ConnectionUsage;
import com.aiworkforce.core.workforce.AgentDirectory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter of the ports other modules use to learn about agents: counts for departments and teams
 * ({@link AgentDirectory}) and the dependency check of provider connections ({@link ConnectionUsage}).
 * No permission check: callers have already verified access to the organization.
 */
@Service
class AgentDirectoryAdapter implements AgentDirectory, ConnectionUsage {

    private final AgentRepository agents;
    private final TeamAgentRepository teamAgents;
    private final ProviderBindingRepository bindings;

    AgentDirectoryAdapter(AgentRepository agents, TeamAgentRepository teamAgents, ProviderBindingRepository bindings) {
        this.agents = agents;
        this.teamAgents = teamAgents;
        this.bindings = bindings;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> countByDepartment(UUID organizationId) {
        return toMap(agents.countLiveByOrganizationGroupedByDepartment(organizationId));
    }

    @Override
    @Transactional(readOnly = true)
    public long countInDepartment(UUID organizationId, UUID departmentId) {
        return agents.countByOrganizationIdAndDepartmentIdAndStatusNot(organizationId, departmentId, AgentStatus.ARCHIVED);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> countByTeam(UUID organizationId) {
        return toMap(teamAgents.countCurrentByOrganizationGroupedByTeam(organizationId));
    }

    @Override
    @Transactional(readOnly = true)
    public long activeAgentsUsingAsPrimary(UUID organizationId, UUID connectionId) {
        return bindings.countActivePrimaryUsersByOrganization(organizationId, connectionId);
    }

    private static Map<UUID, Long> toMap(List<Object[]> rows) {
        Map<UUID, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put((UUID) row[0], (Long) row[1]);
        }
        return map;
    }
}
