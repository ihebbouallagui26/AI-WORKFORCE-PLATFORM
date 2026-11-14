package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.AgentParts.ToolDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

/** The global, read-only tool registry (not organization data). */
interface ToolDefinitionRepository extends JpaRepository<ToolDefinition, String> {
}
