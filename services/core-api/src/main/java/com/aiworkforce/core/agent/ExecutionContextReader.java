package com.aiworkforce.core.agent;

import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.provider.ConnectionStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the execution context for the AI orchestrator's service account.
 *
 * <p>The service account has no membership, so there is no user permission check: instead the agent
 * is loaded <em>through its organization</em> (a mismatching pair is a 404, exactly like for users),
 * only ACTIVE agents are served, and the result is a narrow, read-only projection without secrets.
 */
@Component
class ExecutionContextReader {

    private final AgentSupport support;
    private final AgentService agents;
    private final ToolDefinitionRepository toolDefinitions;

    ExecutionContextReader(AgentSupport support, AgentService agents, ToolDefinitionRepository toolDefinitions) {
        this.support = support;
        this.agents = agents;
        this.toolDefinitions = toolDefinitions;
    }

    @Transactional(readOnly = true)
    public ExecutionContextView read(UUID organizationId, UUID agentId) {
        Agent agent = support.load(organizationId, agentId);
        if (agent.getStatus() != AgentStatus.ACTIVE) {
            throw new ConflictException("The agent is not active.");
        }
        AgentView.Detail detail = agents.detail(organizationId, agent, true);

        List<ExecutionContextView.ProviderRef> providers = detail.providers().stream()
                .filter(binding -> binding.connection().status() == ConnectionStatus.VALID)
                .map(binding -> new ExecutionContextView.ProviderRef(binding.connection().id(),
                        binding.connection().providerType(), binding.modelId(), binding.role(), binding.priority()))
                .toList();

        Map<String, AgentParts.ToolDefinition> registry = toolDefinitions
                .findAllById(detail.tools().stream().map(AgentView.ToolGrantView::toolCode).toList()).stream()
                .collect(Collectors.toMap(AgentParts.ToolDefinition::getCode, Function.identity()));
        List<ExecutionContextView.ToolPermission> tools = detail.tools().stream()
                .filter(grant -> registry.get(grant.toolCode()) != null && registry.get(grant.toolCode()).isEnabled())
                .map(grant -> new ExecutionContextView.ToolPermission(grant.toolCode(), grant.level(),
                        grant.approvalRequired(), registry.get(grant.toolCode()).getMcpServer(),
                        registry.get(grant.toolCode()).getMcpTool()))
                .toList();

        AgentView.Memory memory = detail.memory();
        return new ExecutionContextView(organizationId, agentId, agent.getHandle(), agent.getName(), agent.getStatus(),
                agent.getConfigVersion(), detail.systemInstructions() == null ? "" : detail.systemInstructions(),
                providers, tools, detail.skills().stream().filter(AgentView.SkillRef::active)
                        .map(AgentView.SkillRef::name).toList(),
                new ExecutionContextView.MemorySettings(memory.enabled(), memory.scope(), memory.accessMode(),
                        memory.retentionDays()));
    }
}
