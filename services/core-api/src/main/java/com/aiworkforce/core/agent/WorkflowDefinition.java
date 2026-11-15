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
 * A named pipeline of ordered static steps with explicit limits. Every change bumps {@code version}; a run copies the
 * definition into its own snapshot when it starts, so changing or archiving a definition never affects a run.
 */
@Entity
@Table(name = "workflow_definitions")
public class WorkflowDefinition {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkflowDefinitionStatus status;

    @Column(nullable = false)
    private int version;

    /** The ordered steps as JSON text (validated by {@link WorkflowSpec}). */
    @Column(nullable = false)
    private String steps;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private WorkflowMode mode;

    /** The coordinator agent of a COORDINATED workflow (also an explicit participant); null for a PIPELINE. */
    @Column(name = "coordinator_agent_id")
    private UUID coordinatorAgentId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "failure_policy", nullable = false)
    private WorkflowFailurePolicy failurePolicy = WorkflowFailurePolicy.FAIL_FAST;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "recovery_mode", nullable = false)
    private WorkflowRecoveryMode recoveryMode = WorkflowRecoveryMode.NONE;

    @Column(name = "step_timeout_seconds")
    private Integer stepTimeoutSeconds;

    @Column(name = "max_steps", nullable = false)
    private int maxSteps;

    @Column(name = "max_retries_per_step", nullable = false)
    private int maxRetriesPerStep;

    @Column(name = "token_budget", nullable = false)
    private int tokenBudget;

    @Column(name = "deadline_seconds", nullable = false)
    private int deadlineSeconds;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Version
    @Column(name = "row_version")
    private long rowVersion;

    protected WorkflowDefinition() {
    }

    WorkflowDefinition(UUID id, UUID organizationId, String name, String description, int maxSteps, int maxRetriesPerStep,
            int tokenBudget, int deadlineSeconds, UUID createdBy, WorkflowMode mode, Instant now) {
        this.id = id;
        this.mode = mode;
        this.organizationId = organizationId;
        this.name = name;
        this.description = description;
        this.status = WorkflowDefinitionStatus.ACTIVE;
        this.version = 1;
        this.steps = "[]";
        this.maxSteps = maxSteps;
        this.maxRetriesPerStep = maxRetriesPerStep;
        this.tokenBudget = tokenBudget;
        this.deadlineSeconds = deadlineSeconds;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    private void requireActive() {
        if (status != WorkflowDefinitionStatus.ACTIVE) {
            throw new ConflictException("This workflow is archived and can no longer be modified.");
        }
    }

    void configure(String name, String description, Integer maxSteps, Integer maxRetriesPerStep, Integer tokenBudget,
            Integer deadlineSeconds, Instant now) {
        requireActive();
        if (name != null) {
            this.name = name;
        }
        if (description != null) {
            this.description = description;
        }
        if (maxSteps != null) {
            this.maxSteps = maxSteps;
        }
        if (maxRetriesPerStep != null) {
            this.maxRetriesPerStep = maxRetriesPerStep;
        }
        if (tokenBudget != null) {
            this.tokenBudget = tokenBudget;
        }
        if (deadlineSeconds != null) {
            this.deadlineSeconds = deadlineSeconds;
        }
        bump(now);
    }

    /** Failure handling (C3.3). A null argument leaves the value unchanged; stepTimeoutSeconds 0 removes the timeout. */
    void configureReliability(WorkflowFailurePolicy policy, WorkflowRecoveryMode recovery, Integer stepTimeout, Instant now) {
        requireActive();
        if (policy != null) {
            this.failurePolicy = policy;
        }
        if (recovery != null) {
            this.recoveryMode = recovery;
        }
        if (stepTimeout != null) {
            this.stepTimeoutSeconds = stepTimeout == 0 ? null : stepTimeout;
        }
        bump(now);
    }

    /** Chooses the coordinator of a COORDINATED workflow (null is only valid for a PIPELINE). */
    void changeCoordinator(UUID agentId, Instant now) {
        requireActive();
        this.coordinatorAgentId = agentId;
        bump(now);
    }

    void replaceSteps(String json, Instant now) {
        requireActive();
        this.steps = json;
        bump(now);
    }

    /** A change of the participant set is a change of the definition: a new version. */
    void participantsChanged(Instant now) {
        requireActive();
        bump(now);
    }

    void archive(Instant now) {
        requireActive();
        status = WorkflowDefinitionStatus.ARCHIVED;
        archivedAt = now;
        bump(now);
    }

    private void bump(Instant now) {
        version++;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public WorkflowDefinitionStatus getStatus() { return status; }
    public int getVersion() { return version; }
    public WorkflowMode getMode() { return mode; }
    public UUID getCoordinatorAgentId() { return coordinatorAgentId; }
    public WorkflowFailurePolicy getFailurePolicy() { return failurePolicy; }
    public WorkflowRecoveryMode getRecoveryMode() { return recoveryMode; }
    public Integer getStepTimeoutSeconds() { return stepTimeoutSeconds; }
    public String getSteps() { return steps; }
    public int getMaxSteps() { return maxSteps; }
    public int getMaxRetriesPerStep() { return maxRetriesPerStep; }
    public int getTokenBudget() { return tokenBudget; }
    public int getDeadlineSeconds() { return deadlineSeconds; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getArchivedAt() { return archivedAt; }
}
