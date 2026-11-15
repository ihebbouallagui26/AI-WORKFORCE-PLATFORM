package com.aiworkforce.core.agent;

import com.aiworkforce.core.access.OrganizationAccess;
import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.access.TenantAccessService;
import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The platform-native control actions of a coordinator: {@code workflow.delegate} and {@code workflow.finish}. They are NOT
 * tools and have nothing to do with MCP. The orchestrator only forwards what the provider asked for; THIS class validates it
 * against the immutable run snapshot and the live state, records it (accepted or rejected, idempotent per coordinator
 * execution and provider tool call id), and never creates a worker itself: the scheduler does that, after the coordinator
 * turn, from the recorded accepted action.
 *
 * <p>Lock order is run, then execution (as everywhere in the workflow engine).
 */
@Service
public class WorkflowActionService {

    /** The outcome returned to the orchestrator, normalized: never an internal id, never a stack. */
    public record ActionResult(String action, String status, String code, String message) {
    }

    private static final Pattern CALL_ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,100}$");
    static final int MAX_TASK = 1500;
    static final int MAX_REJECTIONS_PER_TURN = 3;

    private final WorkflowStepRepository steps;
    private final WorkflowRunRepository runs;
    private final WorkflowActionRepository actions;
    private final ExecutionRepository executions;
    private final ExecutionService executionService;
    private final TenantAccessService access;
    private final AuditLog audit;
    private final WorkflowProperties properties;
    private final Clock clock;

    WorkflowActionService(WorkflowStepRepository steps, WorkflowRunRepository runs, WorkflowActionRepository actions,
            ExecutionRepository executions, ExecutionService executionService, TenantAccessService access, AuditLog audit,
            WorkflowProperties properties, Clock clock) {
        this.steps = steps;
        this.runs = runs;
        this.actions = actions;
        this.executions = executions;
        this.executionService = executionService;
        this.access = access;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** True when this execution is a coordinator decision turn (so it may only use the two platform actions). */
    @Transactional(readOnly = true)
    public boolean isCoordinatorTurn(UUID organizationId, UUID executionId) {
        return steps.findByOrganizationIdAndExecutionId(organizationId, executionId)
                .map(s -> WorkflowScheduler.COORDINATOR.equals(s.getRole())).orElse(false);
    }

    /**
     * Seconds left for this execution when it belongs to a workflow: the earlier of its step deadline and the workflow deadline
     * (null for an ordinary execution). The orchestrator must not run past it; the Core API still ends the child itself.
     */
    @Transactional(readOnly = true)
    public Long remainingSeconds(UUID organizationId, UUID executionId) {
        WorkflowStep step = steps.findByOrganizationIdAndExecutionId(organizationId, executionId).orElse(null);
        if (step == null) {
            return null;
        }
        WorkflowRun run = runs.findByOrganizationIdAndId(organizationId, step.getRunId()).orElse(null);
        if (run == null) {
            return null;
        }
        Instant deadline = step.getDeadlineAt() != null && step.getDeadlineAt().isBefore(run.getDeadlineAt()) ? step.getDeadlineAt() : run.getDeadlineAt();
        return Math.max(1L, java.time.Duration.between(Instant.now(clock), deadline).getSeconds());
    }

    @Transactional(noRollbackFor = {ConflictException.class})
    public ActionResult submit(UUID organizationId, UUID executionId, String toolCallId, String actionName, JsonNode arguments) {
        if (toolCallId == null || !CALL_ID.matcher(toolCallId).matches()) {
            throw new ConflictException("The action call identifier is invalid.");
        }
        WorkflowStep turn = steps.findByOrganizationIdAndExecutionId(organizationId, executionId)
                .orElseThrow(() -> new AccessDeniedException("This execution is not a workflow coordinator turn."));
        if (!WorkflowScheduler.COORDINATOR.equals(turn.getRole())) {
            throw new AccessDeniedException("Only a coordinator turn may use workflow actions.");
        }
        WorkflowRun run = runs.lockByOrganizationAndId(organizationId, turn.getRunId())
                .orElseThrow(() -> new ResourceNotFoundException("workflow run"));
        AgentExecution execution = executions.lockByOrganizationAndId(organizationId, executionId)
                .orElseThrow(() -> new ResourceNotFoundException("execution"));
        JsonNode args = arguments == null || arguments.isNull() ? JsonNodeFactory.instance.objectNode() : arguments;
        String hash = ToolArguments.sha256(ToolArguments.canonical(args));

        WorkflowAction previous = actions.findByOrganizationIdAndExecutionIdAndToolCallId(organizationId, executionId, toolCallId)
                .orElse(null);
        if (previous != null) { // a replay never creates anything: the same answer is returned
            if (!previous.getArgumentsHash().equals(hash) || !previous.getAction().equals(safeAction(actionName))) {
                throw new ConflictException("This action call identifier was already used for a different action.");
            }
            return resultOf(previous);
        }
        if (execution.getStatus() != ExecutionStatus.RUNNING) {
            throw new ConflictException("The coordinator turn is not running.");
        }
        Instant now = Instant.now(clock);
        WorkflowSpec.Snapshot snapshot = WorkflowSpec.parseSnapshot(run.getSnapshot());

        Rejection rejection = null;
        Accepted accepted = null;
        if (actions.countRejectedByOrganizationAndExecution(organizationId, executionId) >= MAX_REJECTIONS_PER_TURN) {
            rejection = new Rejection("TOO_MANY_REJECTIONS", "Too many invalid actions in this turn.");
        } else if (WorkflowAction.DELEGATE.equals(actionName)) {
            Object outcome = validateDelegate(run, snapshot, turn, args, now);
            if (outcome instanceof Rejection r) {
                rejection = r;
            } else {
                accepted = (Accepted) outcome;
            }
        } else if (WorkflowAction.FINISH.equals(actionName)) {
            Object outcome = validateFinish(run, turn, args, now);
            if (outcome instanceof Rejection r) {
                rejection = r;
            } else {
                accepted = (Accepted) outcome;
            }
        } else {
            rejection = new Rejection("UNKNOWN_ACTION", "Only workflow.delegate and workflow.finish exist.");
        }
        String ref = args.path("participantRef").isTextual() ? WorkflowText.bounded(args.path("participantRef").asText(), 16) : null;
        WorkflowAction record = rejection != null
                ? new WorkflowAction(UUID.randomUUID(), turn, toolCallId, safeAction(actionName), false, rejection.code(),
                        rejection.message(), hash, ref, null, null, null, "", null, now)
                : new WorkflowAction(UUID.randomUUID(), turn, toolCallId, actionName, true, "OK", accepted.message(), hash,
                        accepted.ref(), accepted.workerAgentId(), accepted.task(), accepted.taskHash(), accepted.inputRanks(),
                        accepted.result(), now);
        actions.save(record);
        audit.record(organizationId, null, rejection != null ? "workflow.action.rejected" : "workflow.action.accepted", "workflow",
                run.getId(), AgentSupport.details("workflowRunId", run.getId(), "executionId", executionId, "action",
                        safeAction(actionName), "code", record.getCode(), "argumentsHash", hash));
        return resultOf(record);
    }

    private record Rejection(String code, String message) {
    }

    private record Accepted(String message, String ref, UUID workerAgentId, String task, String taskHash, String inputRanks,
            String result) {
    }

    private Object validateDelegate(WorkflowRun run, WorkflowSpec.Snapshot snapshot, WorkflowStep turn, JsonNode args, Instant now) {
        UUID org = run.getOrganizationId();
        Rejection common = commonChecks(run, turn, now);
        if (common != null) {
            return common;
        }
        String ref = args.path("participantRef").asText("");
        WorkflowSpec.Member member = snapshot.member(ref);
        if (member == null) {
            return new Rejection("UNKNOWN_PARTICIPANT", "participantRef is not a participant of this workflow.");
        }
        if (member.coordinator() || member.agentId().equals(snapshot.coordinatorAgentId())) {
            return new Rejection("SELF_DELEGATION", "The coordinator cannot delegate to itself.");
        }
        if (!args.path("task").isTextual() || args.path("task").asText().isBlank()) {
            return new Rejection("INVALID_TASK", "task is required.");
        }
        String rawTask = args.path("task").asText().strip();
        if (rawTask.length() > MAX_TASK) {
            return new Rejection("INVALID_TASK", "task is longer than " + MAX_TASK + " characters.");
        }
        List<WorkflowStep> all = steps.findByOrganizationIdAndRunIdOrderByStepRankAscAttemptAsc(org, run.getId());
        if (all.stream().anyMatch(s -> !WorkflowScheduler.COORDINATOR.equals(s.getRole()) && !s.getStatus().isFinal())) {
            return new Rejection("WORKER_ACTIVE", "A worker is still running.");
        }
        long workers = all.stream().filter(s -> !WorkflowScheduler.COORDINATOR.equals(s.getRole()) && s.getAttempt() == 1).count();
        if (workers >= snapshot.maxSteps()) {
            return new Rejection("STEP_LIMIT", "The maximum number of worker steps is reached.");
        }
        List<Integer> inputs = new ArrayList<>();
        JsonNode raw = args.path("inputRanks");
        if (!raw.isMissingNode() && !raw.isNull()) {
            if (!raw.isArray() || raw.size() > 7) {
                return new Rejection("INVALID_INPUT", "inputRanks must be a list of at most 7 step numbers.");
            }
            for (JsonNode n : raw) {
                int rank = n.asInt(-1);
                boolean ok = n.isInt() && all.stream().anyMatch(s -> s.getStepRank() == rank
                        && !WorkflowScheduler.COORDINATOR.equals(s.getRole()) && s.getStatus() == WorkflowStepStatus.SUCCEEDED);
                if (!ok) {
                    return new Rejection("INVALID_INPUT", "inputRanks must reference completed worker steps.");
                }
                if (!inputs.contains(rank)) {
                    inputs.add(rank);
                }
            }
        }
        try {
            executionService.ensureRunnable(org, member.agentId());
        } catch (ConflictException e) {
            return new Rejection("AGENT_NOT_RUNNABLE", "That worker cannot run now.");
        }
        String task = WorkflowText.bounded(rawTask, MAX_TASK);
        String taskHash = ToolArguments.sha256(task.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
        if (actions.existsAcceptedDelegationByOrganization(org, run.getId(), member.agentId(), taskHash)) {
            return new Rejection("DUPLICATE_DELEGATION", "This task was already delegated to this worker.");
        }
        inputs.sort(Integer::compare);
        return new Accepted("Delegation accepted: " + member.ref() + " will run next.", member.ref(), member.agentId(), task,
                taskHash, inputs.toString().replaceAll("[\\[\\] ]", ""), null);
    }

    private Object validateFinish(WorkflowRun run, WorkflowStep turn, JsonNode args, Instant now) {
        Rejection common = commonChecks(run, turn, now);
        if (common != null) {
            return common;
        }
        if (!args.path("result").isTextual() || args.path("result").asText().isBlank()) {
            return new Rejection("INVALID_RESULT", "result is required.");
        }
        if (args.path("result").asText().length() > properties.maxResultChars()) {
            return new Rejection("INVALID_RESULT", "result is longer than " + properties.maxResultChars() + " characters.");
        }
        List<WorkflowStep> all = steps.findByOrganizationIdAndRunIdOrderByStepRankAscAttemptAsc(run.getOrganizationId(), run.getId());
        if (all.stream().anyMatch(s -> !WorkflowScheduler.COORDINATOR.equals(s.getRole()) && !s.getStatus().isFinal())) {
            return new Rejection("WORKER_ACTIVE", "A worker is still running.");
        }
        return new Accepted("Finish accepted.", null, null, null, null, "",
                WorkflowText.bounded(args.path("result").asText(), properties.maxResultChars()));
    }

    /** Checks shared by both actions: current turn, run state, one action per turn, deadline, budget, requester. */
    private Rejection commonChecks(WorkflowRun run, WorkflowStep turn, Instant now) {
        if (run.getStatus().isTerminal() || run.getStatus() == WorkflowRunStatus.CANCEL_REQUESTED) {
            return new Rejection("RUN_NOT_ACTIVE", "The workflow is no longer active.");
        }
        var latest = steps.findLatestByOrganizationAndRun(run.getOrganizationId(), run.getId(), PageRequest.of(0, 1));
        if (latest.isEmpty() || !latest.getFirst().getId().equals(turn.getId())) {
            return new Rejection("NOT_CURRENT_TURN", "This is not the current coordinator turn.");
        }
        if (actions.findAcceptedByOrganizationAndExecution(run.getOrganizationId(), turn.getExecutionId()).isPresent()) {
            return new Rejection("ACTION_ALREADY_TAKEN", "This turn already has its action.");
        }
        if (!now.isBefore(run.getDeadlineAt())) {
            return new Rejection("DEADLINE_EXCEEDED", "The workflow time limit is reached.");
        }
        if (run.getTokenBudget() - run.getTokensUsed() < WorkflowScheduler.MIN_TOKEN_RESERVE) {
            return new Rejection("TOKEN_BUDGET_EXCEEDED", "The token budget is used up.");
        }
        OrganizationAccess requester = access.findActiveAccess(run.getOrganizationId(), run.getRequestedBy()).orElse(null);
        if (requester == null || !requester.has(Permission.WORKFLOW_EXECUTE) || !requester.has(Permission.AGENT_EXECUTE)) {
            return new Rejection("REQUESTER_NOT_AUTHORIZED", "The person who started the workflow can no longer run it.");
        }
        return null;
    }

    private static String safeAction(String name) {
        return WorkflowAction.FINISH.equals(name) ? name : WorkflowAction.DELEGATE;
    }

    private static ActionResult resultOf(WorkflowAction a) {
        return new ActionResult(a.getAction(), a.getStatus(), a.getCode(), a.getMessage());
    }
}
