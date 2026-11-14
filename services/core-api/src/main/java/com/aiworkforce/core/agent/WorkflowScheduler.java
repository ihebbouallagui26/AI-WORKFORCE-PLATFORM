package com.aiworkforce.core.agent;

import com.aiworkforce.core.access.OrganizationAccess;
import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.access.TenantAccessService;
import com.aiworkforce.core.agent.ExecutionService.CapacityExceededException;
import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The deterministic sequential scheduler. It is the ONLY place where a workflow moves: one step at a time, each step a
 * real child execution launched through the C1 path with the step agent's own identity, grants and provider.
 *
 * <p>{@link #advance(WorkflowRun)} is idempotent and always runs with the run row locked, so a repeated pass, a pass
 * after a restart, or two passes at once can never schedule a step twice (a unique index backs this up). All the
 * state it needs is in PostgreSQL: nothing is lost when the Core API or the orchestrator restarts.
 */
@Component
class WorkflowScheduler {

    static final String STEP_FAILED = "STEP_FAILED";
    static final String STEP_NOT_RUNNABLE = "STEP_NOT_RUNNABLE";
    static final String TOKEN_BUDGET_EXCEEDED = "TOKEN_BUDGET_EXCEEDED";
    static final String REQUESTER_NOT_AUTHORIZED = "REQUESTER_NOT_AUTHORIZED";
    static final String CANCELLED_CODE = "WORKFLOW_CANCELLED";
    static final String TIMEOUT_CODE = "WORKFLOW_TIMEOUT";
    /** Provider failures that may be retried automatically, and only when no tool effect has started. */
    private static final Set<String> TRANSIENT = Set.of("PROVIDER_TIMEOUT", "PROVIDER_UNAVAILABLE", "PROVIDER_RATE_LIMIT");

    private final WorkflowRunRepository runs;
    private final WorkflowStepRepository steps;
    private final WorkflowMessageRepository messages;
    private final ExecutionRepository executions;
    private final ToolInvocationRepository invocations;
    private final ExecutionService executionService;
    private final ExecutionCoordinator coordinator;
    private final TenantAccessService access;
    private final AuditLog audit;
    private final WorkflowProperties properties;
    private final Clock clock;

    WorkflowScheduler(WorkflowRunRepository runs, WorkflowStepRepository steps, WorkflowMessageRepository messages,
            ExecutionRepository executions, ToolInvocationRepository invocations, ExecutionService executionService,
            ExecutionCoordinator coordinator, TenantAccessService access, AuditLog audit, WorkflowProperties properties,
            Clock clock) {
        this.runs = runs;
        this.steps = steps;
        this.messages = messages;
        this.executions = executions;
        this.invocations = invocations;
        this.executionService = executionService;
        this.coordinator = coordinator;
        this.access = access;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** One scheduler pass for one run, in its own transaction (used by the maintenance loop and by tests). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void advanceById(UUID organizationId, UUID runId) {
        runs.lockByOrganizationAndId(organizationId, runId).ifPresent(this::advance);
    }

    /** The pass itself. The run MUST be locked by the caller's transaction. */
    @Transactional
    void advance(WorkflowRun run) {
        if (run.getStatus().isTerminal()) {
            return;
        }
        Instant now = Instant.now(clock);
        UUID org = run.getOrganizationId();
        WorkflowSpec.Snapshot snapshot = WorkflowSpec.parseSnapshot(run.getSnapshot());
        WorkflowStep current = latest(run);
        AgentExecution execution = current == null ? null
                : executions.lockByOrganizationAndId(org, current.getExecutionId()).orElse(null);
        boolean live = current != null && !current.getStatus().isFinal();

        // The total time limit applies to everything, including a run that is being cancelled.
        if (!now.isBefore(run.getDeadlineAt())) {
            stopChild(execution, TIMEOUT_CODE, "The workflow exceeded its total time limit.");
            if (live) {
                settle(run, current, execution, now);
            }
            run.timeout(now);
            append(run, null, WorkflowMessageKind.STATUS, "The workflow ended: total time limit reached.", now);
            audit(run, "workflow.run.timeout");
            return;
        }

        if (live) {
            if (run.getStatus() == WorkflowRunStatus.CANCEL_REQUESTED && execution != null && !execution.getStatus().isTerminal()) {
                requestStop(run, execution, now);
            }
            if (execution == null || execution.getStatus().isTerminal()) {
                settle(run, current, execution, now);
            } else {
                mirror(run, current, execution, now);
                return;
            }
        }

        if (run.getStatus() == WorkflowRunStatus.CANCEL_REQUESTED) {
            run.cancelled(null, now);
            append(run, null, WorkflowMessageKind.STATUS, "The workflow was cancelled. Started steps are not undone.", now);
            audit(run, "workflow.run.cancelled");
            return;
        }
        decideNext(run, snapshot, latest(run), now);
    }

    // ------------------------------------------------------------------ what happens after a step

    private void decideNext(WorkflowRun run, WorkflowSpec.Snapshot snapshot, WorkflowStep last, Instant now) {
        if (last == null) {
            schedule(run, snapshot, 1, 1, now);
            return;
        }
        switch (last.getStatus()) {
            case SUCCEEDED -> {
                if (last.getStepRank() >= run.getTotalSteps()) {
                    String finalResult = executions.findByOrganizationIdAndId(run.getOrganizationId(), last.getExecutionId())
                            .map(AgentExecution::getResult).orElse(last.getResultSummary());
                    run.succeed(WorkflowText.bounded(finalResult, properties.maxResultChars()), now);
                    append(run, null, WorkflowMessageKind.STATUS, "The workflow succeeded.", now);
                    audit(run, "workflow.run.succeeded");
                } else {
                    schedule(run, snapshot, last.getStepRank() + 1, 1, now);
                }
            }
            case FAILED -> {
                boolean retry = last.getAttempt() <= snapshot.maxRetriesPerStep() && last.getErrorCode() != null && TRANSIENT.contains(last.getErrorCode())
                        && !invocations.existsStartedByOrganizationAndExecution(run.getOrganizationId(), last.getExecutionId());
                if (retry) {
                    append(run, last.getStepRank(), WorkflowMessageKind.STATUS,
                            "Step " + last.getStepRank() + " failed with a transient provider error: retrying (a new execution).", now);
                    schedule(run, snapshot, last.getStepRank(), last.getAttempt() + 1, now);
                } else {
                    // FAIL_FAST: nothing after a failed step ever starts. OUTCOME_UNKNOWN is never retried.
                    run.fail(STEP_FAILED, "Step " + last.getStepRank() + " (" + WorkflowText.bounded(last.getName(), 80)
                            + ") failed: " + (last.getErrorCode() == null ? "error" : last.getErrorCode()) + ".", now);
                    append(run, last.getStepRank(), WorkflowMessageKind.STATUS, "The workflow failed at step " + last.getStepRank() + ".", now);
                    audit(run, "workflow.run.failed");
                }
            }
            case CANCELLED -> {
                run.fail(STEP_FAILED, "Step " + last.getStepRank() + " was cancelled.", now);
                audit(run, "workflow.run.failed");
            }
            default -> {
                // RUNNING / WAITING_APPROVAL are handled before: nothing to decide.
            }
        }
    }

    private void schedule(WorkflowRun run, WorkflowSpec.Snapshot snapshot, int rank, int attempt, Instant now) {
        UUID org = run.getOrganizationId();
        if (run.getTokensUsed() >= run.getTokenBudget()) {
            failRun(run, TOKEN_BUDGET_EXCEEDED, "The token budget of the workflow is used up.", now);
            return;
        }
        // Authorization is checked again before EVERY step: workflow.execute never replaces agent.execute.
        OrganizationAccess requester = access.findActiveAccess(org, run.getRequestedBy()).orElse(null);
        if (requester == null || !requester.has(Permission.WORKFLOW_EXECUTE) || !requester.has(Permission.AGENT_EXECUTE)) {
            failRun(run, REQUESTER_NOT_AUTHORIZED, "The person who started the workflow can no longer run it.", now);
            return;
        }
        WorkflowSpec.Step definition = snapshot.steps().get(rank - 1);
        if (!snapshot.participants().contains(definition.agentId())) {
            failRun(run, STEP_NOT_RUNNABLE, "The agent of step " + rank + " is not a participant of this workflow.", now);
            return;
        }
        Map<Integer, String> summaries = new HashMap<>();
        Map<Integer, String> names = new HashMap<>();
        for (WorkflowStep done : steps.findByOrganizationIdAndRunIdOrderByStepRankAscAttemptAsc(org, run.getId())) {
            if (done.getStatus() == WorkflowStepStatus.SUCCEEDED) {
                summaries.put(done.getStepRank(), done.getResultSummary());
                names.put(done.getStepRank(), done.getName());
            }
        }
        long remainingSeconds = Math.max(0, Duration.between(now, run.getDeadlineAt()).getSeconds());
        String task = WorkflowText.composeTask(run.getObjective(), rank, run.getTotalSteps(), definition.name(),
                definition.instruction(), definition.inputsFrom(), summaries, names,
                run.getTokenBudget() - run.getTokensUsed(), remainingSeconds);

        AgentExecution execution;
        try {
            execution = executionService.launch(org, definition.agentId(), run.getRequestedBy(), task);
        } catch (CapacityExceededException e) {
            return; // the organization is at its open-execution limit: the next pass tries again, nothing is lost
        } catch (ConflictException | ResourceNotFoundException e) {
            failRun(run, STEP_NOT_RUNNABLE, WorkflowText.bounded("Step " + rank + " cannot run: " + e.getMessage(), 280), now);
            return;
        }
        WorkflowStep step = new WorkflowStep(UUID.randomUUID(), org, run.getId(), rank, attempt, definition.name(),
                definition.agentId(), execution.getId(), definition.inputsFrom().toString().replaceAll("[\\[\\] ]", ""), now);
        steps.save(step);
        run.scheduled(rank);
        run.markRunning(now);
        append(run, rank, WorkflowMessageKind.ASSIGNMENT, "Step " + rank + " (" + definition.name() + ") assigned: "
                + definition.instruction(), now);
        audit.record(org, run.getRequestedBy(), "workflow.step.started", "workflow", run.getId(), AgentSupport.details(
                "workflowRunId", run.getId(), "step", rank, "attempt", attempt, "agentId", definition.agentId(),
                "executionId", execution.getId()));
    }

    private void failRun(WorkflowRun run, String code, String message, Instant now) {
        run.fail(code, message, now);
        append(run, null, WorkflowMessageKind.STATUS, message, now);
        audit(run, "workflow.run.failed");
    }

    // ------------------------------------------------------------------ settling a step from its execution

    private void settle(WorkflowRun run, WorkflowStep step, AgentExecution execution, Instant now) {
        if (execution == null) {
            step.fail("EXECUTION_MISSING", "The execution of this step is missing.", now);
            return;
        }
        int tokens = (execution.getInputTokens() == null ? 0 : execution.getInputTokens())
                + (execution.getOutputTokens() == null ? 0 : execution.getOutputTokens());
        run.addTokens(tokens);
        if (execution.getStatus() == ExecutionStatus.SUCCEEDED) {
            String summary = WorkflowText.bounded(execution.getResult(), WorkflowText.MAX_SUMMARY);
            step.succeed(summary, now);
            append(run, step.getStepRank(), WorkflowMessageKind.RESULT,
                    "Step " + step.getStepRank() + " (" + step.getName() + ") result: " + summary, now);
            audit.record(run.getOrganizationId(), null, "workflow.step.succeeded", "workflow", run.getId(), AgentSupport.details(
                    "workflowRunId", run.getId(), "step", step.getStepRank(), "executionId", execution.getId()));
        } else {
            String code = execution.getErrorCode() == null ? "EXECUTION_FAILED" : execution.getErrorCode();
            String message = WorkflowText.bounded(execution.getErrorMessage(), 280);
            if (CANCELLED_CODE.equals(code)) {
                step.cancelled(code, message, now);
            } else {
                step.fail(code, message, now);
            }
            append(run, step.getStepRank(), WorkflowMessageKind.STATUS,
                    "Step " + step.getStepRank() + " ended: " + code + ".", now);
            audit.record(run.getOrganizationId(), null, "workflow.step.failed", "workflow", run.getId(), AgentSupport.details(
                    "workflowRunId", run.getId(), "step", step.getStepRank(), "executionId", execution.getId(), "errorCode", code));
        }
    }

    private void mirror(WorkflowRun run, WorkflowStep step, AgentExecution execution, Instant now) {
        boolean waiting = execution.getStatus() == ExecutionStatus.WAITING_APPROVAL;
        step.mirrorWaiting(waiting, now);
        if (run.getStatus() == WorkflowRunStatus.CANCEL_REQUESTED) {
            return; // a cancelling run keeps its status; the child is being stopped
        }
        if (waiting) {
            run.markWaitingApproval(now);
        } else {
            run.markRunning(now);
        }
    }

    // ------------------------------------------------------------------ cooperative cancellation

    /**
     * Ends the active child so that nothing new starts. An execution that has not started, or that waits for a person, is
     * ended at once (its approval is closed by the C2 mechanism). A RUNNING one with a tool call IN FLIGHT is given the
     * grace period to reach a known outcome; after it, the child is ended and the call is reported OUTCOME_UNKNOWN: a
     * started external effect is never presented as rolled back. The orchestrator learns of it by the 409 it receives
     * from its next call to the Core API.
     */
    private void requestStop(WorkflowRun run, AgentExecution execution, Instant now) {
        boolean toolInFlight = execution.getStatus() == ExecutionStatus.RUNNING
                && invocations.existsRunningByOrganizationAndExecution(run.getOrganizationId(), execution.getId());
        Instant cancelledAt = run.getCancelRequestedAt() == null ? now : run.getCancelRequestedAt();
        if (toolInFlight && now.isBefore(cancelledAt.plus(properties.cancelGrace()))) {
            return;
        }
        stopChild(execution, CANCELLED_CODE, toolInFlight
                ? "The workflow was cancelled while a tool call was running: its outcome is unknown."
                : "The workflow was cancelled.");
    }

    private void stopChild(AgentExecution execution, String code, String message) {
        if (execution != null && !execution.getStatus().isTerminal()) {
            coordinator.failIfOpen(execution.getOrganizationId(), execution.getId(), code, message);
        }
    }

    // ------------------------------------------------------------------ helpers

    private WorkflowStep latest(WorkflowRun run) {
        List<WorkflowStep> latest = steps.findLatestByOrganizationAndRun(run.getOrganizationId(), run.getId(), PageRequest.of(0, 1));
        return latest.isEmpty() ? null : latest.getFirst();
    }

    private void append(WorkflowRun run, Integer rank, WorkflowMessageKind kind, String body, Instant now) {
        messages.save(new WorkflowMessage(UUID.randomUUID(), run.getOrganizationId(), run.getId(), run.nextMessageSequence(),
                rank, kind, WorkflowText.bounded(body, WorkflowText.MAX_MESSAGE), now));
    }

    void appendStatus(WorkflowRun run, String body, Instant now) {
        append(run, null, WorkflowMessageKind.STATUS, body, now);
    }

    void appendObjective(WorkflowRun run, Instant now) {
        append(run, null, WorkflowMessageKind.OBJECTIVE, run.getObjective(), now);
    }

    private void audit(WorkflowRun run, String action) {
        audit.record(run.getOrganizationId(), run.getRequestedBy(), action, "workflow", run.getId(), AgentSupport.details(
                "workflowRunId", run.getId(), "definitionId", run.getDefinitionId(), "status", run.getStatus().name(),
                "errorCode", run.getErrorCode()));
    }
}
