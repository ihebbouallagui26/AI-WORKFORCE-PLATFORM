package com.aiworkforce.core.agent;

import com.aiworkforce.core.common.exception.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * One run of a workflow. It owns the workflow state: lifecycle, the next rank to schedule, the token budget used,
 * the deadline and the immutable snapshot of the definition it was started from. Every transition is guarded.
 */
@Entity
@Table(name = "workflow_runs")
public class WorkflowRun {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "definition_id", nullable = false, updatable = false)
    private UUID definitionId;

    @Column(name = "definition_version", nullable = false, updatable = false)
    private int definitionVersion;

    @Column(nullable = false, updatable = false)
    private String snapshot;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "request_id", updatable = false)
    private String requestId;

    @Column(nullable = false, updatable = false)
    private String objective;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkflowRunStatus status;

    @Column(name = "total_steps", nullable = false, updatable = false)
    private int totalSteps;

    @Column(name = "next_rank", nullable = false)
    private int nextRank;

    @Column(name = "message_sequence", nullable = false)
    private int messageSequence;

    @Column(name = "tokens_used", nullable = false)
    private int tokensUsed;

    @Column(name = "token_budget", nullable = false, updatable = false)
    private int tokenBudget;

    @Column(name = "deadline_at", nullable = false, updatable = false)
    private Instant deadlineAt;

    private String result;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "cancel_requested_at")
    private Instant cancelRequestedAt;

    @Column(name = "cancel_requested_by")
    private UUID cancelRequestedBy;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version")
    private long rowVersion;

    protected WorkflowRun() {
    }

    WorkflowRun(UUID id, WorkflowDefinition definition, String snapshot, UUID requestedBy, String requestId,
            String objective, int totalSteps, String correlationId, Instant now) {
        this.id = id;
        this.organizationId = definition.getOrganizationId();
        this.definitionId = definition.getId();
        this.definitionVersion = definition.getVersion();
        this.snapshot = snapshot;
        this.requestedBy = requestedBy;
        this.requestId = requestId;
        this.objective = objective;
        this.status = WorkflowRunStatus.PENDING;
        this.totalSteps = totalSteps;
        this.nextRank = 1;
        this.tokenBudget = definition.getTokenBudget();
        this.deadlineAt = now.plusSeconds(definition.getDeadlineSeconds());
        this.correlationId = correlationId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    private void transition(WorkflowRunStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new ConflictException("The workflow run is " + status + " and cannot become " + next + ".");
        }
        status = next;
        updatedAt = now;
        if (next.isTerminal()) {
            completedAt = now;
        }
    }

    void markRunning(Instant now) {
        if (status == WorkflowRunStatus.PENDING) {
            transition(WorkflowRunStatus.RUNNING, now);
            startedAt = now;
        } else if (status == WorkflowRunStatus.WAITING_APPROVAL) {
            transition(WorkflowRunStatus.RUNNING, now);
        }
    }

    void markWaitingApproval(Instant now) {
        if (status == WorkflowRunStatus.RUNNING) {
            transition(WorkflowRunStatus.WAITING_APPROVAL, now);
        }
    }

    /** Cooperative cancellation: nothing new may start; the scheduler settles the run. Idempotent. */
    void requestCancel(UUID user, Instant now) {
        if (status == WorkflowRunStatus.CANCEL_REQUESTED) {
            return;
        }
        transition(WorkflowRunStatus.CANCEL_REQUESTED, now);
        cancelRequestedAt = now;
        cancelRequestedBy = user;
    }

    void succeed(String finalResult, Instant now) {
        transition(WorkflowRunStatus.SUCCEEDED, now);
        result = finalResult;
    }

    void fail(String code, String safeMessage, Instant now) {
        transition(WorkflowRunStatus.FAILED, now);
        errorCode = code;
        errorMessage = safeMessage;
    }

    void timeout(Instant now) {
        transition(WorkflowRunStatus.TIMEOUT, now);
        errorCode = "WORKFLOW_TIMEOUT";
        errorMessage = "The workflow exceeded its total time limit.";
    }

    void cancelled(String lastResult, Instant now) {
        transition(WorkflowRunStatus.CANCELLED, now);
        errorCode = "WORKFLOW_CANCELLED";
        errorMessage = "The workflow was cancelled. Effects already produced by started steps are not undone.";
        result = lastResult;
    }

    void addTokens(int tokens) {
        tokensUsed += Math.max(0, tokens);
    }

    void scheduled(int rank) {
        nextRank = rank + 1;
    }

    int nextMessageSequence() {
        return ++messageSequence;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getDefinitionId() { return definitionId; }
    public int getDefinitionVersion() { return definitionVersion; }
    public String getSnapshot() { return snapshot; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getRequestId() { return requestId; }
    public String getObjective() { return objective; }
    public WorkflowRunStatus getStatus() { return status; }
    public int getTotalSteps() { return totalSteps; }
    public int getNextRank() { return nextRank; }
    public int getTokensUsed() { return tokensUsed; }
    public int getTokenBudget() { return tokenBudget; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public String getResult() { return result; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCancelRequestedAt() { return cancelRequestedAt; }
    public UUID getCancelRequestedBy() { return cancelRequestedBy; }
    public String getCorrelationId() { return correlationId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
