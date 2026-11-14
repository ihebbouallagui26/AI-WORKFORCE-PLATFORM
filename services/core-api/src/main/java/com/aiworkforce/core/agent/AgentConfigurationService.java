package com.aiworkforce.core.agent;

import com.aiworkforce.core.access.OrganizationAccess;
import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.access.TenantAccessService;
import com.aiworkforce.core.agent.AgentParts.AgentSkill;
import com.aiworkforce.core.agent.AgentParts.MemoryConfig;
import com.aiworkforce.core.agent.AgentParts.ProviderBinding;
import com.aiworkforce.core.agent.AgentParts.Skill;
import com.aiworkforce.core.agent.AgentParts.ToolDefinition;
import com.aiworkforce.core.agent.AgentParts.ToolGrant;
import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.BadRequestException;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.identity.AuthenticatedUser;
import com.aiworkforce.core.provider.ConnectionStatus;
import com.aiworkforce.core.provider.ProviderDirectory;
import com.aiworkforce.core.provider.ProviderDirectory.ConnectionRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Configuration of an agent: provider bindings (one primary, ordered fallbacks), skills, tool
 * grants (least privilege) and memory policy. Every change needs the matching permission, applies
 * only inside the agent's organization, bumps the configuration version and is audited without
 * secrets. Nothing here executes a tool or calls a model.
 */
@Service
public class AgentConfigurationService {

    static final int MAX_BINDINGS = 5;
    static final int MAX_SKILLS = 30;
    static final int MAX_TOOLS = 20;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Input of a tool grant. */
    public record ToolGrantInput(String toolCode, ToolLevel level, boolean approvalRequired, UUID projectId) {
    }

    /** Validated provider-specific options, applied as a whole. */
    public record BindingOptions(Double temperature, Integer maxOutputTokens, Double topP) {
    }

    private final AgentRepository agents;
    private final ProviderBindingRepository bindings;
    private final AgentSkillRepository agentSkills;
    private final SkillRepository skills;
    private final ToolGrantRepository grants;
    private final ToolDefinitionRepository tools;
    private final MemoryConfigRepository memories;
    private final TenantAccessService access;
    private final AgentSupport support;
    private final AgentService agentService;
    private final ProviderDirectory providers;
    private final AuditLog audit;

    public AgentConfigurationService(AgentRepository agents, ProviderBindingRepository bindings,
            AgentSkillRepository agentSkills, SkillRepository skills, ToolGrantRepository grants,
            ToolDefinitionRepository tools, MemoryConfigRepository memories, TenantAccessService access,
            AgentSupport support, AgentService agentService, ProviderDirectory providers, AuditLog audit) {
        this.agents = agents;
        this.bindings = bindings;
        this.agentSkills = agentSkills;
        this.skills = skills;
        this.grants = grants;
        this.tools = tools;
        this.memories = memories;
        this.access = access;
        this.support = support;
        this.agentService = agentService;
        this.providers = providers;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ provider bindings

    @Transactional(readOnly = true)
    public List<AgentView.Binding> listBindings(UUID organizationId, UUID agentId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.AGENT_READ);
        support.load(organizationId, agentId);
        return agentService.bindingViews(organizationId, agentId);
    }

