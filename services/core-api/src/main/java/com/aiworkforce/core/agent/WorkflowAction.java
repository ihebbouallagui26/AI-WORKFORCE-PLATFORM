package com.aiworkforce.core.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A platform-native action of a coordinator turn ({@code workflow.delegate} or {@code workflow.finish}). It is not a tool
 * invocation and is never associated with an MCP server. It is idempotent per (coordinator execution, provider
 * {@code tool_call_id}) and is persisted BEFORE the next step is created: a replayed request returns the stored outcome.
 */
@Entity
@Table(name = "workflow_actions")
public class WorkflowAction {

    public static final String DELEGATE = "workflow.delegate";
    public static final String FINISH = "workflow.finish";

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "step_id", nullable = false, updatable = false)
    private UUID stepId;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "tool_call_id", nullable = false, updatable = false)
    private String toolCallId;

    @Column(nullable = false, updatable = false)
    private String action;

    @Column(nullable = false, updatable = false)
    private String status;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false, updatable = false)
    private String message;

    @Column(name = "arguments_hash", nullable = false, updatable = false)
    private String argumentsHash;

    @Column(name = "participant_ref", updatable = false)
    private String participantRef;

    @Column(name = "worker_agent_id", updatable = false)
    private UUID workerAgentId;

    @Column(updatable = false)
    private String task;

    @Column(name = "task_hash", updatable = false)
    private String taskHash;

    @Column(name = "input_ranks", nullable = false, updatable = false)
    private String inputRanks;

    @Column(updatable = false)
    private String result;

    /** The only field that changes: the worker step created from an accepted delegation. */
    @Column(name = "result_step_id")
    private UUID resultStepId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "row_version")
    private long rowVersion;

    protected WorkflowAction() {
    }

    WorkflowAction(UUID id, WorkflowStep step, String toolCallId, String action, boolean accepted, String code, String message,
            String argumentsHash, String participantRef, UUID workerAgentId, String task, String taskHash, String inputRanks,
            String result, Instant now) {
        this.id = id;
        this.organizationId = step.getOrganizationId();
        this.runId = step.getRunId();
        this.stepId = step.getId();
        this.executionId = step.getExecutionId();
        this.toolCallId = toolCallId;
        this.action = action;
        this.status = accepted ? "ACCEPTED" : "REJECTED";
        this.code = code;
        this.message = message;
        this.argumentsHash = argumentsHash;
        this.participantRef = participantRef;
        this.workerAgentId = workerAgentId;
        this.task = task;
        this.taskHash = taskHash;
        this.inputRanks = inputRanks == null ? "" : inputRanks;
        this.result = result;
        this.createdAt = now;
    }

    void linkStep(UUID workerStepId) {
        this.resultStepId = workerStepId;
    }

    public boolean isAccepted() {
        return "ACCEPTED".equals(status);
    }

    public boolean isDelegate() {
        return DELEGATE.equals(action);
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getRunId() { return runId; }
    public UUID getStepId() { return stepId; }
    public UUID getExecutionId() { return executionId; }
    public String getToolCallId() { return toolCallId; }
    public String getAction() { return action; }
    public String getStatus() { return status; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public String getArgumentsHash() { return argumentsHash; }
    public String getParticipantRef() { return participantRef; }
    public UUID getWorkerAgentId() { return workerAgentId; }
    public String getTask() { return task; }
    public String getTaskHash() { return taskHash; }
    public String getInputRanks() { return inputRanks; }
    public String getResult() { return result; }
    public UUID getResultStepId() { return resultStepId; }
    public Instant getCreatedAt() { return createdAt; }
}
