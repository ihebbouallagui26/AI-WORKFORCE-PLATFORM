package com.aiworkforce.core.agent;

import com.aiworkforce.core.access.OrganizationAccess;
import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.access.TenantAccessService;
import com.aiworkforce.core.agent.WorkflowViews.DefinitionView;
import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.BadRequestException;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.identity.AuthenticatedUser;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workflow definitions: name, limits, EXPLICIT participants and ordered static steps. Each change bumps the version; runs
 * work on a snapshot, so a change never affects a run in progress. Team membership never makes an agent a participant.
 */
@Service
public class WorkflowDefinitionService {

    static final int DEFAULT_TOKEN_BUDGET = 200_000;
    static final int DEFAULT_DEADLINE_SECONDS = 1800;

    private final WorkflowDefinitionRepository definitions;
    private final WorkflowParticipantRepository participants;
    private final AgentRepository agents;
    private final AgentSupport support;
    private final ExecutionService executionService;
    private final TenantAccessService access;
    private final AuditLog audit;
    private final Clock clock;

    public WorkflowDefinitionService(WorkflowDefinitionRepository definitions, WorkflowParticipantRepository participants,
            AgentRepository agents, AgentSupport support, ExecutionService executionService, TenantAccessService access,
            AuditLog audit, Clock clock) {
        this.definitions = definitions;
        this.participants = participants;
        this.agents = agents;
        this.support = support;
        this.executionService = executionService;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public DefinitionView create(UUID organizationId, AuthenticatedUser user, String name, String description, Integer maxSteps,
            Integer maxRetriesPerStep, Integer tokenBudget, Integer deadlineSeconds) {
        access.require(organizationId, user, Permission.WORKFLOW_MANAGE);
        WorkflowDefinition definition = new WorkflowDefinition(UUID.randomUUID(), organizationId, cleanName(name),
                description == null ? "" : description.strip(), limit(maxSteps, 8, 1, WorkflowSpec.MAX_STEPS, "maxSteps"),
                limit(maxRetriesPerStep, 0, 0, 2, "maxRetriesPerStep"),
                limit(tokenBudget, DEFAULT_TOKEN_BUDGET, 1000, 10_000_000, "tokenBudget"),
                limit(deadlineSeconds, DEFAULT_DEADLINE_SECONDS, 30, 86_400, "deadlineSeconds"), user.userId(), Instant.now(clock));
        definitions.save(definition);
        audit.record(organizationId, user.userId(), "workflow.created", "workflow", definition.getId(),
                AgentSupport.details("name", definition.getName()));
        return view(organizationId, definition);
    }

    @Transactional(readOnly = true)
    public Page<DefinitionView> list(UUID organizationId, AuthenticatedUser user, WorkflowDefinitionStatus status, Pageable pageable) {
        access.require(organizationId, user, Permission.WORKFLOW_READ);
        return definitions.searchByOrganization(organizationId, status, pageable).map(d -> view(organizationId, d));
    }

    @Transactional(readOnly = true)
    public DefinitionView get(UUID organizationId, UUID id, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.WORKFLOW_READ);
        return view(organizationId, load(organizationId, id));
    }

    @Transactional
    public DefinitionView update(UUID organizationId, UUID id, AuthenticatedUser user, String name, String description,
            Integer maxSteps, Integer maxRetriesPerStep, Integer tokenBudget, Integer deadlineSeconds) {
        access.require(organizationId, user, Permission.WORKFLOW_MANAGE);
        WorkflowDefinition definition = lock(organizationId, id);
        if (maxSteps != null && WorkflowSpec.parseSteps(definition.getSteps()).size() > limit(maxSteps, 8, 1, WorkflowSpec.MAX_STEPS, "maxSteps")) {
            throw new ConflictException("The workflow already has more steps than this maximum.");
        }
        definition.configure(name == null ? null : cleanName(name), description == null ? null : description.strip(),
                maxSteps == null ? null : limit(maxSteps, 8, 1, WorkflowSpec.MAX_STEPS, "maxSteps"),
                maxRetriesPerStep == null ? null : limit(maxRetriesPerStep, 0, 0, 2, "maxRetriesPerStep"),
                tokenBudget == null ? null : limit(tokenBudget, DEFAULT_TOKEN_BUDGET, 1000, 10_000_000, "tokenBudget"),
                deadlineSeconds == null ? null : limit(deadlineSeconds, DEFAULT_DEADLINE_SECONDS, 30, 86_400, "deadlineSeconds"),
                Instant.now(clock));
        audit.record(organizationId, user.userId(), "workflow.updated", "workflow", id, AgentSupport.details("version", definition.getVersion()));
        return view(organizationId, definition);
    }

