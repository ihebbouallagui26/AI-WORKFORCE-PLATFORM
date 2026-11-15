package com.aiworkforce.core.agent;

import com.aiworkforce.core.access.OrganizationAccess;
import com.aiworkforce.core.access.OrganizationLock;
import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.access.TenantAccessService;
import com.aiworkforce.core.agent.WorkflowViews.MessageView;
import com.aiworkforce.core.agent.WorkflowViews.RunView;
import com.aiworkforce.core.agent.WorkflowViews.StepView;
import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.BadRequestException;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.common.logging.RequestIdFilter;
import com.aiworkforce.core.identity.AuthenticatedUser;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs of workflows (user side): start, inspect, cancel. Starting needs {@code workflow.execute} AND {@code agent.execute}:
 * the first never replaces the second. The definition is copied into the run, so later changes never reach it.
 */
@Service
public class WorkflowRunService {

    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    static final int MAX_OBJECTIVE = 2000;

    private final WorkflowDefinitionRepository definitions;
    private final WorkflowParticipantRepository participants;
    private final WorkflowRunRepository runs;
    private final WorkflowStepRepository steps;
    private final WorkflowMessageRepository messages;
    private final WorkflowActionRepository actionRepository;
    private final AgentRepository agents;
    private final ExecutionService executionService;
    private final WorkflowScheduler scheduler;
    private final TenantAccessService access;
    private final OrganizationLock organizationLock;
    private final AuditLog audit;
    private final WorkflowProperties properties;
    private final Clock clock;

