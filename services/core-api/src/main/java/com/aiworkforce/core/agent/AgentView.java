package com.aiworkforce.core.agent;

import com.aiworkforce.core.department.DepartmentRef;
import com.aiworkforce.core.provider.ProviderDirectory.ConnectionRef;
import com.aiworkforce.core.team.TeamDirectory.TeamRef;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read models of agents (never entities). Provider connections appear only as short references
 * (id, type, name, status): no credential material exists in any of these records.
 */
@Schema(name = "AgentSummary")
public record AgentView(
        UUID id,
        UUID organizationId,
        String handle,
        String name,
        int avatarTone,
        String title,
        String description,
        DepartmentRef department,
        List<String> responsibilities,
        AgentStatus status,
        int configVersion,
        long skillCount,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    /** A provider binding with its connection reference. */
    @Schema(name = "AgentProviderBinding")
    public record Binding(UUID id, ConnectionRef connection, String modelId, BindingRole role, int priority,
            String config) {
    }

    @Schema(name = "AgentSkillReference")
    public record SkillRef(UUID id, String name, String category, boolean active) {
    }

    /** A granted tool with its registry metadata. */
    @Schema(name = "AgentToolGrant")
    public record ToolGrantView(String toolCode, String toolName, ToolRisk risk, ToolLevel maxLevel, ToolLevel level,
            boolean approvalRequired) {
    }

    /** A tool of the global registry. */
    @Schema(name = "ToolDefinition")
    public record ToolView(String code, String name, String description, String category, ToolRisk risk,
            ToolLevel maxLevel, String mcpServer, String mcpTool, boolean enabled) {
    }

    @Schema(name = "AgentMemoryConfig")
    public record Memory(boolean enabled, MemoryScope scope, MemoryAccess accessMode, Integer retentionDays,
            Instant updatedAt) {
    }

    /**
     * Everything about one agent. {@code systemInstructions} is null unless the caller may manage
     * agents (least privilege: a reader sees the identity, not the prompt).
     */
    @Schema(name = "AgentDetail")
    public record Detail(AgentView agent, String systemInstructions, List<Binding> providers, List<SkillRef> skills,
            List<ToolGrantView> tools, Memory memory, List<TeamRef> teams) {
    }
}
