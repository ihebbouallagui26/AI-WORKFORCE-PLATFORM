package com.aiworkforce.core.agent;

import com.aiworkforce.core.identity.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;

/** Configuration of an agent: provider bindings, skills, tool grants and memory policy. */
@RestController
@Validated
@RequestMapping(path = "/api/v1/organizations/{organizationId}/agents/{agentId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AI agent configuration", description = "Providers, skills, tools and memory of an agent")
public class AgentConfigurationController {

    static final String MODEL_ID = "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}$";

    @Schema(name = "ProviderBindingOptions", description = "Provider-specific generation options (allowlisted keys).")
    public record OptionsRequest(
            @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
            @Min(1) @Max(1000000) Integer maxOutputTokens,
            @DecimalMin("0.0") @DecimalMax("1.0") Double topP) {

        AgentConfigurationService.BindingOptions toOptions() {
            return new AgentConfigurationService.BindingOptions(temperature, maxOutputTokens, topP);
        }
    }

    @Schema(name = "BindAgentProviderRequest")
    public record BindingRequest(
            @NotNull UUID connectionId,
            @NotBlank @Pattern(regexp = MODEL_ID) String modelId,
            @NotNull BindingRole role,
            @Schema(description = "Fallbacks only; defaults to the next free priority (1, 2, ...).") @Min(1) @Max(100) Integer priority,
            @Valid OptionsRequest options) {
    }

    @Schema(name = "UpdateAgentProviderBindingRequest", description = "Omitted fields are left unchanged.")
    public record UpdateBindingRequest(
            @Pattern(regexp = MODEL_ID) String modelId,
            @Valid OptionsRequest options,
            @Schema(description = "Promote this fallback to primary; the current primary becomes a fallback.") Boolean makePrimary,
            @Min(1) @Max(100) Integer priority) {
    }

    @Schema(name = "SetAgentSkillsRequest", description = "Replaces the skills of the agent.")
    public record SkillsRequest(@NotNull @Size(max = 30) Set<@NotNull UUID> skillIds) {
    }

    @Schema(name = "AgentToolGrantInput")
    public record ToolGrantRequest(
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_.-]{1,59}$") String toolCode,
            @NotNull ToolLevel level,
            @Schema(description = "Mandatory (true) for high-risk tools.") boolean approvalRequired,
            @Schema(description = "Reserved: per-project scope is not available yet and must be null.") UUID projectId) {
    }

    @Schema(name = "SetAgentToolsRequest", description = "Replaces the tool grants (least privilege: none by default).")
    public record ToolsRequest(@NotNull @Size(max = 20) List<@Valid ToolGrantRequest> grants) {
    }

    @Schema(name = "SetAgentMemoryConfigRequest")
    public record MemoryRequest(
            boolean enabled,
            @NotNull MemoryScope scope,
            @NotNull MemoryAccess accessMode,
            @Min(1) @Max(3650) Integer retentionDays) {
    }

    private final AgentConfigurationService configuration;

    public AgentConfigurationController(AgentConfigurationService configuration) {
        this.configuration = configuration;
    }

    // ------------------------------------------------------------------ providers

    @GetMapping("/providers")
    @Operation(summary = "Provider bindings of the agent: one primary, ordered fallbacks (agent.read)")
    public List<AgentView.Binding> bindings(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return configuration.listBindings(organizationId, agentId, user);
    }

    @PostMapping(path = "/providers", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Bind a provider connection and model (agent.manage + provider.read)")
    public List<AgentView.Binding> addBinding(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            @Parameter(hidden = true) AuthenticatedUser user, @Valid @RequestBody BindingRequest request) {
        return configuration.addBinding(organizationId, agentId, user, request.connectionId(), request.modelId(),
                request.role(), request.priority(), request.options() == null ? null : request.options().toOptions());
    }

    @PatchMapping(path = "/providers/{bindingId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Change model, options or priority, or promote a fallback to primary (agent.manage + provider.read)")
    public List<AgentView.Binding> updateBinding(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            @PathVariable UUID bindingId, @Parameter(hidden = true) AuthenticatedUser user,
            @Valid @RequestBody UpdateBindingRequest request) {
        return configuration.updateBinding(organizationId, agentId, bindingId, user, request.modelId(),
                request.options() == null ? null : request.options().toOptions(), Boolean.TRUE.equals(request.makePrimary()),
                request.priority());
    }

    @DeleteMapping("/providers/{bindingId}")
    @Operation(summary = "Remove a provider binding (agent.manage)")
    public List<AgentView.Binding> removeBinding(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            @PathVariable UUID bindingId, @Parameter(hidden = true) AuthenticatedUser user) {
        return configuration.removeBinding(organizationId, agentId, bindingId, user);
    }

    // ------------------------------------------------------------------ skills

    @GetMapping("/skills")
    @Operation(summary = "Skills of the agent (agent.read)")
    public List<AgentView.SkillRef> skills(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return configuration.listSkills(organizationId, agentId, user);
    }

    @PutMapping(path = "/skills", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Replace the skills of the agent (agent.manage + skill.read)")
    public List<AgentView.SkillRef> setSkills(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            @Parameter(hidden = true) AuthenticatedUser user, @Valid @RequestBody SkillsRequest request) {
        return configuration.setSkills(organizationId, agentId, user, request.skillIds());
    }

    // ------------------------------------------------------------------ tools

    @GetMapping("/tools")
    @Operation(summary = "Tools granted to the agent (agent.read)")
    public List<AgentView.ToolGrantView> grants(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return configuration.listGrants(organizationId, agentId, user);
    }

    @PutMapping(path = "/tools", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Replace the tool grants; nothing is executed (agent.tool.manage)")
    public List<AgentView.ToolGrantView> setGrants(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            @Parameter(hidden = true) AuthenticatedUser user, @Valid @RequestBody ToolsRequest request) {
        return configuration.setGrants(organizationId, agentId, user, request.grants().stream()
                .map(g -> new AgentConfigurationService.ToolGrantInput(g.toolCode(), g.level(), g.approvalRequired(),
                        g.projectId())).toList());
    }

    // ------------------------------------------------------------------ memory

    @GetMapping("/memory-config")
    @Operation(summary = "Memory policy of the agent (agent.read)")
    public AgentView.Memory memory(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return configuration.memory(organizationId, agentId, user);
    }

    @PutMapping(path = "/memory-config", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Set the memory policy; configuration only, no memory engine yet (agent.manage)")
    public AgentView.Memory setMemory(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            @Parameter(hidden = true) AuthenticatedUser user, @Valid @RequestBody MemoryRequest request) {
        return configuration.setMemory(organizationId, agentId, user, request.enabled(), request.scope(),
                request.accessMode(), request.retentionDays());
    }
}