    public WorkflowRunService(WorkflowDefinitionRepository definitions, WorkflowParticipantRepository participants,
            WorkflowRunRepository runs, WorkflowStepRepository steps, WorkflowMessageRepository messages, WorkflowActionRepository actionRepository, AgentRepository agents,
            ExecutionService executionService, WorkflowScheduler scheduler, TenantAccessService access,
            OrganizationLock organizationLock, AuditLog audit, WorkflowProperties properties, Clock clock) {
        this.definitions = definitions;
        this.participants = participants;
        this.runs = runs;
        this.steps = steps;
        this.messages = messages;
        this.actionRepository = actionRepository;
        this.agents = agents;
        this.executionService = executionService;
        this.scheduler = scheduler;
        this.access = access;
        this.organizationLock = organizationLock;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public RunView start(UUID organizationId, UUID definitionId, AuthenticatedUser user, String objective, String requestId) {
        OrganizationAccess granted = access.require(organizationId, user, Permission.WORKFLOW_EXECUTE);
        granted.require(Permission.AGENT_EXECUTE);
        String cleaned = objective == null ? "" : objective.strip();
        if (cleaned.isEmpty() || cleaned.length() > MAX_OBJECTIVE) {
            throw new BadRequestException("An objective of at most " + MAX_OBJECTIVE + " characters is required.");
        }
        if (requestId != null && !REQUEST_ID.matcher(requestId).matches()) {
            throw new BadRequestException("requestId must be 1 to 64 characters among letters, digits and . _ : -");
        }
        if (requestId != null) {
            WorkflowRun existing = runs.findByOrganizationIdAndRequestedByAndRequestId(organizationId, user.userId(), requestId).orElse(null);
            if (existing != null) {
                if (!existing.getDefinitionId().equals(definitionId)) {
                    throw new ConflictException("This requestId was already used for another workflow.");
                }
                return RunView.of(existing); // a repeated launch request never creates a second run
            }
        }
        WorkflowDefinition definition = definitions.lockByOrganizationAndId(organizationId, definitionId)
                .orElseThrow(() -> new ResourceNotFoundException("workflow"));
        if (definition.getStatus() != WorkflowDefinitionStatus.ACTIVE) {
            throw new ConflictException("This workflow is archived and cannot be started.");
        }
        List<WorkflowSpec.Step> stepList = WorkflowSpec.parseSteps(definition.getSteps());
        Set<UUID> participantAgents = participants.findByOrganizationIdAndDefinitionId(organizationId, definitionId).stream()
                .map(WorkflowParticipant::getAgentId).collect(Collectors.toCollection(HashSet::new));
        boolean coordinated = definition.getMode() == WorkflowMode.COORDINATED;
        if (coordinated) {
            validateCoordinated(definition, participantAgents);
        } else {
            validateComplete(definition, stepList, participantAgents);
        }
        for (UUID agentId : participantAgents) {
            try {
                executionService.ensureRunnable(organizationId, agentId); // ACTIVE agent, valid provider
            } catch (ConflictException e) {
                throw new ConflictException("Agent " + agentId + " cannot run: " + e.getMessage());
            }
        }

        organizationLock.acquire(organizationId);
        if (runs.countOpenByOrganization(organizationId) >= properties.maxOpenRunsPerOrganization()) {
            throw new ConflictException("Too many workflow runs are in progress. Try again when one has finished.");
        }

        Instant now = Instant.now(clock);
        List<WorkflowSpec.Member> members = coordinated ? members(organizationId, definition, participantAgents) : List.of();
        WorkflowSpec.Snapshot snapshot = new WorkflowSpec.Snapshot(definition.getId(), definition.getVersion(), definition.getName(),
                stepList, participantAgents.stream().sorted().toList(), definition.getMaxSteps(), definition.getMaxRetriesPerStep(),
                definition.getTokenBudget(), definition.getDeadlineSeconds(), definition.getMode().name(),
                coordinated ? definition.getCoordinatorAgentId() : null, members);
        // save() merges an entity with an assigned id and returns the MANAGED copy: only that one may be modified afterwards.
        WorkflowRun run = runs.save(new WorkflowRun(UUID.randomUUID(), definition, WorkflowSpec.toJson(snapshot), user.userId(),
                requestId, WorkflowText.mask(cleaned), coordinated ? definition.getMaxSteps() : stepList.size(), MDC.get(RequestIdFilter.MDC_KEY), now));
        scheduler.appendObjective(run, now);
        audit.record(organizationId, user.userId(), "workflow.run.requested", "workflow", run.getId(), AgentSupport.details(
                "workflowRunId", run.getId(), "definitionId", definitionId, "version", definition.getVersion(), "steps", stepList.size()));
        scheduler.advance(run); // first step now; if the organization is at its execution limit the next pass retries
        return RunView.of(run);
    }

    private static void validateCoordinated(WorkflowDefinition definition, Set<UUID> participantAgents) {
        UUID coordinator = definition.getCoordinatorAgentId();
        if (coordinator == null || !participantAgents.contains(coordinator)) {
            throw new ConflictException("A coordinated workflow needs a coordinator among its participants.");
        }
        if (participantAgents.size() < 2) {
            throw new ConflictException("A coordinated workflow needs at least one worker besides its coordinator.");
        }
    }

    /** Opaque, workflow-scoped references: the coordinator only ever sees these, never an agent id. */
    private List<WorkflowSpec.Member> members(UUID organizationId, WorkflowDefinition definition, Set<UUID> participantAgents) {
        Map<UUID, Agent> byId = new HashMap<>();
        agents.findByOrganizationIdAndIdIn(organizationId, List.copyOf(participantAgents)).forEach(a -> byId.put(a.getId(), a));
        List<WorkflowSpec.Member> out = new java.util.ArrayList<>();
        Agent coordinatorAgent = byId.get(definition.getCoordinatorAgentId());
        out.add(new WorkflowSpec.Member("coordinator", coordinatorAgent.getId(), coordinatorAgent.getName(), coordinatorAgent.getTitle(),
                coordinatorAgent.getDescription(), true));
        int n = 1;
        for (UUID id : participantAgents.stream().filter(i -> !i.equals(definition.getCoordinatorAgentId())).sorted().toList()) {
            Agent a = byId.get(id);
            out.add(new WorkflowSpec.Member("worker_" + n++, a.getId(), a.getName(), a.getTitle(), a.getDescription(), false));
        }
        return out;
    }

    private static void validateComplete(WorkflowDefinition definition, List<WorkflowSpec.Step> stepList, Set<UUID> participantAgents) {
        if (stepList.isEmpty()) {
            throw new ConflictException("The workflow has no steps.");
        }
        if (stepList.size() > Math.min(definition.getMaxSteps(), WorkflowSpec.MAX_STEPS)) {
            throw new ConflictException("The workflow has more steps than its maximum.");
        }
        Set<UUID> used = new HashSet<>();
        for (WorkflowSpec.Step step : stepList) {
            if (!participantAgents.contains(step.agentId())) {
                throw new ConflictException("Step " + step.rank() + " uses an agent that is not a participant.");
            }
            used.add(step.agentId());
        }
        if (!used.containsAll(participantAgents)) {
            throw new ConflictException("Every participant must be assigned to at least one step.");
        }
    }

    @Transactional(readOnly = true)
    public Page<RunView> list(UUID organizationId, AuthenticatedUser user, WorkflowRunStatus status, UUID definitionId, Pageable pageable) {
        access.require(organizationId, user, Permission.WORKFLOW_READ);
        return runs.searchByOrganization(organizationId, status, definitionId, pageable).map(RunView::of);
    }

    @Transactional(readOnly = true)
    public RunView get(UUID organizationId, UUID runId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.WORKFLOW_READ);
        return RunView.of(load(organizationId, runId));
    }

