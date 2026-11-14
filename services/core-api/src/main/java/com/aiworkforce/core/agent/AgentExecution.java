package com.aiworkforce.core.agent;

import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.provider.ProviderType;
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
 * One supervised run of one agent. The only place where its status changes is this class, which
 * enforces the state machine: an illegal or duplicate transition is a {@link ConflictException}.
 * No credential is ever stored here, only the id of the connection that was used.
 */
@Entity
@Table(name = "agent_executions")
public class AgentExecution {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(nullable = false, updatable = false)
    private String task;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, updatable = false)
    private ProviderType providerType;

    @Column(name = "connection_id", nullable = false, updatable = false)
    private UUID connectionId;

    @Column(name = "model_id", nullable = false)
    private String modelId;

    private String result;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(name = "credential_resolved_at")
    private Instant credentialResolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected AgentExecution() {
    }

    AgentExecution(UUID id, UUID organizationId, UUID agentId, UUID requestedBy, String task, ProviderType providerType,
            UUID connectionId, String modelId, String correlationId, Instant now) {
        this.id = id;
        this.organizationId = organizationId;
        this.agentId = agentId;
        this.requestedBy = requestedBy;
        this.task = task;
        this.status = ExecutionStatus.PENDING;
        this.providerType = providerType;
        this.connectionId = connectionId;
        this.modelId = modelId;
        this.correlationId = correlationId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    private void transition(ExecutionStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new ConflictException("The execution is " + status + " and cannot become " + next + ".");
        }
        status = next;
        updatedAt = now;
    }

    /** The orchestrator picked the execution up. Only a PENDING execution can start, exactly once. */
    void start(Instant now) {
        transition(ExecutionStatus.RUNNING, now);
        startedAt = now;
    }

    /** The provider credential may be handed to the orchestrator once per execution, while it is RUNNING. */
    void markCredentialResolved(Instant now) {
        if (status != ExecutionStatus.RUNNING) {
            throw new ConflictException("The execution is not running.");
        }
        if (credentialResolvedAt != null) {
            throw new ConflictException("The credential was already issued for this execution.");
        }
        credentialResolvedAt = now;
        updatedAt = now;
    }

    void succeed(String result, Integer inputTokens, Integer outputTokens, String model, Instant now) {
        transition(ExecutionStatus.SUCCEEDED, now);
        this.result = result;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        if (model != null && !model.isBlank()) {
            this.modelId = model;
        }
        completedAt = now;
    }

    void fail(String code, String safeMessage, Instant now) {
        transition(ExecutionStatus.FAILED, now);
        errorCode = code;
        errorMessage = safeMessage;
        completedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getAgentId() { return agentId; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getTask() { return task; }
    public ExecutionStatus getStatus() { return status; }
    public ProviderType getProviderType() { return providerType; }
    public UUID getConnectionId() { return connectionId; }
    public String getModelId() { return modelId; }
    public String getResult() { return result; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public String getCorrelationId() { return correlationId; }
    public Instant getCredentialResolvedAt() { return credentialResolvedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