    /** Binds a provider connection and model. The first binding must be the PRIMARY one. */
    @Transactional
    public List<AgentView.Binding> addBinding(UUID organizationId, UUID agentId, AuthenticatedUser user,
            UUID connectionId, String modelId, BindingRole role, Integer priority, BindingOptions options) {
        requireManageAndProviderRead(organizationId, user);
        Agent agent = support.loadEditable(organizationId, agentId);
        providers.requireUsable(organizationId, connectionId);
        List<ProviderBinding> existing = bindings.findByOrganizationIdAndAgentIdOrderByPriorityAsc(organizationId, agentId);
        if (existing.size() >= MAX_BINDINGS) {
            throw new ConflictException("An agent can have at most " + MAX_BINDINGS + " provider bindings.");
        }
        if (bindings.existsByOrganizationIdAndAgentIdAndConnectionIdAndModelId(organizationId, agentId, connectionId,
                modelId)) {
            throw new ConflictException("This provider and model are already bound to the agent.");
        }
        boolean hasPrimary = existing.stream().anyMatch(b -> b.getRole() == BindingRole.PRIMARY);
        int position;
        if (role == BindingRole.PRIMARY) {
            if (hasPrimary) {
                throw new ConflictException("The agent already has a primary provider. Promote a binding instead.");
            }
            position = 0;
        } else {
            if (!hasPrimary) {
                throw new ConflictException("Add the primary provider first.");
            }
            position = priority != null ? priority : existing.stream().mapToInt(ProviderBinding::getPriority).max().orElse(0) + 1;
            if (position < 1) {
                throw new BadRequestException("A fallback priority starts at 1.");
            }
            if (existing.stream().anyMatch(b -> b.getPriority() == position)) {
                throw new ConflictException("Another binding already uses this priority.");
            }
        }
        bindings.save(new ProviderBinding(organizationId, agentId, connectionId, modelId, role, position,
                serialize(options), support.now()));
        bindings.flush();
        support.touch(agent);
        agents.flush();
        audit.record(organizationId, user.userId(), "agent.provider.bound", "agent", agentId,
                AgentSupport.details("connectionId", connectionId, "modelId", modelId, "role", role.name(),
                        "configVersion", agent.getConfigVersion()));
        return agentService.bindingViews(organizationId, agentId);
    }

    /** Changes model, options and/or position of a binding; {@code makePrimary} swaps it with the current primary. */
    @Transactional
    public List<AgentView.Binding> updateBinding(UUID organizationId, UUID agentId, UUID bindingId,
            AuthenticatedUser user, String modelId, BindingOptions options, boolean makePrimary, Integer priority) {
        requireManageAndProviderRead(organizationId, user);
        Agent agent = support.loadEditable(organizationId, agentId);
        ProviderBinding binding = bindings.findByOrganizationIdAndAgentIdAndId(organizationId, agentId, bindingId)
                .orElseThrow(() -> new ResourceNotFoundException("provider binding"));
        List<String> changed = new ArrayList<>();
        Instant now = support.now();
        if (modelId != null && !modelId.equals(binding.getModelId())) {
            if (bindings.existsByOrganizationIdAndAgentIdAndConnectionIdAndModelId(organizationId, agentId,
                    binding.getConnectionId(), modelId)) {
                throw new ConflictException("This provider and model are already bound to the agent.");
            }
            binding.update(modelId, binding.getConfig(), now);
            changed.add("model");
        }
        if (options != null) {
            binding.update(binding.getModelId(), serialize(options), now);
            changed.add("options");
        }
        if (priority != null && binding.getRole() == BindingRole.FALLBACK && priority != binding.getPriority()) {
            if (priority < 1) {
                throw new BadRequestException("A fallback priority starts at 1.");
            }
            if (bindings.findByOrganizationIdAndAgentIdOrderByPriorityAsc(organizationId, agentId).stream()
                    .anyMatch(b -> b.getPriority() == priority)) {
                throw new ConflictException("Another binding already uses this priority.");
            }
            binding.reposition(BindingRole.FALLBACK, priority, now);
            changed.add("priority");
        }
        bindings.flush();
        if (makePrimary && binding.getRole() == BindingRole.FALLBACK) {
            promote(organizationId, agent, binding, now);
            changed.add("primary");
        }
        if (!changed.isEmpty()) {
            support.touch(agent);
            agents.flush();
            audit.record(organizationId, user.userId(), "agent.provider.updated", "agent", agentId,
                    AgentSupport.details("bindingId", bindingId, "changed", changed, "configVersion", agent.getConfigVersion()));
        }
        return agentService.bindingViews(organizationId, agentId);
    }