    @Transactional(readOnly = true)
    public Page<StepView> steps(UUID organizationId, UUID runId, AuthenticatedUser user, Pageable pageable) {
        access.require(organizationId, user, Permission.WORKFLOW_READ);
        load(organizationId, runId);
        Page<WorkflowStep> page = steps.findByOrganizationIdAndRunId(organizationId, runId, pageable);
        Map<UUID, String> names = new HashMap<>();
        agents.findByOrganizationIdAndIdIn(organizationId, page.getContent().stream().map(WorkflowStep::getAgentId).distinct().toList())
                .forEach(a -> names.put(a.getId(), a.getName()));
        return page.map(s -> StepView.of(s, names.get(s.getAgentId())));
    }

    @Transactional(readOnly = true)
    public Page<WorkflowViews.ActionView> actions(UUID organizationId, UUID runId, AuthenticatedUser user, Pageable pageable) {
        access.require(organizationId, user, Permission.WORKFLOW_READ);
        load(organizationId, runId);
        return actionRepository.findByOrganizationIdAndRunId(organizationId, runId, pageable).map(WorkflowViews.ActionView::of);
    }

    @Transactional(readOnly = true)
    public Page<MessageView> messages(UUID organizationId, UUID runId, AuthenticatedUser user, Pageable pageable) {
        access.require(organizationId, user, Permission.WORKFLOW_READ);
        load(organizationId, runId);
        return messages.findByOrganizationIdAndRunId(organizationId, runId, pageable).map(MessageView::of);
    }

    /**
     * Cooperative cancellation. The run becomes CANCEL_REQUESTED at once (nothing new may start) and is settled by the
     * scheduler: a CANCELLED run means every child is over. Started external effects are never reported as undone.
     */
    @Transactional
    public RunView cancel(UUID organizationId, UUID runId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.WORKFLOW_EXECUTE);
        WorkflowRun run = runs.lockByOrganizationAndId(organizationId, runId).orElseThrow(() -> new ResourceNotFoundException("workflow run"));
        if (run.getStatus().isTerminal()) {
            throw new ConflictException("The workflow run is already " + run.getStatus() + ".");
        }
        Instant now = Instant.now(clock);
        boolean first = run.getStatus() != WorkflowRunStatus.CANCEL_REQUESTED;
        run.requestCancel(user.userId(), now);
        if (first) {
            scheduler.appendStatus(run, "Cancellation requested. Nothing new will start; started steps are not undone.", now);
            audit.record(organizationId, user.userId(), "workflow.run.cancel-requested", "workflow", runId,
                    AgentSupport.details("workflowRunId", runId));
        }
        scheduler.advance(run);
        return RunView.of(run);
    }

    private WorkflowRun load(UUID organizationId, UUID runId) {
        return runs.findByOrganizationIdAndId(organizationId, runId).orElseThrow(() -> new ResourceNotFoundException("workflow run"));
    }
}
