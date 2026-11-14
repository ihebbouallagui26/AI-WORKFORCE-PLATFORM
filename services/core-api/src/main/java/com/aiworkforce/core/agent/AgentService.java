package com.aiworkforce.core.agent;

import com.aiworkforce.core.access.OrganizationAccess;
import com.aiworkforce.core.access.OrganizationLock;
import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.access.TenantAccessService;
import com.aiworkforce.core.agent.AgentParts.MemoryConfig;
import com.aiworkforce.core.agent.AgentParts.ProviderBinding;
import com.aiworkforce.core.agent.AgentParts.Skill;
import com.aiworkforce.core.agent.AgentParts.TeamAgentMember;
import com.aiworkforce.core.agent.AgentParts.ToolDefinition;
import com.aiworkforce.core.agent.AgentParts.ToolGrant;
import com.aiworkforce.core.audit.AuditEntry;
import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.BadRequestException;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.persistence.LikePatterns;
import com.aiworkforce.core.department.DepartmentDirectory;
import com.aiworkforce.core.department.DepartmentRef;
import com.aiworkforce.core.identity.AuthenticatedUser;
import com.aiworkforce.core.provider.ConnectionStatus;
import com.aiworkforce.core.provider.ProviderDirectory;
import com.aiworkforce.core.provider.ProviderDirectory.ConnectionRef;
import com.aiworkforce.core.team.TeamDirectory;
import com.aiworkforce.core.team.TeamDirectory.TeamRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI agents of an organization: identity, lifecycle and the read model of their configuration.
 * An agent is an AI identity owned by the organization: not a user account, not an employee.
 * Nothing here runs an agent. Every operation starts with the tenant check.
 */
@Service
public class AgentService {

    static final int MAX_AGENTS = 200;
    static final int MAX_RESPONSIBILITIES = 20;
    static final int MAX_RESPONSIBILITY_LENGTH = 200;

    /** Identity data of a new agent. */
    public record NewAgent(String name, String handle, Integer avatarTone, String title, String description,
            UUID departmentId, List<String> responsibilities, String systemInstructions) {
    }

    /** Identity changes; null means unchanged. */
    public record AgentChanges(String name, Integer avatarTone, String title, String description,
            List<String> responsibilities, String systemInstructions, Long expectedVersion) {
    }

    private final AgentRepository agents;
    private final ProviderBindingRepository bindings;
    private final AgentSkillRepository agentSkills;
    private final SkillRepository skills;
    private final ToolGrantRepository grants;
    private final ToolDefinitionRepository tools;
    private final MemoryConfigRepository memories;
    private final TeamAgentRepository teamAgents;
    private final TenantAccessService access;
    private final AgentSupport support;
    private final DepartmentDirectory departments;
    private final TeamDirectory teams;
    private final ProviderDirectory providers;
    private final AuditLog audit;
    private final OrganizationLock lock;

