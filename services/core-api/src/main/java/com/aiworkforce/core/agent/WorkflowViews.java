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
    public record StepDefinitionView(int rank, String name, UUID agentId, String instruction, List<Integer> inputsFrom) {
    }

    @Schema(name = "WorkflowParticipant")
    public record ParticipantView(UUID agentId, String agentName, String agentStatus, UUID addedBy, Instant addedAt) {
    }

    @Schema(name = "WorkflowDefinition")
    public record DefinitionView(UUID id, UUID organizationId, String name, String description, WorkflowDefinitionStatus status,
            int version, List<ParticipantView> participants, List<StepDefinitionView> steps, int maxSteps,
            int maxRetriesPerStep, int tokenBudget, int deadlineSeconds, UUID createdBy, Instant createdAt,
            Instant updatedAt, Instant archivedAt) {

        static DefinitionView of(WorkflowDefinition d, List<WorkflowParticipant> participants, Map<UUID, Agent> agents) {
            List<ParticipantView> people = participants.stream()
                    .sorted(java.util.Comparator.comparing(WorkflowParticipant::getAddedAt).thenComparing(WorkflowParticipant::getId))
                    .map(p -> new ParticipantView(p.getAgentId(), agents.containsKey(p.getAgentId()) ? agents.get(p.getAgentId()).getName() : null,
                            agents.containsKey(p.getAgentId()) ? agents.get(p.getAgentId()).getStatus().name() : null,
                            p.getAddedBy(), p.getAddedAt()))
                    .toList();
            List<StepDefinitionView> stepViews = WorkflowSpec.parseSteps(d.getSteps()).stream()
                    .map(s -> new StepDefinitionView(s.rank(), s.name(), s.agentId(), s.instruction(), s.inputsFrom())).toList();
            return new DefinitionView(d.getId(), d.getOrganizationId(), d.getName(), d.getDescription(), d.getStatus(), d.getVersion(),
                    people, stepViews, d.getMaxSteps(), d.getMaxRetriesPerStep(), d.getTokenBudget(), d.getDeadlineSeconds(),
                    d.getCreatedBy(), d.getCreatedAt(), d.getUpdatedAt(), d.getArchivedAt());
        }
    }

    @Schema(name = "WorkflowRun")
    public record RunView(UUID id, UUID organizationId, UUID definitionId, int definitionVersion, WorkflowRunStatus status,
            UUID requestedBy, String objective, int totalSteps, int nextRank, int tokensUsed, int tokenBudget,
            Instant deadlineAt, String result, String errorCode, String errorMessage, Instant cancelRequestedAt,
            Instant createdAt, Instant startedAt, Instant completedAt) {

        static RunView of(WorkflowRun r) {
            return new RunView(r.getId(), r.getOrganizationId(), r.getDefinitionId(), r.getDefinitionVersion(), r.getStatus(),
                    r.getRequestedBy(), r.getObjective(), r.getTotalSteps(), r.getNextRank(), r.getTokensUsed(), r.getTokenBudget(),
                    r.getDeadlineAt(), r.getResult(), r.getErrorCode(), r.getErrorMessage(), r.getCancelRequestedAt(),
                    r.getCreatedAt(), r.getStartedAt(), r.getCompletedAt());
        }
    }

    @Schema(name = "WorkflowStep")
    public record StepView(UUID id, UUID runId, int rank, int attempt, String name, UUID agentId, String agentName, String role,
            UUID executionId, WorkflowStepStatus status, String inputsFrom, String resultSummary, String errorCode,
            String errorMessage, Instant createdAt, Instant startedAt, Instant completedAt) {

        static StepView of(WorkflowStep s, String agentName) {
            return new StepView(s.getId(), s.getRunId(), s.getStepRank(), s.getAttempt(), s.getName(), s.getAgentId(), agentName,
                    s.getRole(), s.getExecutionId(), s.getStatus(), s.getInputRanks(), s.getResultSummary(), s.getErrorCode(),
                    s.getErrorMessage(), s.getCreatedAt(), s.getStartedAt(), s.getCompletedAt());
        }
    }

    @Schema(name = "WorkflowMessage")
    public record MessageView(UUID id, int sequence, Integer stepRank, WorkflowMessageKind kind, String body, Instant createdAt) {

        static MessageView of(WorkflowMessage m) {
            return new MessageView(m.getId(), m.getSequence(), m.getStepRank(), m.getKind(), m.getBody(), m.getCreatedAt());
        }
    }
}
