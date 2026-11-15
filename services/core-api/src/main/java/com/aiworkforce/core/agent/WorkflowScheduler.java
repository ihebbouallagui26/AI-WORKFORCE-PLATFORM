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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sequential scheduler of workflows. It is the ONLY place where a workflow moves: one step at a time, each step a real
 * child execution launched through the C1 path with the step agent's own identity, grants and provider.
 *
 * <p>PIPELINE (C3.1): the next static step. COORDINATED (C3.2): a coordinator turn is an execution of the coordinator agent;
 * its platform action (recorded by {@link WorkflowActionService} BEFORE anything is created) tells this scheduler whether to
 * launch a worker or to finish, and a finished worker always leads to the next coordinator turn. Neither the coordinator nor
 * the orchestrator ever creates a step: only this class does, after the Core API validated the action.
 *
 * <p>{@link #advance(WorkflowRun)} is idempotent and always runs with the run row locked, so a repeated pass, a pass after a
 * restart, or two passes at once can never schedule a step twice (a unique index backs this up). All the state it needs is in
 * PostgreSQL: nothing is lost when the Core API or the orchestrator restarts.
 */
@Component
class WorkflowScheduler {

    static final String STEP_FAILED = "STEP_FAILED";
    static final String STEP_NOT_RUNNABLE = "STEP_NOT_RUNNABLE";
    static final String TOKEN_BUDGET_EXCEEDED = "TOKEN_BUDGET_EXCEEDED";
    static final String REQUESTER_NOT_AUTHORIZED = "REQUESTER_NOT_AUTHORIZED";
    static final String COORDINATOR_TURN_LIMIT = "COORDINATOR_TURN_LIMIT";
    static final String COORDINATOR_NO_ACTION = "COORDINATOR_NO_ACTION";
    static final String STEP_LIMIT = "STEP_LIMIT";
    static final String STEP_TIMEOUT = "STEP_TIMEOUT";
    static final String PARTIAL_FAILURE = "PARTIAL_FAILURE";
    static final String CANCELLED_CODE = "WORKFLOW_CANCELLED";
    static final String TIMEOUT_CODE = "WORKFLOW_TIMEOUT";
    static final String COORDINATOR = "COORDINATOR";
    /** Manual retries per run (the database enforces the same bound). */
    static final int MAX_MANUAL_RETRIES = 3;
    /** A coordinated run always keeps this many tokens in reserve for a decision: below it no new turn or worker starts. */
    static final int MIN_TOKEN_RESERVE = 500;

    private final WorkflowRunRepository runs;
    private final WorkflowStepRepository steps;
    private final WorkflowMessageRepository messages;
    private final WorkflowActionRepository actions;
    private final ExecutionRepository executions;
    private final ToolInvocationRepository invocations;
    private final ExecutionService executionService;
    private final ExecutionCoordinator coordinator;
    private final TenantAccessService access;
    private final AuditLog audit;
    private final WorkflowProperties properties;
    private final Clock clock;

    WorkflowScheduler(WorkflowRunRepository runs, WorkflowStepRepository steps, WorkflowMessageRepository messages,
            WorkflowActionRepository actions, ExecutionRepository executions, ToolInvocationRepository invocations,
            ExecutionService executionService, ExecutionCoordinator coordinator, TenantAccessService access, AuditLog audit,
            WorkflowProperties properties, Clock clock) {
        this.runs = runs;
        this.steps = steps;
        this.messages = messages;
        this.actions = actions;
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
            if (current.getDeadlineAt() != null && !now.isBefore(current.getDeadlineAt()) && execution != null
                    && !execution.getStatus().isTerminal()) {
                // The step exceeded ITS time limit: the child is ended (a tool call in flight becomes OUTCOME_UNKNOWN, never "cancelled").
                stopChild(execution, STEP_TIMEOUT, "The step exceeded its time limit.");
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
        if (run.getStatus() == WorkflowRunStatus.WAITING_RECOVERY) {
            return; // a failed step waits for a person: nothing starts until a retry is requested (or the deadline / a cancel comes)
        }
        if (snapshot.coordinated()) {
            decideCoordinated(run, snapshot, latest(run), now);
        } else {
            decideNext(run, snapshot, latest(run), now);
        }
    }

    // ------------------------------------------------------------------ PIPELINE: what happens after a step

    private void decideNext(WorkflowRun run, WorkflowSpec.Snapshot snapshot, WorkflowStep last, Instant now) {
        if (last == null) {
            schedule(run, snapshot, 1, 1, now);
            return;
        }
        switch (last.getStatus()) {
            case SUCCEEDED -> {
                if (last.getStepRank() >= run.getTotalSteps()) {
                    finishPipeline(run, last, now);
                } else {
                    schedule(run, snapshot, last.getStepRank() + 1, 1, now);
                }
            }
            case FAILED -> afterFailure(run, snapshot, last, now);
            case CANCELLED -> {
                run.fail(STEP_FAILED, "Step " + last.getStepRank() + " was cancelled.", now);
                audit(run, "workflow.run.failed");
            }
            default -> {
                // RUNNING / WAITING_APPROVAL are handled before: nothing to decide.
            }
        }
    }

    // ------------------------------------------------------------------ failure handling (C3.3)

    /**
     * What happens after a step ended FAILED. The Core API decides, from the classification recorded at settle time:
     * a durable scheduled retry (backoff) comes first; then the workflow policy (CONTINUE), then manual recovery, else FAIL_FAST.
     */
    private void afterFailure(WorkflowRun run, WorkflowSpec.Snapshot snapshot, WorkflowStep last, Instant now) {
        boolean coordinatorStep = COORDINATOR.equals(last.getRole());
        if (last.getNextAttemptAt() != null) {
            if (now.isBefore(last.getNextAttemptAt())) {
                return; // backoff not over: the due time is in PostgreSQL, a restart does not lose it
            }
            relaunch(run, snapshot, last, coordinatorStep, now);
            return;
        }
        WorkflowFailureClassifier.Failure failure = new WorkflowFailureClassifier.Failure(
                last.getFailureCategory() == null ? FailureCategory.UNCLASSIFIED : last.getFailureCategory(), Boolean.TRUE.equals(last.getRetrySafe()));
        if (snapshot.policy() == WorkflowFailurePolicy.CONTINUE && !coordinatorStep && failure.continuable()) {
            continueAfter(run, snapshot, last, now);
            return;
        }
        if (snapshot.manualRecovery() && failure.manuallyRecoverable() && run.getManualRetries() < MAX_MANUAL_RETRIES) {
            run.waitRecovery(now);
            append(run, last.getStepRank(), WorkflowMessageKind.STATUS, "Step " + last.getStepRank() + " failed (" + failure.category()
                    + "): the workflow waits for a person to retry the step or cancel the run.", now);
            audit(run, "workflow.run.waiting-recovery");
            return;
        }
        failStep(run, last, now);
    }

    /** Launches the next attempt of the same rank (a NEW child execution; the failed one is kept). */
    private void relaunch(WorkflowRun run, WorkflowSpec.Snapshot snapshot, WorkflowStep last, boolean coordinatorStep, Instant now) {
        int rank = last.getStepRank();
        int attempt = last.getAttempt() + 1;
        if (coordinatorStep) {
            scheduleCoordinatorTurn(run, snapshot, false, rank, attempt, now);
        } else if (snapshot.coordinated()) {
            WorkflowAction delegation = delegationOf(run, rank);
            if (delegation == null) {
                failStep(run, last, now);
            } else {
                scheduleWorker(run, snapshot, delegation, rank, attempt, now);
            }
        } else {
            schedule(run, snapshot, rank, attempt, now);
        }
    }

    /** CONTINUE: the failure stays recorded and is reported to whoever decides next; it is never presented as a success. */
    private void continueAfter(WorkflowRun run, WorkflowSpec.Snapshot snapshot, WorkflowStep last, Instant now) {
        append(run, last.getStepRank(), WorkflowMessageKind.STATUS, "Step " + last.getStepRank() + " failed (" + last.getFailureCategory()
                + "): the CONTINUE policy keeps the failure and goes on.", now);
        audit.record(run.getOrganizationId(), null, "workflow.step.continued", "workflow", run.getId(), AgentSupport.details(
                "workflowRunId", run.getId(), "step", last.getStepRank(), "category", String.valueOf(last.getFailureCategory())));
        if (snapshot.coordinated()) {
            scheduleCoordinatorTurn(run, snapshot, false, run.getNextRank(), 1, now);
        } else if (last.getStepRank() >= run.getTotalSteps()) {
            finishPipeline(run, last, now);
        } else {
            schedule(run, snapshot, last.getStepRank() + 1, 1, now);
        }
    }

    /** End of a pipeline: a SUCCEEDED run only when every rank has a successful attempt; otherwise truthfully FAILED (partial). */
    private void finishPipeline(WorkflowRun run, WorkflowStep last, Instant now) {
        Set<Integer> done = new java.util.HashSet<>();
        for (WorkflowStep s : steps.findByOrganizationIdAndRunIdOrderByStepRankAscAttemptAsc(run.getOrganizationId(), run.getId())) {
            if (s.getStatus() == WorkflowStepStatus.SUCCEEDED) {
                done.add(s.getStepRank());
            }
        }
        int failed = run.getTotalSteps() - done.size();
        if (failed > 0) {
            failRun(run, PARTIAL_FAILURE, failed + " of " + run.getTotalSteps() + " steps failed: the results of the others are kept.", now);
            return;
        }
        String finalResult = executions.findByOrganizationIdAndId(run.getOrganizationId(), last.getExecutionId())
                .map(AgentExecution::getResult).orElse(last.getResultSummary());
        run.succeed(WorkflowText.bounded(finalResult, properties.maxResultChars()), now);
        append(run, null, WorkflowMessageKind.STATUS, "The workflow succeeded.", now);
        audit(run, "workflow.run.succeeded");
    }

    /** Backoff before attempt N+1: base * 2^(N-1), capped, with +/-20% jitter, decided once and stored. */
    private Instant retryDue(int failedAttempt, Instant now) {
        long base = properties.retryBaseDelay().toMillis();
        long max = properties.retryMaxDelay().toMillis();
        long delay = Math.min(max, base * (1L << Math.min(10, failedAttempt - 1)));
        double jitter = 0.8 + java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 0.4;
        return now.plusMillis(Math.min(max, (long) (delay * jitter)));
    }

    /** FAIL_FAST: nothing after a failed step ever starts. OUTCOME_UNKNOWN is never retried. */
    private void failStep(WorkflowRun run, WorkflowStep last, Instant now) {
        run.fail(STEP_FAILED, "Step " + last.getStepRank() + " (" + WorkflowText.bounded(last.getName(), 80)
                + ") failed: " + (last.getErrorCode() == null ? "error" : last.getErrorCode()) + ".", now);
        append(run, last.getStepRank(), WorkflowMessageKind.STATUS, "The workflow failed at step " + last.getStepRank() + ".", now);
        audit(run, "workflow.run.failed");
    }

    private void schedule(WorkflowRun run, WorkflowSpec.Snapshot snapshot, int rank, int attempt, Instant now) {
        UUID org = run.getOrganizationId();
        if (run.getTokensUsed() >= run.getTokenBudget()) {
            failRun(run, TOKEN_BUDGET_EXCEEDED, "The token budget of the workflow is used up.", now);
            return;
        }
        if (!requesterStillAuthorized(run, now)) {
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
            } else if (done.getStatus() == WorkflowStepStatus.FAILED && !summaries.containsKey(done.getStepRank())) {
                // CONTINUE: a failed predecessor is reported AS a failure, never as a result.
                summaries.put(done.getStepRank(), "[This step FAILED (" + done.getFailureCategory() + "). No result is available.]");
                names.put(done.getStepRank(), done.getName());
            }
        }
        String task = WorkflowText.composeTask(run.getObjective(), rank, run.getTotalSteps(), definition.name(),
                definition.instruction(), definition.inputsFrom(), summaries, names,
                run.getTokenBudget() - run.getTokensUsed(), remainingSeconds(run, now));
        WorkflowStep step = launchStep(run, rank, attempt, definition.name(), definition.agentId(), task,
                definition.inputsFrom().toString().replaceAll("[\\[\\] ]", ""), "WORKER", now);
        if (step == null) {
            return;
        }
        append(run, rank, WorkflowMessageKind.ASSIGNMENT, "Step " + rank + " (" + definition.name() + ") assigned: "
                + definition.instruction(), now);
    }

    // ------------------------------------------------------------------ COORDINATED: coordinator turns and delegations

    private void decideCoordinated(WorkflowRun run, WorkflowSpec.Snapshot snapshot, WorkflowStep last, Instant now) {
        if (last == null) {
            scheduleCoordinatorTurn(run, snapshot, false, run.getNextRank(), 1, now);
            return;
        }
        boolean coordinatorStep = COORDINATOR.equals(last.getRole());
        switch (last.getStatus()) {
            case SUCCEEDED -> {
                if (coordinatorStep) {
                    afterCoordinatorTurn(run, snapshot, last, now);
                } else {
                    scheduleCoordinatorTurn(run, snapshot, false, run.getNextRank(), 1, now); // a worker result: decide again
                }
            }
            case FAILED -> afterFailure(run, snapshot, last, now); // FAIL_FAST: a failed coordinator turn creates no worker
            case CANCELLED -> {
                run.fail(STEP_FAILED, "Step " + last.getStepRank() + " was cancelled.", now);
                audit(run, "workflow.run.failed");
            }
            default -> {
                // live steps are handled before
            }
        }
    }

    /** The coordinator turn ended: what it decided is in the Core API's record of ITS accepted action, nothing else counts. */
    private void afterCoordinatorTurn(WorkflowRun run, WorkflowSpec.Snapshot snapshot, WorkflowStep turn, Instant now) {
        WorkflowAction action = actions.findAcceptedByOrganizationAndExecution(run.getOrganizationId(), turn.getExecutionId()).orElse(null);
        if (action == null) {
            // The turn ended without an accepted control action. One bounded correction; a second silent turn fails the run.
            if (previousTurnWithoutAction(run, turn)) {
                failRun(run, COORDINATOR_NO_ACTION, "The coordinator did not choose an action after being asked twice.", now);
                return;
            }
            append(run, turn.getStepRank(), WorkflowMessageKind.STATUS,
                    "The coordinator ended its turn without delegating or finishing: asking once more.", now);
            scheduleCoordinatorTurn(run, snapshot, true, run.getNextRank(), 1, now);
            return;
        }
        if (action.isDelegate()) {
            if (action.getResultStepId() == null) {
                scheduleWorker(run, snapshot, action, run.getNextRank(), 1, now);
            }
        } else {
            run.succeed(WorkflowText.bounded(action.getResult(), properties.maxResultChars()), now);
            append(run, null, WorkflowMessageKind.STATUS, "The coordinator finished the workflow.", now);
            audit(run, "workflow.run.succeeded");
        }
    }

    private boolean previousTurnWithoutAction(WorkflowRun run, WorkflowStep turn) {
        List<WorkflowStep> all = steps.findByOrganizationIdAndRunIdOrderByStepRankAscAttemptAsc(run.getOrganizationId(), run.getId());
        WorkflowStep before = null;
        for (WorkflowStep s : all) {
            if (s.getId().equals(turn.getId())) {
                break;
            }
            before = s;
        }
        return before != null && COORDINATOR.equals(before.getRole()) && before.getStatus() == WorkflowStepStatus.SUCCEEDED
                && actions.findAcceptedByOrganizationAndExecution(run.getOrganizationId(), before.getExecutionId()).isEmpty();
    }

    private void scheduleCoordinatorTurn(WorkflowRun run, WorkflowSpec.Snapshot snapshot, boolean corrective, int rank, int attempt, Instant now) {
        UUID org = run.getOrganizationId();
        List<WorkflowStep> all = steps.findByOrganizationIdAndRunIdOrderByStepRankAscAttemptAsc(org, run.getId());
        int turns = (int) all.stream().filter(s -> COORDINATOR.equals(s.getRole()) && s.getAttempt() == 1).count();
        if (attempt == 1 && turns >= snapshot.maxSteps() + 1) {
            failRun(run, COORDINATOR_TURN_LIMIT, "The coordinator used all its decision turns.", now);
            return;
        }
        if (!preflight(run, true, now)) {
            return;
        }
        List<String> results = new ArrayList<>();
        int workerSteps = 0;
        Map<Integer, WorkflowStep> latestByRank = new java.util.TreeMap<>();
        for (WorkflowStep s : all) {
            if (!COORDINATOR.equals(s.getRole())) {
                latestByRank.put(s.getStepRank(), s); // attempts are ordered: the last one of a rank is the one that counts
                if (s.getAttempt() == 1) {
                    workerSteps++;
                }
            }
        }
        for (WorkflowStep s : latestByRank.values()) {
            WorkflowSpec.Member member = snapshot.memberOf(s.getAgentId());
            String who = member == null ? "a worker" : member.ref() + " (" + member.name() + ")";
            if (s.getStatus() == WorkflowStepStatus.SUCCEEDED) {
                results.add("--- Step " + s.getStepRank() + " by " + who + " ---\n" + WorkflowText.bounded(s.getResultSummary(), 1500));
            } else if (s.getStatus() == WorkflowStepStatus.FAILED) {
                results.add("--- Step " + s.getStepRank() + " by " + who + " FAILED (" + s.getFailureCategory()
                        + (Boolean.TRUE.equals(s.getRetrySafe()) ? ", nothing external happened" : ", an external effect may have happened")
                        + ") ---\n" + WorkflowText.bounded(s.getErrorMessage(), 280));
            }
        }
        String task = WorkflowText.composeCoordinatorTask(run.getObjective(), snapshot.members(), results, turns + (attempt == 1 ? 1 : 0),
                snapshot.maxSteps() + 1, workerSteps, snapshot.maxSteps(), run.getTokenBudget() - run.getTokensUsed(),
                remainingSeconds(run, now), corrective);
        WorkflowSpec.Member self = snapshot.memberOf(snapshot.coordinatorAgentId());
        WorkflowStep step = launchStep(run, rank, attempt, "Coordinator turn " + (turns + (attempt == 1 ? 1 : 0)),
                snapshot.coordinatorAgentId(), task, "", COORDINATOR, now);
        if (step != null) {
            append(run, rank, WorkflowMessageKind.STATUS, "Coordinator turn started" + (corrective ? " (second ask)" : "") + ".", now);
        }
        if (self == null && step == null) {
            return;
        }
    }

    private void scheduleWorker(WorkflowRun run, WorkflowSpec.Snapshot snapshot, WorkflowAction action, int rank, int attempt, Instant now) {
        UUID org = run.getOrganizationId();
        if (!preflight(run, true, now)) {
            return;
        }
        List<WorkflowStep> all = steps.findByOrganizationIdAndRunIdOrderByStepRankAscAttemptAsc(org, run.getId());
        long workers = all.stream().filter(s -> !COORDINATOR.equals(s.getRole()) && s.getAttempt() == 1).count();
        if (attempt == 1 && workers >= snapshot.maxSteps()) {
            failRun(run, STEP_LIMIT, "The workflow reached its maximum number of worker steps.", now);
            return;
        }
        WorkflowSpec.Member member = snapshot.memberOf(action.getWorkerAgentId());
        if (member == null || member.coordinator()) {
            failRun(run, STEP_NOT_RUNNABLE, "The delegated agent is not a worker of this workflow.", now);
            return;
        }
        Map<Integer, String> inputs = new LinkedHashMap<>();
        for (String raw : action.getInputRanks().isBlank() ? new String[0] : action.getInputRanks().split(",")) {
            int from = Integer.parseInt(raw.strip());
            all.stream().filter(s -> s.getStepRank() == from && s.getStatus() == WorkflowStepStatus.SUCCEEDED).findFirst()
                    .ifPresent(s -> inputs.put(from, WorkflowText.bounded(s.getResultSummary(), 1500)));
        }
        String task = WorkflowText.composeDelegatedTask(run.getObjective(), member.name(), action.getTask(), inputs,
                run.getTokenBudget() - run.getTokensUsed(), remainingSeconds(run, now));
        WorkflowStep step = launchStep(run, rank, attempt, member.name(), member.agentId(), task, action.getInputRanks(), "WORKER", now);
        if (step == null) {
            return;
        }
        if (attempt == 1) {
            action.linkStep(step.getId());
        }
        append(run, rank, WorkflowMessageKind.ASSIGNMENT, "The coordinator delegated step " + rank + " to " + member.name() + ": "
                + action.getTask(), now);
    }

    /** The delegation that created the worker step of this rank (any attempt), if it is still recorded. */
    private WorkflowAction delegationOf(WorkflowRun run, int rank) {
        List<WorkflowStep> ofRank = steps.findByOrganizationIdAndRunIdOrderByStepRankAscAttemptAsc(run.getOrganizationId(), run.getId())
                .stream().filter(s -> s.getStepRank() == rank).toList();
        for (WorkflowAction a : actions.findByOrganizationIdAndRunIdOrderByCreatedAtAsc(run.getOrganizationId(), run.getId())) {
            if (a.isAccepted() && a.isDelegate() && a.getResultStepId() != null
                    && ofRank.stream().anyMatch(s -> s.getId().equals(a.getResultStepId()))) {
                return a;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ shared launching and checks

    /** Budget and authorization checks before anything new starts. Fails the run and returns false when they do not hold. */
    private boolean preflight(WorkflowRun run, boolean reserve, Instant now) {
        int remaining = run.getTokenBudget() - run.getTokensUsed();
        if (remaining <= 0 || (reserve && remaining < MIN_TOKEN_RESERVE)) {
            failRun(run, TOKEN_BUDGET_EXCEEDED, "The token budget of the workflow is used up.", now);
            return false;
        }
        return requesterStillAuthorized(run, now);
    }

    /** Authorization is checked again before EVERY step: workflow.execute never replaces agent.execute. */
    private boolean requesterStillAuthorized(WorkflowRun run, Instant now) {
        OrganizationAccess requester = access.findActiveAccess(run.getOrganizationId(), run.getRequestedBy()).orElse(null);
        if (requester == null || !requester.has(Permission.WORKFLOW_EXECUTE) || !requester.has(Permission.AGENT_EXECUTE)) {
            failRun(run, REQUESTER_NOT_AUTHORIZED, "The person who started the workflow can no longer run it.", now);
            return false;
        }
        return true;
    }

    private long remainingSeconds(WorkflowRun run, Instant now) {
        return Math.max(0, Duration.between(now, run.getDeadlineAt()).getSeconds());
    }

    /**
     * Creates the child execution (the C1 path: agent ACTIVE, provider, open-execution limit) and its step. Returns null when
     * nothing was created: the organization is at its execution limit (retried by the next pass) or the run was failed.
     */
    private WorkflowStep launchStep(WorkflowRun run, int rank, int attempt, String name, UUID agentId, String task, String inputRanks,
            String role, Instant now) {
        UUID org = run.getOrganizationId();
        AgentExecution execution;
        try {
            execution = executionService.launch(org, agentId, run.getRequestedBy(), task);
        } catch (CapacityExceededException e) {
            return null; // the organization is at its open-execution limit: the next pass tries again, nothing is lost
        } catch (ConflictException | ResourceNotFoundException e) {
            failRun(run, STEP_NOT_RUNNABLE, WorkflowText.bounded("Step " + rank + " cannot run: " + e.getMessage(), 280), now);
            return null;
        }
        String reason = "INITIAL";
        if (attempt > 1) {
            WorkflowStep before = steps.findByOrganizationIdAndRunIdOrderByStepRankAscAttemptAsc(org, run.getId()).stream()
                    .filter(s -> s.getStepRank() == rank && s.getAttempt() == attempt - 1).findFirst().orElse(null);
            reason = before != null && before.getRetryRequestedBy() != null ? "MANUAL_RETRY" : "AUTO_RETRY";
        }
        Integer stepTimeout = WorkflowSpec.parseSnapshot(run.getSnapshot()).stepTimeoutSeconds();
        Instant stepDeadline = stepTimeout == null ? null : java.util.Collections.min(List.of(now.plusSeconds(stepTimeout), run.getDeadlineAt()));
        WorkflowStep fresh = new WorkflowStep(UUID.randomUUID(), org, run.getId(), rank, attempt, name, agentId,
                execution.getId(), inputRanks, role, now);
        fresh.launchedAs(reason, stepDeadline);
        WorkflowStep step = steps.save(fresh);
        run.scheduled(rank);
        run.markRunning(now);
        audit.record(org, run.getRequestedBy(), "workflow.step.started", "workflow", run.getId(), AgentSupport.details(
                "workflowRunId", run.getId(), "step", rank, "attempt", attempt, "role", role, "agentId", agentId,
                "executionId", execution.getId(), "reason", reason));
        return step;
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
        run.addTokens(tokens); // coordinator turns and workers share the same workflow budget
        boolean coordinatorTurn = COORDINATOR.equals(step.getRole());
        if (execution.getStatus() == ExecutionStatus.SUCCEEDED) {
            String summary = WorkflowText.bounded(execution.getResult(), WorkflowText.MAX_SUMMARY);
            step.succeed(summary, now);
            append(run, step.getStepRank(), coordinatorTurn ? WorkflowMessageKind.STATUS : WorkflowMessageKind.RESULT,
                    coordinatorTurn ? "Coordinator turn " + step.getStepRank() + " ended."
                            : "Step " + step.getStepRank() + " (" + step.getName() + ") result: " + summary, now);
            audit.record(run.getOrganizationId(), null, "workflow.step.succeeded", "workflow", run.getId(), AgentSupport.details(
                    "workflowRunId", run.getId(), "step", step.getStepRank(), "role", step.getRole(), "executionId", execution.getId()));
        } else {
            String code = execution.getErrorCode() == null ? "EXECUTION_FAILED" : execution.getErrorCode();
            String message = WorkflowText.bounded(execution.getErrorMessage(), 280);
            if (CANCELLED_CODE.equals(code)) {
                step.cancelled(code, message, now);
            } else {
                step.fail(code, message, now);
            }
            WorkflowFailureClassifier.Failure failure = WorkflowFailureClassifier.classify(code,
                    invocations.existsStartedByOrganizationAndExecution(run.getOrganizationId(), execution.getId()),
                    // A call still RUNNING when its execution is over, or already closed as such, has an UNKNOWN outcome.
                    invocations.existsOutcomeUnknownByOrganizationAndExecution(run.getOrganizationId(), execution.getId())
                            || invocations.existsRunningByOrganizationAndExecution(run.getOrganizationId(), execution.getId()));
            step.classified(failure);
            scheduleAutomaticRetry(run, step, failure, now);
            append(run, step.getStepRank(), WorkflowMessageKind.STATUS,
                    "Step " + step.getStepRank() + " ended: " + code + ".", now);
            audit.record(run.getOrganizationId(), null, "workflow.step.failed", "workflow", run.getId(), AgentSupport.details(
                    "workflowRunId", run.getId(), "step", step.getStepRank(), "role", step.getRole(), "executionId", execution.getId(),
                    "errorCode", code));
        }
    }

    /**
     * Decides, at the moment a step fails, whether a bounded AUTOMATIC retry is due. Only a classified transient provider failure
     * before any external action started qualifies; never an unknown outcome, never an authorization failure, never after a
     * manual retry, never past the workflow deadline. The due time is stored on the failed step (durable).
     */
    private void scheduleAutomaticRetry(WorkflowRun run, WorkflowStep step, WorkflowFailureClassifier.Failure failure, Instant now) {
        WorkflowSpec.Snapshot snapshot = WorkflowSpec.parseSnapshot(run.getSnapshot());
        if (!failure.autoRetryable() || "MANUAL_RETRY".equals(step.getLaunchReason()) || step.getAttempt() > snapshot.maxRetriesPerStep()
                || run.getStatus() == WorkflowRunStatus.CANCEL_REQUESTED) {
            return;
        }
        Instant due = retryDue(step.getAttempt(), now);
        if (!due.isBefore(run.getDeadlineAt())) {
            append(run, step.getStepRank(), WorkflowMessageKind.STATUS, "Step " + step.getStepRank()
                    + " failed: no automatic retry, the retry delay would pass the workflow deadline.", now);
            return;
        }
        step.retryDueAt(due, null, now);
        append(run, step.getStepRank(), WorkflowMessageKind.STATUS, "Step " + step.getStepRank() + " failed with a transient provider error: "
                + "automatic retry " + step.getAttempt() + " of " + snapshot.maxRetriesPerStep() + " scheduled (a new execution).", now);
        audit.record(run.getOrganizationId(), null, "workflow.step.retry-scheduled", "workflow", run.getId(), AgentSupport.details(
                "workflowRunId", run.getId(), "step", step.getStepRank(), "attempt", step.getAttempt(), "dueAt", due.toString()));
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
