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
 * The human decision about ONE tool invocation. It is bound to the execution, the agent, the tool, the
 * provider call id, the SHA-256 of the canonical arguments and the policy version that were in force
 * when it was requested; the 1:1 unique key with the invocation means it can never be reused for
 * another call. Only a PENDING approval can change, and only once.
 *
 * <p>It is also the durable outbox of the resume notification: {@code resumeAcknowledgedAt} stays null
 * until the orchestrator confirms it took the decision into account, and a scheduled job retries.
 */
@Entity
@Table(name = "tool_approvals")
public class ToolApproval {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "invocation_id", nullable = false, updatable = false)
    private UUID invocationId;

    @Column(name = "tool_code", nullable = false, updatable = false)
    private String toolCode;

    @Column(name = "mcp_server", updatable = false)
    private String mcpServer;

    @Column(name = "tool_call_id", nullable = false, updatable = false)
    private String toolCallId;

    @Column(nullable = false, updatable = false)
    private String risk;

    @Column(nullable = false, updatable = false)
    private String description;

    @Column(updatable = false)
    private String reason;

    @Column(name = "arguments_redacted", nullable = false, updatable = false)
    private String argumentsRedacted;

    @Column(name = "arguments_hash", nullable = false, updatable = false)
    private String argumentsHash;

    @Column(name = "policy_version", nullable = false, updatable = false)
    private int policyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApprovalStatus status;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "resume_acknowledged_at")
    private Instant resumeAcknowledgedAt;

    @Column(name = "resume_attempts", nullable = false)
    private int resumeAttempts;

    @Column(name = "last_resume_attempt_at")
    private Instant lastResumeAttemptAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ToolApproval() {
    }

    ToolApproval(UUID id, ToolInvocation invocation, String mcpServer, String risk, String description,
            Instant now, Instant expiresAt) {
        this.id = id;
        this.organizationId = invocation.getOrganizationId();
        this.executionId = invocation.getExecutionId();
        this.agentId = invocation.getAgentId();
        this.invocationId = invocation.getId();
        this.toolCode = invocation.getToolCode();
        this.mcpServer = mcpServer;
        this.toolCallId = invocation.getToolCallId();
        this.risk = risk;
        this.description = description;
        this.reason = invocation.getReason();
        this.argumentsRedacted = invocation.getArgumentsRedacted();
        this.argumentsHash = invocation.getArgumentsHash();
        this.policyVersion = invocation.getPolicyVersion();
        this.status = ApprovalStatus.PENDING;
        this.requestedAt = now;
        this.expiresAt = expiresAt;
        this.updatedAt = now;
    }

    private void decide(ApprovalStatus next, UUID user, Instant now, String why) {
        if (status != ApprovalStatus.PENDING) {
            throw new ConflictException("This approval request is already " + status + ".");
        }
        if (!now.isBefore(expiresAt)) {
            throw new ConflictException("This approval request has expired.");
        }
        status = next;
        decidedBy = user;
        decidedAt = now;
        rejectionReason = why;
        updatedAt = now;
    }

    void approve(UUID user, Instant now) {
        decide(ApprovalStatus.APPROVED, user, now, null);
    }

    void reject(UUID user, Instant now, String why) {
        decide(ApprovalStatus.REJECTED, user, now, why);
    }

    boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** No human decided in time. The decision columns stay empty: nobody decided. */
    void expire(Instant now) {
        if (status != ApprovalStatus.PENDING) {
            throw new ConflictException("This approval request is already " + status + ".");
        }
        status = ApprovalStatus.EXPIRED;
        updatedAt = now;
    }

    /** The execution ended before anyone decided: the request is closed, never executable. */
    void cancel(Instant now) {
        if (status != ApprovalStatus.PENDING) {
            throw new ConflictException("This approval request is already " + status + ".");
        }
        status = ApprovalStatus.CANCELLED;
        updatedAt = now;
    }

    void acknowledgeResume(Instant now) {
        resumeAcknowledgedAt = now;
        updatedAt = now;
    }

    void recordResumeAttempt(Instant now) {
        resumeAttempts++;
        lastResumeAttemptAt = now;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getExecutionId() { return executionId; }
    public UUID getAgentId() { return agentId; }
    public UUID getInvocationId() { return invocationId; }
    public String getToolCode() { return toolCode; }
    public String getMcpServer() { return mcpServer; }
    public String getToolCallId() { return toolCallId; }
    public String getRisk() { return risk; }
    public String getDescription() { return description; }
    public String getReason() { return reason; }
    public String getArgumentsRedacted() { return argumentsRedacted; }
    public String getArgumentsHash() { return argumentsHash; }
    public int getPolicyVersion() { return policyVersion; }
    public ApprovalStatus getStatus() { return status; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getResumeAcknowledgedAt() { return resumeAcknowledgedAt; }
    public int getResumeAttempts() { return resumeAttempts; }
    public Instant getLastResumeAttemptAt() { return lastResumeAttemptAt; }
    public long getVersion() { return version; }
}
