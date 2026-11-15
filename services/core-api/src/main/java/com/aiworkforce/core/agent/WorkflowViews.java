package com.aiworkforce.core.agent;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read models of workflows. They never carry a credential, the immutable snapshot of a run, or the composed task given to a
 * worker: only bounded, secret-masked summaries.
 */
public final class WorkflowViews {

    private WorkflowViews() {
    }

    @Schema(name = "WorkflowStepDefinition")
    public record StepDefinitionView(int rank, String name, UUID agentId, String instruction, List<Integer> inputsFrom, Integer stage) {
    }

    @Schema(name = "WorkflowParticipant")
    public record ParticipantView(UUID agentId, String agentName, String agentStatus, boolean coordinator, UUID addedBy, Instant addedAt) {
    }

    @Schema(name = "WorkflowDefinition")
    public record DefinitionView(UUID id, UUID organizationId, String name, String description, WorkflowDefinitionStatus status,
            WorkflowMode mode, UUID coordinatorAgentId, int version, List<ParticipantView> participants, List<StepDefinitionView> steps, int maxSteps,
            int maxRetriesPerStep, int tokenBudget, int deadlineSeconds, WorkflowFailurePolicy failurePolicy,
            WorkflowRecoveryMode recoveryMode, Integer stepTimeoutSeconds, int maxParallelWorkers, UUID createdBy, Instant createdAt,
            Instant updatedAt, Instant archivedAt) {

        static DefinitionView of(WorkflowDefinition d, List<WorkflowParticipant> participants, Map<UUID, Agent> agents) {
            List<ParticipantView> people = participants.stream()
                    .sorted(java.util.Comparator.comparing(WorkflowParticipant::getAddedAt).thenComparing(WorkflowParticipant::getId))
                    .map(p -> new ParticipantView(p.getAgentId(), agents.containsKey(p.getAgentId()) ? agents.get(p.getAgentId()).getName() : null,
                            agents.containsKey(p.getAgentId()) ? agents.get(p.getAgentId()).getStatus().name() : null,
                            p.getAgentId().equals(d.getCoordinatorAgentId()), p.getAddedBy(), p.getAddedAt()))
                    .toList();
            List<StepDefinitionView> stepViews = WorkflowSpec.parseSteps(d.getSteps()).stream()
                    .map(s -> new StepDefinitionView(s.rank(), s.name(), s.agentId(), s.instruction(), s.inputsFrom(), s.stage())).toList();
            return new DefinitionView(d.getId(), d.getOrganizationId(), d.getName(), d.getDescription(), d.getStatus(), d.getMode(),
                    d.getCoordinatorAgentId(), d.getVersion(), people, stepViews, d.getMaxSteps(), d.getMaxRetriesPerStep(), d.getTokenBudget(), d.getDeadlineSeconds(),
                    d.getFailurePolicy(), d.getRecoveryMode(), d.getStepTimeoutSeconds(), d.getMaxParallelWorkers(), d.getCreatedBy(), d.getCreatedAt(), d.getUpdatedAt(), d.getArchivedAt());
        }
    }

    @Schema(name = "WorkflowRun")
    public record RunView(UUID id, UUID organizationId, UUID definitionId, int definitionVersion, WorkflowMode mode, int maxParallelWorkers, WorkflowRunStatus status,
            UUID requestedBy, String objective, int totalSteps, int nextRank, int tokensUsed, int tokenBudget,
            Instant deadlineAt, String result, String errorCode, String errorMessage, Instant cancelRequestedAt,
            Instant createdAt, Instant startedAt, Instant completedAt) {

        static RunView of(WorkflowRun r) {
            return new RunView(r.getId(), r.getOrganizationId(), r.getDefinitionId(), r.getDefinitionVersion(), r.getMode(), r.getMaxParallelWorkers(), r.getStatus(),
                    r.getRequestedBy(), r.getObjective(), r.getTotalSteps(), r.getNextRank(), r.getTokensUsed(), r.getTokenBudget(),
                    r.getDeadlineAt(), r.getResult(), r.getErrorCode(), r.getErrorMessage(), r.getCancelRequestedAt(),
                    r.getCreatedAt(), r.getStartedAt(), r.getCompletedAt());
        }
    }