    public AgentService(AgentRepository agents, ProviderBindingRepository bindings, AgentSkillRepository agentSkills,
            SkillRepository skills, ToolGrantRepository grants, ToolDefinitionRepository tools,
            MemoryConfigRepository memories, TeamAgentRepository teamAgents, TenantAccessService access,
            AgentSupport support, DepartmentDirectory departments, TeamDirectory teams, ProviderDirectory providers,
            AuditLog audit, OrganizationLock lock) {
        this.lock = lock;
        this.agents = agents;
        this.bindings = bindings;
        this.agentSkills = agentSkills;
        this.skills = skills;
        this.grants = grants;
        this.tools = tools;
        this.memories = memories;
        this.teamAgents = teamAgents;
        this.access = access;
        this.support = support;
        this.departments = departments;
        this.teams = teams;
        this.providers = providers;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Page<AgentView> list(UUID organizationId, AuthenticatedUser user, String query, AgentStatus status,
            UUID departmentId, Pageable pageable) {
        access.require(organizationId, user, Permission.AGENT_READ);
        Page<Agent> page = agents.searchByOrganization(organizationId, status, departmentId,
                LikePatterns.contains(query), pageable);
        Map<UUID, AgentView> views = summaries(organizationId, page.getContent()).stream()
                .collect(Collectors.toMap(AgentView::id, v -> v));
        return page.map(a -> views.get(a.getId()));
    }

    @Transactional(readOnly = true)
    public AgentView.Detail get(UUID organizationId, UUID agentId, AuthenticatedUser user) {
        OrganizationAccess actor = access.require(organizationId, user, Permission.AGENT_READ);
        return detail(organizationId, support.load(organizationId, agentId), actor.has(Permission.AGENT_MANAGE));
    }

    /** Configuration history: the audit trail of the agent (no instructions, no secrets). */
    @Transactional(readOnly = true)
    public Page<AuditEntry> history(UUID organizationId, UUID agentId, AuthenticatedUser user, Pageable pageable) {
        access.require(organizationId, user, Permission.AGENT_READ);
        support.load(organizationId, agentId);
        return audit.findForTarget(organizationId, "agent", agentId, pageable);
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public AgentView.Detail create(UUID organizationId, AuthenticatedUser user, NewAgent data) {
        access.require(organizationId, user, Permission.AGENT_CREATE);
        if (agents.countByOrganizationId(organizationId) >= MAX_AGENTS) {
            throw new ConflictException("An organization can have at most " + MAX_AGENTS + " AI agents.");
        }
        String name = data.name().trim();
        String handle = data.handle() == null || data.handle().isBlank() ? uniqueHandle(organizationId, name)
                : requireFreeHandle(organizationId, data.handle().trim().toLowerCase(Locale.ROOT));
        if (data.departmentId() != null) {
            departments.requireActive(organizationId, data.departmentId());
        }
        Instant now = support.now();
        Agent agent = agents.save(new Agent(organizationId, handle, name,
                data.avatarTone() == null ? 1 + Math.floorMod(handle.hashCode(), 8) : data.avatarTone(),
                clean(data.title()), clean(data.description()), data.departmentId(),
                responsibilities(data.responsibilities()), data.systemInstructions() == null ? "" : data.systemInstructions(),
                user.userId(), now));
        memories.save(new MemoryConfig(organizationId, agent.getId(), now));
        agents.flush();
        audit.record(organizationId, user.userId(), "agent.created", "agent", agent.getId(),
                AgentSupport.details("name", name, "handle", handle, "departmentId", data.departmentId()));
        return detail(organizationId, agent, true);
    }

    @Transactional
    public AgentView.Detail update(UUID organizationId, UUID agentId, AuthenticatedUser user, AgentChanges changes) {
        access.require(organizationId, user, Permission.AGENT_MANAGE);
        Agent agent = support.loadEditable(organizationId, agentId);
        if (changes.expectedVersion() != null && changes.expectedVersion() != agent.getVersion()) {
            throw new ConflictException("This agent was modified by someone else. Reload and retry.");
        }
        List<String> changed = new ArrayList<>();
        String name = changes.name() == null ? agent.getName() : changes.name().trim();
        if (!name.equals(agent.getName())) changed.add("name");
        String title = changes.title() == null ? agent.getTitle() : clean(changes.title());
        if (!title.equals(agent.getTitle())) changed.add("title");
        String description = changes.description() == null ? agent.getDescription() : clean(changes.description());
        if (!description.equals(agent.getDescription())) changed.add("description");
        int tone = changes.avatarTone() == null ? agent.getAvatarTone() : changes.avatarTone();
        if (tone != agent.getAvatarTone()) changed.add("avatar");
        List<String> duties = changes.responsibilities() == null ? agent.getResponsibilities()
                : responsibilities(changes.responsibilities());
        if (!duties.equals(agent.getResponsibilities())) changed.add("responsibilities");
        String instructions = changes.systemInstructions() == null ? agent.getSystemInstructions()
                : changes.systemInstructions();
        if (!instructions.equals(agent.getSystemInstructions())) changed.add("systemInstructions");
        if (!changed.isEmpty()) {
            agent.updateIdentity(name, tone, title, description, duties, instructions, support.now());
            agents.flush();
            // The audit trail records WHICH fields changed, never the instructions themselves.
            audit.record(organizationId, user.userId(), "agent.updated", "agent", agentId,
                    AgentSupport.details("fields", changed, "configVersion", agent.getConfigVersion()));
        }
        return detail(organizationId, agent, true);
    }

    @Transactional
    public AgentView.Detail changeDepartment(UUID organizationId, UUID agentId, AuthenticatedUser user,
            UUID departmentId) {
        access.require(organizationId, user, Permission.AGENT_MANAGE);
        Agent agent = support.loadEditable(organizationId, agentId);
        if (departmentId != null) {
            departments.requireActive(organizationId, departmentId);
        }
        agent.assignDepartment(departmentId, support.now());
        agents.flush();
        audit.record(organizationId, user.userId(), "agent.department.changed", "agent", agentId,
                AgentSupport.details("departmentId", departmentId, "configVersion", agent.getConfigVersion()));
        return detail(organizationId, agent, true);
    }

    /**
     * DRAFT or PAUSED → ACTIVE. Requires a PRIMARY provider binding whose connection is VALID (a saved
     * credential is not enough: it must have been tested). ACTIVE does not start anything.
     */
    @Transactional
    public AgentView.Detail activate(UUID organizationId, UUID agentId, AuthenticatedUser user) {
        OrganizationAccess actor = access.require(organizationId, user, Permission.AGENT_ACTIVATE);
        Agent agent = support.load(organizationId, agentId);
        if (agent.getStatus() != AgentStatus.DRAFT && agent.getStatus() != AgentStatus.PAUSED) {
            throw new ConflictException("Only a draft or paused agent can be activated.");
        }
        ProviderBinding primary = bindings.findByOrganizationIdAndAgentIdAndRole(organizationId, agentId,
                BindingRole.PRIMARY).orElseThrow(() -> new ConflictException(
                        "Configure a primary provider before activating this agent."));
        ConnectionRef connection = providers.findRefs(organizationId, Set.of(primary.getConnectionId()))
                .get(primary.getConnectionId());
        if (connection == null || connection.status() != ConnectionStatus.VALID) {
            throw new ConflictException("The primary provider connection must be tested and valid before activation.");
        }
        if (agent.getDepartmentId() != null) {
            departments.requireActive(organizationId, agent.getDepartmentId());
        }
        agent.changeStatus(AgentStatus.ACTIVE, support.now());
        agents.flush();
        audit.record(organizationId, user.userId(), "agent.activated", "agent", agentId, Map.of());
        return detail(organizationId, agent, actor.has(Permission.AGENT_MANAGE));
    }

    @Transactional
    public AgentView.Detail pause(UUID organizationId, UUID agentId, AuthenticatedUser user) {
        OrganizationAccess actor = access.require(organizationId, user, Permission.AGENT_ACTIVATE);
        Agent agent = support.load(organizationId, agentId);
        if (agent.getStatus() != AgentStatus.ACTIVE) {
            throw new ConflictException("Only an active agent can be paused.");
        }
        agent.changeStatus(AgentStatus.PAUSED, support.now());
        agents.flush();
        audit.record(organizationId, user.userId(), "agent.paused", "agent", agentId, Map.of());
        return detail(organizationId, agent, actor.has(Permission.AGENT_MANAGE));
    }

    /** Archiving is final: the agent leaves its teams (history kept) and can no longer be configured or assigned. */
    @Transactional
    public AgentView.Detail archive(UUID organizationId, UUID agentId, AuthenticatedUser user) {
        OrganizationAccess actor = access.require(organizationId, user, Permission.AGENT_ARCHIVE);
        lock.acquire(organizationId);
        Agent agent = support.load(organizationId, agentId);
        if (agent.getStatus() == AgentStatus.ARCHIVED) {
            throw new ConflictException("This agent is already archived.");
        }
        Instant now = support.now();
        teamAgents.findByOrganizationIdAndAgentIdAndLeftAtIsNull(organizationId, agentId)
                .forEach(member -> member.leave(now));
        agent.changeStatus(AgentStatus.ARCHIVED, now);
        agents.flush();
        audit.record(organizationId, user.userId(), "agent.archived", "agent", agentId, Map.of());
        return detail(organizationId, agent, actor.has(Permission.AGENT_MANAGE));
    }

    // ------------------------------------------------------------------ read models (shared with the configuration service)

    List<AgentView.Binding> bindingViews(UUID organizationId, UUID agentId) {
        List<ProviderBinding> rows = bindings.findByOrganizationIdAndAgentIdOrderByPriorityAsc(organizationId, agentId);
        Map<UUID, ConnectionRef> refs = providers.findRefs(organizationId,
                rows.stream().map(ProviderBinding::getConnectionId).collect(Collectors.toSet()));
        return rows.stream().map(b -> new AgentView.Binding(b.getId(), refs.get(b.getConnectionId()), b.getModelId(),
                b.getRole(), b.getPriority(), b.getConfig())).toList();
    }

    List<AgentView.SkillRef> skillRefs(UUID organizationId, UUID agentId) {
        Set<UUID> ids = agentSkills.findByOrganizationIdAndIdAgentId(organizationId, agentId).stream()
                .map(AgentParts.AgentSkill::getSkillId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return List.of();
        }
        return skills.findByOrganizationIdAndIdIn(organizationId, ids).stream()
                .sorted(Comparator.comparing(Skill::getName, String.CASE_INSENSITIVE_ORDER))
                .map(s -> new AgentView.SkillRef(s.getId(), s.getName(), s.getCategory(), s.isActive())).toList();
    }

    List<AgentView.ToolGrantView> toolViews(UUID organizationId, UUID agentId) {
        List<ToolGrant> rows = grants.findByOrganizationIdAndIdAgentId(organizationId, agentId);
        Map<String, ToolDefinition> definitions = tools.findAllById(rows.stream().map(ToolGrant::getToolCode).toList())
                .stream().collect(Collectors.toMap(ToolDefinition::getCode, Function.identity()));
        return rows.stream().map(g -> {
            ToolDefinition d = definitions.get(g.getToolCode());
            return new AgentView.ToolGrantView(g.getToolCode(), d.getName(), d.getRisk(), d.getMaxLevel(), g.getLevel(),
                    g.isApprovalRequired());
        }).sorted(Comparator.comparing(AgentView.ToolGrantView::toolCode)).toList();
    }

    AgentView.Memory memoryView(UUID organizationId, UUID agentId) {
        return memories.findByOrganizationIdAndAgentId(organizationId, agentId)
                .map(m -> new AgentView.Memory(m.isEnabled(), m.getScope(), m.getAccessMode(), m.getRetentionDays(),
                        m.getUpdatedAt()))
                .orElse(new AgentView.Memory(false, MemoryScope.PROJECT, MemoryAccess.AGENT_ONLY, null, null));
    }

    AgentView.Detail detail(UUID organizationId, Agent agent, boolean includeInstructions) {
        UUID agentId = agent.getId();
        Set<UUID> teamIds = teamAgents.findByOrganizationIdAndAgentIdAndLeftAtIsNull(organizationId, agentId).stream()
                .map(TeamAgentMember::getTeamId).collect(Collectors.toSet());
        List<TeamRef> teamRefs = teams.findRefs(organizationId, teamIds).values().stream()
                .sorted(Comparator.comparing(TeamRef::name, String.CASE_INSENSITIVE_ORDER)).toList();
        return new AgentView.Detail(summaries(organizationId, List.of(agent)).getFirst(),
                includeInstructions ? agent.getSystemInstructions() : null, bindingViews(organizationId, agentId),
                skillRefs(organizationId, agentId), toolViews(organizationId, agentId), memoryView(organizationId, agentId),
                teamRefs);
    }

    List<AgentView> summaries(UUID organizationId, Collection<Agent> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, DepartmentRef> departmentRefs = departments.findRefs(organizationId,
                list.stream().map(Agent::getDepartmentId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, Long> skillCounts = new HashMap<>();
        for (Object[] row : agentSkills.countByOrganizationGroupedByAgent(organizationId,
                list.stream().map(Agent::getId).toList())) {
            skillCounts.put((UUID) row[0], (Long) row[1]);
        }
        return list.stream().map(a -> new AgentView(a.getId(), a.getOrganizationId(), a.getHandle(), a.getName(),
                a.getAvatarTone(), a.getTitle(), a.getDescription(),
                a.getDepartmentId() == null ? null : departmentRefs.get(a.getDepartmentId()), a.getResponsibilities(),
                a.getStatus(), a.getConfigVersion(), skillCounts.getOrDefault(a.getId(), 0L), a.getCreatedBy(),
                a.getCreatedAt(), a.getUpdatedAt(), a.getVersion())).toList();
    }

    // ------------------------------------------------------------------ helpers

    private String requireFreeHandle(UUID organizationId, String handle) {
        if (agents.existsByOrganizationIdAndHandle(organizationId, handle)) {
            throw new ConflictException("An agent with this identifier already exists.");
        }
        return handle;
    }

    /** Derives a unique identifier from the name (letters, digits and hyphens). */
    private String uniqueHandle(UUID organizationId, String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.length() > 34) {
            base = base.substring(0, 34).replaceAll("-+$", "");
        }
        if (base.length() < 2) {
            base = "agent";
        }
        String candidate = base;
        for (int i = 2; agents.existsByOrganizationIdAndHandle(organizationId, candidate); i++) {
            candidate = base + "-" + i;
            if (i > 50) {
                throw new ConflictException("Could not derive a free identifier; provide one.");
            }
        }
        return candidate;
    }

    private static List<String> responsibilities(List<String> input) {
        if (input == null) {
            return List.of();
        }
        List<String> cleaned = input.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (cleaned.size() > MAX_RESPONSIBILITIES) {
            throw new BadRequestException("An agent can have at most " + MAX_RESPONSIBILITIES + " responsibilities.");
        }
        if (cleaned.stream().anyMatch(s -> s.length() > MAX_RESPONSIBILITY_LENGTH)) {
            throw new BadRequestException("A responsibility can have at most " + MAX_RESPONSIBILITY_LENGTH + " characters.");
        }
        return cleaned;
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }
}
