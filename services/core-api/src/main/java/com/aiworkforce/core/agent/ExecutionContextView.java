package com.aiworkforce.core.agent;

import com.aiworkforce.core.provider.ProviderType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * The authorized, read-only configuration of one ACTIVE agent as needed by the AI orchestrator.
 *
 * <p>It carries references only: connection ids (never credentials), tool codes with their granted
 * level and the MCP coordinates declared in the registry. Provider secrets are never part of it: they
 * stay in the SecretStore and will be resolved server-side at call time by a dedicated mechanism.
 */
@Schema(name = "AgentExecutionContext")
public record ExecutionContextView(
        UUID organizationId,
        UUID agentId,
        String handle,
        String name,
        AgentStatus status,
        int configVersion,
        String systemInstructions,
        List<ProviderRef> providers,
        List<ToolPermission> tools,
        List<String> skills,
        MemorySettings memory) {

    /** Which connection and model the agent may use (only connections that are currently VALID). */
    @Schema(name = "ExecutionProviderBinding")
    public record ProviderRef(UUID connectionId, ProviderType providerType, String modelId, BindingRole role,
            int priority) {
    }

    /** One approved tool grant, with the MCP server and tool it maps to (if any). */
    @Schema(name = "ExecutionToolPermission")
    public record ToolPermission(String toolCode, ToolLevel level, boolean approvalRequired, String mcpServer,
            String mcpTool) {
    }

    @Schema(name = "ExecutionMemorySettings")
    public record MemorySettings(boolean enabled, MemoryScope scope, MemoryAccess accessMode, Integer retentionDays) {
    }
}