    /**
     * Replaces the participants with exactly the agents chosen here. Each must belong to the organization, be readable by
     * the person configuring (agent.read), be ACTIVE and have a valid, enabled primary provider.
     */
    @Transactional
    public DefinitionView setParticipants(UUID organizationId, UUID id, AuthenticatedUser user, List<UUID> agentIds) {
        OrganizationAccess granted = access.require(organizationId, user, Permission.WORKFLOW_MANAGE);
        granted.require(Permission.AGENT_READ);
        WorkflowDefinition definition = lock(organizationId, id);
        Set<UUID> wanted = new LinkedHashSet<>(agentIds == null ? List.of() : agentIds);
        if (wanted.size() > 16) {
            throw new BadRequestException("A workflow may have at most 16 participants.");
        }
        for (UUID agentId : wanted) {
            support.load(organizationId, agentId); // 404 for an agent of another organization
            try {
                executionService.ensureRunnable(organizationId, agentId);
            } catch (ConflictException e) {
                throw new ConflictException("Agent " + agentId + " cannot take part: " + e.getMessage());
            }
        }
        List<WorkflowParticipant> current = participants.findByOrganizationIdAndDefinitionId(organizationId, id);
        Set<UUID> usedBySteps = WorkflowSpec.parseSteps(definition.getSteps()).stream().map(WorkflowSpec.Step::agentId)
                .collect(Collectors.toSet());
        Instant now = Instant.now(clock);
        for (WorkflowParticipant existing : current) {
            if (!wanted.contains(existing.getAgentId())) {
                if (usedBySteps.contains(existing.getAgentId())) {
                    throw new ConflictException("An agent used by a step cannot be removed: change the steps first.");
                }
                participants.delete(existing);
            }
        }
        Set<UUID> present = current.stream().map(WorkflowParticipant::getAgentId).collect(Collectors.toSet());
        for (UUID agentId : wanted) {
            if (!present.contains(agentId)) {
                participants.save(new WorkflowParticipant(UUID.randomUUID(), organizationId, id, agentId, user.userId(), now));
            }
        }
        definition.participantsChanged(now);
        audit.record(organizationId, user.userId(), "workflow.participants.updated", "workflow", id,
                AgentSupport.details("participants", wanted.size(), "version", definition.getVersion()));
        return view(organizationId, definition);
    }

    @Transactional
    public DefinitionView setSteps(UUID organizationId, UUID id, AuthenticatedUser user, List<WorkflowSpec.StepInput> submitted) {
        access.require(organizationId, user, Permission.WORKFLOW_MANAGE);
        WorkflowDefinition definition = lock(organizationId, id);
        Set<UUID> participantAgents = participants.findByOrganizationIdAndDefinitionId(organizationId, id).stream()
                .map(WorkflowParticipant::getAgentId).collect(Collectors.toSet());
        List<WorkflowSpec.Step> steps = WorkflowSpec.normalize(submitted, participantAgents, definition.getMaxSteps());
        definition.replaceSteps(WorkflowSpec.toJson(steps), Instant.now(clock));
        audit.record(organizationId, user.userId(), "workflow.steps.updated", "workflow", id,
                AgentSupport.details("steps", steps.size(), "version", definition.getVersion()));
        return view(organizationId, definition);
    }

    @Transactional
    public DefinitionView archive(UUID organizationId, UUID id, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.WORKFLOW_MANAGE);
        WorkflowDefinition definition = lock(organizationId, id);
        definition.archive(Instant.now(clock));
        audit.record(organizationId, user.userId(), "workflow.archived", "workflow", id, AgentSupport.details("version", definition.getVersion()));
        return view(organizationId, definition);
    }

    // ------------------------------------------------------------------ helpers

    private WorkflowDefinition load(UUID organizationId, UUID id) {
        return definitions.findByOrganizationIdAndId(organizationId, id).orElseThrow(() -> new ResourceNotFoundException("workflow"));
    }

    private WorkflowDefinition lock(UUID organizationId, UUID id) {
        return definitions.lockByOrganizationAndId(organizationId, id).orElseThrow(() -> new ResourceNotFoundException("workflow"));
    }

    private DefinitionView view(UUID organizationId, WorkflowDefinition definition) {
        List<WorkflowParticipant> people = participants.findByOrganizationIdAndDefinitionId(organizationId, definition.getId());
        Map<UUID, Agent> byId = new HashMap<>();
        agents.findByOrganizationIdAndIdIn(organizationId, people.stream().map(WorkflowParticipant::getAgentId).toList())
                .forEach(a -> byId.put(a.getId(), a));
        return DefinitionView.of(definition, people, byId);
    }

    private static String cleanName(String name) {
        String cleaned = name == null ? "" : name.strip();
        if (cleaned.isEmpty() || cleaned.length() > 120) {
            throw new BadRequestException("A workflow needs a name of at most 120 characters.");
        }
        return cleaned;
    }

    private static int limit(Integer value, int fallback, int min, int max, String field) {
        int actual = value == null ? fallback : value;
        if (actual < min || actual > max) {
            throw new BadRequestException(field + " must be between " + min + " and " + max + ".");
        }
        return actual;
    }
}
