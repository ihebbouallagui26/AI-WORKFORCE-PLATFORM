package com.aiworkforce.core.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An agent explicitly chosen, by a person allowed to configure workflows, for one definition. Never inherited from a team. */
@Entity
@Table(name = "workflow_participants")
public class WorkflowParticipant {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "definition_id", nullable = false, updatable = false)
    private UUID definitionId;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "added_by", nullable = false, updatable = false)
    private UUID addedBy;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    protected WorkflowParticipant() {
    }

    WorkflowParticipant(UUID id, UUID organizationId, UUID definitionId, UUID agentId, UUID addedBy, Instant now) {
        this.id = id;
        this.organizationId = organizationId;
        this.definitionId = definitionId;
        this.agentId = agentId;
        this.addedBy = addedBy;
        this.addedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getDefinitionId() { return definitionId; }
    public UUID getAgentId() { return agentId; }
    public UUID getAddedBy() { return addedBy; }
    public Instant getAddedAt() { return addedAt; }
}
