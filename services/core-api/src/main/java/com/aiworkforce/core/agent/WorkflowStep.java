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
 * One step of a run = exactly ONE existing {@code agent_executions} row. The step only adds what the workflow
 * needs (rank, attempt, role, bounded result summary); the execution keeps its own lifecycle, tools and approvals.
 */
@Entity
@Table(name = "workflow_steps")
public class WorkflowStep {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "step_rank", nullable = false, updatable = false)
    private int stepRank;

    @Column(nullable = false, updatable = false)
    private int attempt;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(nullable = false, updatable = false)
    private String role;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkflowStepStatus status;

    @Column(name = "input_ranks", nullable = false, updatable = false)
    private String inputRanks;

    @Column(name = "result_summary")
    private String resultSummary;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "launch_reason", nullable = false, updatable = false)
    private String launchReason = "INITIAL";

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "failure_category")
    private FailureCategory failureCategory;

    @Column(name = "retry_safe")
    private Boolean retrySafe;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "deadline_at", updatable = false)
    private Instant deadlineAt;

    @Column(name = "retry_requested_by")
    private UUID retryRequestedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version")
    private long rowVersion;

    protected WorkflowStep() {
    }

    WorkflowStep(UUID id, UUID organizationId, UUID runId, int stepRank, int attempt, String name, UUID agentId,
            UUID executionId, String inputRanks, Instant now) {
        this(id, organizationId, runId, stepRank, attempt, name, agentId, executionId, inputRanks, "WORKER", now);
    }

    WorkflowStep(UUID id, UUID organizationId, UUID runId, int stepRank, int attempt, String name, UUID agentId,
            UUID executionId, String inputRanks, String role, Instant now) {
        this.id = id;
        this.organizationId = organizationId;
        this.runId = runId;
        this.stepRank = stepRank;
        this.attempt = attempt;
        this.name = name;
        this.agentId = agentId;
        this.role = role;
        this.executionId = executionId;
        this.status = WorkflowStepStatus.RUNNING;
        this.inputRanks = inputRanks;
        this.createdAt = now;
        this.startedAt = now;
        this.updatedAt = now;
    }

    /** How this attempt was launched and when it must end (null = only the workflow deadline applies). */
    void launchedAs(String reason, Instant stepDeadline) {
        this.launchReason = reason;
        this.deadlineAt = stepDeadline;
    }

    /** Records the classification of a failed attempt (Core API decision data; never a model output). */
    void classified(WorkflowFailureClassifier.Failure failure) {
        this.failureCategory = failure.category();
        this.retrySafe = failure.retrySafe();
    }

    /** The next attempt of this rank is due at {@code at} (durable: it survives a restart). */
    void retryDueAt(Instant at, UUID requestedBy, Instant now) {
        this.nextAttemptAt = at;
        this.retryRequestedBy = requestedBy;
        this.updatedAt = now;
    }

    void mirrorWaiting(boolean waiting, Instant now) {
        if (status.isFinal()) {
            return;
        }
        WorkflowStepStatus next = waiting ? WorkflowStepStatus.WAITING_APPROVAL : WorkflowStepStatus.RUNNING;
        if (status != next) {
            status = next;
            updatedAt = now;
        }
    }

    private void finish(WorkflowStepStatus next, Instant now) {
        if (status.isFinal()) {
            throw new ConflictException("The workflow step is already " + status + ".");
        }
        status = next;
        completedAt = now;
        updatedAt = now;
    }

    void succeed(String summary, Instant now) {
        finish(WorkflowStepStatus.SUCCEEDED, now);
        resultSummary = summary;
    }

    void fail(String code, String message, Instant now) {
        finish(WorkflowStepStatus.FAILED, now);
        errorCode = code;
        errorMessage = message;
    }

    void cancelled(String code, String message, Instant now) {
        finish(WorkflowStepStatus.CANCELLED, now);
        errorCode = code;
        errorMessage = message;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getLaunchReason() { return launchReason; }
    public FailureCategory getFailureCategory() { return failureCategory; }
    public Boolean getRetrySafe() { return retrySafe; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public UUID getRetryRequestedBy() { return retryRequestedBy; }
    public UUID getRunId() { return runId; }
    public int getStepRank() { return stepRank; }
    public int getAttempt() { return attempt; }
    public String getName() { return name; }
    public UUID getAgentId() { return agentId; }
    public String getRole() { return role; }
    public UUID getExecutionId() { return executionId; }
    public WorkflowStepStatus getStatus() { return status; }
    public String getInputRanks() { return inputRanks; }
    public String getResultSummary() { return resultSummary; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