    private void promote(UUID organizationId, Agent agent, ProviderBinding candidate, Instant now) {
        if (agent.getStatus() == AgentStatus.ACTIVE) {
            ConnectionRef connection = providers.findRefs(organizationId, Set.of(candidate.getConnectionId()))
                    .get(candidate.getConnectionId());
            if (connection == null || connection.status() != ConnectionStatus.VALID) {
                throw new ConflictException("An active agent needs a tested, valid connection as its primary provider.");
            }
        }
        ProviderBinding primary = bindings.findByOrganizationIdAndAgentIdAndRole(organizationId, agent.getId(),
                BindingRole.PRIMARY).orElseThrow();
        int freed = candidate.getPriority();
        // Swap in three flushed steps so the unique primary and priority constraints hold at each one.
        primary.reposition(BindingRole.FALLBACK, 1000, now);
        bindings.flush();
        candidate.reposition(BindingRole.PRIMARY, 0, now);
        bindings.flush();
        primary.reposition(BindingRole.FALLBACK, freed, now);
        bindings.flush();
    }

    @Transactional
    public List<AgentView.Binding> removeBinding(UUID organizationId, UUID agentId, UUID bindingId,
            AuthenticatedUser user) {
        access.require(organizationId, user, Permission.AGENT_MANAGE);
        Agent agent = support.loadEditable(organizationId, agentId);
        ProviderBinding binding = bindings.findByOrganizationIdAndAgentIdAndId(organizationId, agentId, bindingId)
                .orElseThrow(() -> new ResourceNotFoundException("provider binding"));
        if (binding.getRole() == BindingRole.PRIMARY && agent.getStatus() == AgentStatus.ACTIVE) {
            throw new ConflictException("Pause the agent before removing its primary provider.");
        }
        if (binding.getRole() == BindingRole.PRIMARY
                && bindings.findByOrganizationIdAndAgentIdOrderByPriorityAsc(organizationId, agentId).size() > 1) {
            throw new ConflictException("Promote a fallback to primary before removing the primary provider.");
        }
        bindings.delete(binding);
        bindings.flush();
        support.touch(agent);
        agents.flush();
        audit.record(organizationId, user.userId(), "agent.provider.unbound", "agent", agentId,
                AgentSupport.details("bindingId", bindingId, "connectionId", binding.getConnectionId(),
                        "configVersion", agent.getConfigVersion()));
        return agentService.bindingViews(organizationId, agentId);
    }

    // ------------------------------------------------------------------ skills

