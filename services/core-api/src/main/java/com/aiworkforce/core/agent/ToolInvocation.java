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
 * One tool call requested by an agent during an execution. Its status only changes through the
 * methods below, which enforce the state machine ({@link InvocationStatus}). The arguments are never
 * stored in clear: {@code argumentsRef} points to an AES-256-GCM entry of the secret store, and only a
 * redacted rendering plus the SHA-256 of the canonical arguments are kept for humans and for binding
 * an approval to this exact call.
 */
@Entity
@Table(name = "tool_invocations")
public class ToolInvocation {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "tool_code", nullable = false, updatable = false)
    private String toolCode;

    @Column(name = "tool_call_id", nullable = false, updatable = false)
    private String toolCallId;

    @Column(nullable = false, updatable = false)
    private int sequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvocationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ToolPolicy policy;

    @Column(name = "policy_version", nullable = false, updatable = false)
    private int policyVersion;

    @Column(name = "arguments_ref")
    private UUID argumentsRef;

    @Column(name = "arguments_redacted", nullable = false, updatable = false)
    private String argumentsRedacted;

    @Column(name = "arguments_hash", nullable = false, updatable = false)
    private String argumentsHash;

    @Column(updatable = false)
    private String reason;

    /** LEGACY plaintext result (rows written before V11). New results are never stored here (see {@code resultEnvelope}). */
    private String result;

    /** Versioned AES-256-GCM envelope of the result (V11). Opened only by {@link ToolResultProtector} after authorization. */
    @Column(name = "result_envelope")
    private byte[] resultEnvelope;

    @Column(name = "result_truncated", nullable = false)
    private boolean resultTruncated;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ToolInvocation() {
    }

    ToolInvocation(UUID id, UUID organizationId, UUID executionId, UUID agentId, String toolCode, String toolCallId,
            int sequence, InvocationStatus initial, ToolPolicy policy, int policyVersion, UUID argumentsRef,
            String argumentsRedacted, String argumentsHash, String reason, String deniedCode, String deniedMessage,
            Instant now) {
        this.id = id;
        this.organizationId = organizationId;
        this.executionId = executionId;
        this.agentId = agentId;
        this.toolCode = toolCode;
        this.toolCallId = toolCallId;
        this.sequence = sequence;
        this.status = initial;
        this.policy = policy;
        this.policyVersion = policyVersion;
        this.argumentsRef = argumentsRef;
        this.argumentsRedacted = argumentsRedacted;
        this.argumentsHash = argumentsHash;
        this.reason = reason;
        this.requestedAt = now;
        this.updatedAt = now;
        if (initial == InvocationStatus.DENIED) {
            this.errorCode = deniedCode;
            this.errorMessage = deniedMessage;
            this.completedAt = now;
        }
    }

    private void transition(InvocationStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new ConflictException("The tool call is " + status + " and cannot become " + next + ".");
        }
        status = next;
        updatedAt = now;
    }

    void approve(Instant now) {
        transition(InvocationStatus.APPROVED, now);
    }

    void reject(Instant now) {
        transition(InvocationStatus.REJECTED, now);
        completedAt = now;
    }

    void expire(Instant now) {
        transition(InvocationStatus.EXPIRED, now);
        completedAt = now;
    }

    /** APPROVED to RUNNING exactly once: this is the single moment a call may start. */
    void begin(Instant now) {
        transition(InvocationStatus.RUNNING, now);
        startedAt = now;
    }

    /** The result arrives already ENCRYPTED: the entity never holds a new plaintext result. */
    void succeed(byte[] protectedResult, boolean truncated, Instant now) {
        transition(InvocationStatus.SUCCEEDED, now);
        result = null;
        resultEnvelope = protectedResult;
        resultTruncated = truncated;
        completedAt = now;
    }

    void fail(String code, String message, Instant now) {
        transition(InvocationStatus.FAILED, now);
        errorCode = code;
        errorMessage = message;
        completedAt = now;
    }

    /** The encrypted arguments are dropped once the call is final (data minimization). */
    void clearArguments() {
        argumentsRef = null;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getExecutionId() { return executionId; }
    public UUID getAgentId() { return agentId; }
    public String getToolCode() { return toolCode; }
    public String getToolCallId() { return toolCallId; }
    public int getSequence() { return sequence; }
    public InvocationStatus getStatus() { return status; }
    public ToolPolicy getPolicy() { return policy; }
    public int getPolicyVersion() { return policyVersion; }
    public UUID getArgumentsRef() { return argumentsRef; }
    public String getArgumentsRedacted() { return argumentsRedacted; }
    public String getArgumentsHash() { return argumentsHash; }
    public String getReason() { return reason; }
    /** Legacy plaintext only (null for every result written since V11): use {@link ToolResultProtector} for the rest. */
    String getLegacyResult() { return result; }
    byte[] getResultEnvelope() { return resultEnvelope; }
    public boolean isResultTruncated() { return resultTruncated; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
