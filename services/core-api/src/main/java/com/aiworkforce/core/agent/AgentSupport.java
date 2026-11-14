package com.aiworkforce.core.agent;

import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Helpers shared by the agent services: organization-scoped loading and configuration versioning. */
@Component
class AgentSupport {

    private final AgentRepository agents;
    private final Clock clock;

    AgentSupport(AgentRepository agents, Clock clock) {
        this.agents = agents;
        this.clock = clock;
    }

    Agent load(UUID organizationId, UUID agentId) {
        return agents.findByOrganizationIdAndId(organizationId, agentId)
                .orElseThrow(() -> new ResourceNotFoundException("agent"));
    }

    /** An agent that may still be configured: archived agents are frozen. */
    Agent loadEditable(UUID organizationId, UUID agentId) {
        Agent agent = load(organizationId, agentId);
        if (agent.getStatus() == AgentStatus.ARCHIVED) {
            throw new ConflictException("This agent is archived and can no longer be modified.");
        }
        return agent;
    }

    /** Records one configuration change on the agent. */
    void touch(Agent agent) {
        agent.touch(Instant.now(clock));
    }

    Instant now() {
        return Instant.now(clock);
    }

    static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                map.put((String) keyValues[i], value instanceof UUID id ? id.toString() : value);
            }
        }
        return map;
    }
}