    @Schema(name = "WorkflowStep")
    public record StepView(UUID id, UUID runId, int rank, int attempt, String name, UUID agentId, String agentName, String role,
            UUID executionId, WorkflowStepStatus status, String inputsFrom, String resultSummary, String errorCode,
            String errorMessage, Instant createdAt, Instant startedAt, Instant completedAt, String launchReason,
            FailureCategory failureCategory, Boolean retrySafe, Instant nextAttemptAt, Instant deadlineAt, Integer groupNo) {

        static StepView of(WorkflowStep s, String agentName) {
            return new StepView(s.getId(), s.getRunId(), s.getStepRank(), s.getAttempt(), s.getName(), s.getAgentId(), agentName,
                    s.getRole(), s.getExecutionId(), s.getStatus(), s.getInputRanks(), s.getResultSummary(), s.getErrorCode(),
                    s.getErrorMessage(), s.getCreatedAt(), s.getStartedAt(), s.getCompletedAt(), s.getLaunchReason(),
                    s.getFailureCategory(), s.getRetrySafe(), s.getNextAttemptAt(), s.getDeadlineAt(), s.getGroupNo());
        }
    }

    @Schema(name = "WorkflowMessage")
    public record MessageView(UUID id, int sequence, Integer stepRank, WorkflowMessageKind kind, String body, Instant createdAt) {

        static MessageView of(WorkflowMessage m) {
            return new MessageView(m.getId(), m.getSequence(), m.getStepRank(), m.getKind(), m.getBody(), m.getCreatedAt());
        }
    }

    @Schema(name = "WorkflowAttemptUsage")
    public record AttemptUsageView(int rank, int attempt, String role, String launchReason, WorkflowStepStatus status, String errorCode,
            FailureCategory failureCategory, Boolean retrySafe, UUID executionId, Long durationMs, long inputTokens, long outputTokens,
            Integer groupNo, Long queueWaitMs) {
    }

    /** One group of workers that ran (or run) concurrently: a pipeline stage or the delegations of one coordinator turn. */
    @Schema(name = "WorkflowGroup")
    public record GroupView(int groupNo, String status, int workers, int queued, int running, int waitingApproval, int succeeded,
            int failed, int cancelled, Instant firstDispatchedAt, Instant finishedAt, int peakConcurrency) {
    }

    /** Operational view of one run: durations, attempts, failures, tokens (not money: no reliable price data) and recovery state. */
    @Schema(name = "WorkflowRunSummary")
    public record RunSummaryView(UUID runId, WorkflowMode mode, WorkflowRunStatus status, WorkflowFailurePolicy failurePolicy,
            Instant createdAt, Instant startedAt, Instant completedAt, Instant deadlineAt, Long durationMs, int attempts,
            int automaticRetries, int manualRetries, int failedAttempts, Map<String, Integer> failuresByCategory,
            long inputTokens, long outputTokens, long coordinatorTokens, long workerTokens, int tokenBudget, long tokensRemaining,
            Instant nextRetryAt, boolean waitingRecovery, String errorCode, List<AttemptUsageView> attemptList,
            int maxParallelWorkers, int peakConcurrency, long reservedTokens, List<GroupView> groups) {
    }

    @Schema(name = "WorkflowAction")
    public record ActionView(UUID id, UUID stepId, UUID executionId, String toolCallId, String action, String status, String code,
            String message, String participantRef, String task, String inputRanks, UUID resultStepId, Instant createdAt) {

        static ActionView of(WorkflowAction a) {
            return new ActionView(a.getId(), a.getStepId(), a.getExecutionId(), a.getToolCallId(), a.getAction(), a.getStatus(),
                    a.getCode(), a.getMessage(), a.getParticipantRef(), a.getTask(), a.getInputRanks(), a.getResultStepId(), a.getCreatedAt());
        }
    }
}