    @Transactional(readOnly = true)
    public List<AgentView.SkillRef> listSkills(UUID organizationId, UUID agentId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.AGENT_READ);
        support.load(organizationId, agentId);
        return agentService.skillRefs(organizationId, agentId);
    }

    /** Replaces the skills of the agent. Only active skills of this organization can be newly assigned. */
    @Transactional
    public List<AgentView.SkillRef> setSkills(UUID organizationId, UUID agentId, AuthenticatedUser user,
            Set<UUID> skillIds) {
        OrganizationAccess actor = access.require(organizationId, user, Permission.AGENT_MANAGE);
        actor.require(Permission.SKILL_READ);
        if (skillIds.size() > MAX_SKILLS) {
            throw new BadRequestException("An agent can have at most " + MAX_SKILLS + " skills.");
        }
        Agent agent = support.loadEditable(organizationId, agentId);
        Map<UUID, Skill> found = skillIds.isEmpty() ? Map.of() : skills.findByOrganizationIdAndIdIn(organizationId, skillIds)
                .stream().collect(Collectors.toMap(Skill::getId, Function.identity()));
        if (found.size() != skillIds.size()) {
            throw new ResourceNotFoundException("skill");
        }
        Set<UUID> before = agentSkills.findByOrganizationIdAndIdAgentId(organizationId, agentId).stream()
                .map(AgentSkill::getSkillId).collect(Collectors.toSet());
        for (UUID id : skillIds) {
            if (!before.contains(id) && !found.get(id).isActive()) {
                throw new ConflictException("The skill \"" + found.get(id).getName() + "\" is deactivated.");
            }
        }
        if (!before.equals(skillIds)) {
            agentSkills.deleteByOrganizationAndAgent(organizationId, agentId);
            Instant now = support.now();
            agentSkills.saveAll(skillIds.stream().map(id -> new AgentSkill(organizationId, agentId, id, user.userId(), now)).toList());
            agentSkills.flush();
            // The bulk delete cleared the persistence context: reload the agent before versioning it.
            Agent managed = support.load(organizationId, agentId);
            support.touch(managed);
            agents.flush();
            audit.record(organizationId, user.userId(), "agent.skills.changed", "agent", agentId,
                    AgentSupport.details("count", skillIds.size(), "configVersion", managed.getConfigVersion()));
        }
        return agentService.skillRefs(organizationId, agentId);
    }

    // ------------------------------------------------------------------ tools

    /** The global tool registry (metadata only: nothing is executable in this phase). */
    @Transactional(readOnly = true)
    public List<AgentView.ToolView> toolCatalog(UUID organizationId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.AGENT_READ);
        return tools.findAll().stream().sorted(Comparator.comparing(ToolDefinition::getCategory)
                        .thenComparing(ToolDefinition::getCode))
                .map(d -> new AgentView.ToolView(d.getCode(), d.getName(), d.getDescription(), d.getCategory(),
                        d.getRisk(), d.getMaxLevel(), d.getMcpServer(), d.getMcpTool(), d.isEnabled())).toList();
    }

    @Transactional(readOnly = true)
    public List<AgentView.ToolGrantView> listGrants(UUID organizationId, UUID agentId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.AGENT_READ);
        support.load(organizationId, agentId);
        return agentService.toolViews(organizationId, agentId);
    }

    /**
     * Replaces the tool grants (least privilege: no grant means no access). A grant cannot exceed the
     * tool's maximum level, HIGH risk tools always require approval, and per-project scope is not
     * available until projects exist in the backend.
     */
    @Transactional
    public List<AgentView.ToolGrantView> setGrants(UUID organizationId, UUID agentId, AuthenticatedUser user,
            List<ToolGrantInput> inputs) {
        access.require(organizationId, user, Permission.AGENT_TOOL_MANAGE);
        if (inputs.size() > MAX_TOOLS) {
            throw new BadRequestException("An agent can have at most " + MAX_TOOLS + " tool grants.");
        }
        Agent agent = support.loadEditable(organizationId, agentId);
        Map<String, ToolDefinition> definitions = tools.findAll().stream()
                .collect(Collectors.toMap(ToolDefinition::getCode, Function.identity()));
        Set<String> seen = new TreeSet<>();
        for (ToolGrantInput input : inputs) {
            ToolDefinition tool = definitions.get(input.toolCode());
            if (tool == null) {
                throw new ResourceNotFoundException("tool");
            }
            if (!seen.add(input.toolCode())) {
                throw new BadRequestException("A tool can only be granted once.");
            }
            if (!tool.isEnabled()) {
                throw new ConflictException("The tool \"" + tool.getName() + "\" is disabled.");
            }
            if (!input.level().atMost(tool.getMaxLevel())) {
                throw new BadRequestException("The level " + input.level() + " exceeds the maximum of the tool \""
                        + tool.getName() + "\" (" + tool.getMaxLevel() + ").");
            }
            if (tool.getRisk() == ToolRisk.HIGH && !input.approvalRequired()) {
                throw new BadRequestException("The tool \"" + tool.getName() + "\" is high risk: approval is mandatory.");
            }
            if (input.projectId() != null) {
                throw new BadRequestException("Per-project scope is not available yet.");
            }
        }
        Set<String> before = grants.findByOrganizationIdAndIdAgentId(organizationId, agentId).stream()
                .map(ToolGrant::getToolCode).collect(Collectors.toCollection(TreeSet::new));
        grants.deleteByOrganizationAndAgent(organizationId, agentId);
        Instant now = support.now();
        grants.saveAll(inputs.stream().map(i -> new ToolGrant(organizationId, agentId, i.toolCode(), i.level(),
                i.approvalRequired(), user.userId(), now)).toList());
        grants.flush();
        // The bulk delete cleared the persistence context: reload the agent before versioning it.
        Agent managed = support.load(organizationId, agentId);
        support.touch(managed);
        agents.flush();
        Set<String> added = new TreeSet<>(seen);
        added.removeAll(before);
        Set<String> removed = new TreeSet<>(before);
        removed.removeAll(seen);
        audit.record(organizationId, user.userId(), "agent.tools.changed", "agent", agentId,
                AgentSupport.details("granted", List.copyOf(added), "revoked", List.copyOf(removed),
                        "total", seen.size(), "configVersion", managed.getConfigVersion()));
        return agentService.toolViews(organizationId, agentId);
    }

    // ------------------------------------------------------------------ memory

    @Transactional(readOnly = true)
    public AgentView.Memory memory(UUID organizationId, UUID agentId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.AGENT_READ);
        support.load(organizationId, agentId);
        return agentService.memoryView(organizationId, agentId);
    }

    /** Memory policy only. Disabled by default; never beyond the organization. */
    @Transactional
    public AgentView.Memory setMemory(UUID organizationId, UUID agentId, AuthenticatedUser user, boolean enabled,
            MemoryScope scope, MemoryAccess accessMode, Integer retentionDays) {
        access.require(organizationId, user, Permission.AGENT_MANAGE);
        Agent agent = support.loadEditable(organizationId, agentId);
        if (retentionDays != null && (retentionDays < 1 || retentionDays > 3650)) {
            throw new BadRequestException("Retention must be between 1 and 3650 days.");
        }
        MemoryConfig config = memories.findByOrganizationIdAndAgentId(organizationId, agentId)
                .orElseGet(() -> memories.save(new MemoryConfig(organizationId, agentId, support.now())));
        config.update(enabled, scope, accessMode, retentionDays, support.now());
        memories.flush();
        support.touch(agent);
        agents.flush();
        audit.record(organizationId, user.userId(), "agent.memory.changed", "agent", agentId,
                AgentSupport.details("enabled", enabled, "scope", scope.name(), "access", accessMode.name(),
                        "retentionDays", retentionDays, "configVersion", agent.getConfigVersion()));
        return agentService.memoryView(organizationId, agentId);
    }

    // ------------------------------------------------------------------ helpers

    private void requireManageAndProviderRead(UUID organizationId, AuthenticatedUser user) {
        OrganizationAccess actor = access.require(organizationId, user, Permission.AGENT_MANAGE);
        // Using a provider connection requires being allowed to see provider connections.
        actor.require(Permission.PROVIDER_READ);
    }

    private static String serialize(BindingOptions options) {
        if (options == null) {
            return "{}";
        }
        Map<String, Object> map = new LinkedHashMap<>();
        if (options.temperature() != null) {
            if (options.temperature() < 0 || options.temperature() > 2) {
                throw new BadRequestException("temperature must be between 0 and 2.");
            }
            map.put("temperature", options.temperature());
        }
        if (options.topP() != null) {
            if (options.topP() < 0 || options.topP() > 1) {
                throw new BadRequestException("topP must be between 0 and 1.");
            }
            map.put("topP", options.topP());
        }
        if (options.maxOutputTokens() != null) {
            if (options.maxOutputTokens() < 1 || options.maxOutputTokens() > 1_000_000) {
                throw new BadRequestException("maxOutputTokens must be between 1 and 1000000.");
            }
            map.put("maxOutputTokens", options.maxOutputTokens());
        }
        return JSON.writeValueAsString(map);
    }
}
