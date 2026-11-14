package com.aiworkforce.core.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One entry of the append-only journal of a run (the database refuses UPDATE and DELETE). Bounded and secret-masked. */
@Entity
@Table(name = "workflow_messages")
public class WorkflowMessage {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(nullable = false, updatable = false)
    private int sequence;

    @Column(name = "step_rank", updatable = false)
    private Integer stepRank;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private WorkflowMessageKind kind;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WorkflowMessage() {
    }

    WorkflowMessage(UUID id, UUID organizationId, UUID runId, int sequence, Integer stepRank, WorkflowMessageKind kind,
            String body, Instant now) {
        this.id = id;
        this.organizationId = organizationId;
        this.runId = runId;
        this.sequence = sequence;
        this.stepRank = stepRank;
        this.kind = kind;
        this.body = body;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getRunId() { return runId; }
    public int getSequence() { return sequence; }
    public Integer getStepRank() { return stepRank; }
    public WorkflowMessageKind getKind() { return kind; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
