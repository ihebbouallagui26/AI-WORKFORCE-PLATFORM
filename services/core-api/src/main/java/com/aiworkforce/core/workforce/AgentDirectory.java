package com.aiworkforce.core.workforce;

import java.util.Map;
import java.util.UUID;

/**
 * Agent counts needed by the department and team modules, so that human employees and AI agents
 * stay separately identifiable in every view. Declared in the shared kernel and implemented by the
 * {@code agent} module (department and team never depend on it). Archived agents are not counted.
 */
public interface AgentDirectory {

    Map<UUID, Long> countByDepartment(UUID organizationId);

    long countInDepartment(UUID organizationId, UUID departmentId);

    /** Current (not left, not archived) agent members per team. */
    Map<UUID, Long> countByTeam(UUID organizationId);
}
