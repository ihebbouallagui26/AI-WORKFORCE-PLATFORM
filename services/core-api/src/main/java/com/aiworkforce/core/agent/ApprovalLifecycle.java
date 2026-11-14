package com.aiworkforce.core.agent;

import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.provider.SensitivePayloadVault;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Time-driven transitions of approvals (expiry, giving up on a lost resume) and the outbox bookkeeping.
 * Every method runs in its own transaction so a caller that is about to refuse a request can still make
 * the transition durable, and so one failing approval never blocks the others. Locks follow the
 * global order: execution, then invocation, then approval.
 */
@Component
class ApprovalLifecycle {

    static final String APPROVAL_EXPIRED = "APPROVAL_EXPIRED";
    static final String RESUME_FAILED = "RESUME_FAILED";

    private final ExecutionRepository executions;
    private final ToolInvocationRepository invocations;
    private final ToolApprovalRepository approvals;
    private final SensitivePayloadVault vault;
    private final AuditLog audit;
    private final Clock clock;

    ApprovalLifecycle(ExecutionRepository executions, ToolInvocationRepository invocations, ToolApprovalRepository approvals,
            SensitivePayloadVault vault, AuditLog audit, Clock clock) {
        this.executions = executions;
        this.invocations = invocations;
        this.approvals = approvals;
        this.vault = vault;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * No human decided in time: the approval and its call EXPIRE and the paused execution fails. Idempotent:
     * an approval that was decided meanwhile is left alone.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireIfDue(UUID organizationId, UUID approvalId) {
        ToolApproval peek = approvals.findByOrganizationIdAndId(organizationId, approvalId).orElse(null);
        if (peek == null) {
            return;
        }
        AgentExecution execution = executions.lockByOrganizationAndId(organizationId, peek.getExecutionId()).orElse(null);
        ToolInvocation invocation = invocations.lockByOrganizationAndId(organizationId, peek.getInvocationId()).orElse(null);
        ToolApproval approval = approvals.lockByOrganizationAndId(organizationId, approvalId).orElse(null);
        Instant now = Instant.now(clock);
        if (approval == null || invocation == null || approval.getStatus() != ApprovalStatus.PENDING || !approval.isExpiredAt(now)) {
            return;
        }
        UUID sealed = null;
        approval.expire(now);
        if (invocation.getStatus() == InvocationStatus.AWAITING_APPROVAL) {
            invocation.expire(now);
            sealed = invocation.getArgumentsRef();
            invocation.clearArguments();
        }
        if (execution != null && execution.getStatus() == ExecutionStatus.WAITING_APPROVAL) {
            execution.fail(APPROVAL_EXPIRED, "The approval request expired before a person decided.", now);
        }
        audit.record(organizationId, null, "agent.approval.expired", "agent", approval.getAgentId(),
                AgentSupport.details("executionId", approval.getExecutionId(), "approvalId", approvalId,
                        "tool", approval.getToolCode(), "argumentsHash", approval.getArgumentsHash()));
        if (sealed != null) {
            vault.discard(organizationId, sealed); // last: SecretStore.delete clears the persistence context
        }
    }

    /** The decision exists but the orchestrator never took it: stop waiting and fail the run with a clear code. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void giveUpResume(UUID organizationId, UUID approvalId) {
        ToolApproval peek = approvals.findByOrganizationIdAndId(organizationId, approvalId).orElse(null);
        if (peek == null) {
            return;
        }
        AgentExecution execution = executions.lockByOrganizationAndId(organizationId, peek.getExecutionId()).orElse(null);
        ToolApproval approval = approvals.lockByOrganizationAndId(organizationId, approvalId).orElse(null);
        if (execution == null || approval == null || approval.getResumeAcknowledgedAt() != null
                || execution.getStatus() != ExecutionStatus.WAITING_APPROVAL) {
            return;
        }
        Instant now = Instant.now(clock);
        execution.fail(RESUME_FAILED, "The decision could not be delivered to the orchestrator in time.", now);
        approval.acknowledgeResume(now); // closes the outbox entry: nobody is waiting for it any more
        ToolInvocation invocation = invocations.lockByOrganizationAndId(organizationId, approval.getInvocationId()).orElse(null);
        UUID sealed = null;
        if (invocation != null && invocation.getArgumentsRef() != null) {
            sealed = invocation.getArgumentsRef();
            invocation.clearArguments();
        }
        audit.record(organizationId, null, "agent.approval.resume-failed", "agent", approval.getAgentId(),
                AgentSupport.details("executionId", approval.getExecutionId(), "approvalId", approvalId));
        if (sealed != null) {
            vault.discard(organizationId, sealed); // last: SecretStore.delete clears the persistence context
        }
    }

    /**
     * A call was RUNNING when its execution ended (the orchestrator vanished mid-call, or reported a failure): nobody will
     * complete it. It is closed as FAILED with the honest code OUTCOME_UNKNOWN (the effect may or may not have happened)
     * and is never started again. Idempotent; arguments were already dropped when the call started.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void closeRunning(UUID organizationId, UUID invocationId) {
        ToolInvocation peek = invocations.findByOrganizationIdAndId(organizationId, invocationId).orElse(null);
        if (peek == null) {
            return;
        }
        AgentExecution execution = executions.lockByOrganizationAndId(organizationId, peek.getExecutionId()).orElse(null);
        ToolInvocation invocation = invocations.lockByOrganizationAndId(organizationId, invocationId).orElse(null);
        if (execution == null || invocation == null || !execution.getStatus().isTerminal() || invocation.getStatus() != InvocationStatus.RUNNING) {
            return;
        }
        invocation.fail("OUTCOME_UNKNOWN", "The execution ended while this call was running: whether it took effect is unknown.", Instant.now(clock));
        audit.record(organizationId, null, "agent.tool.outcome-unknown", "agent", invocation.getAgentId(),
                AgentSupport.details("executionId", invocation.getExecutionId(), "tool", invocation.getToolCode(),
                        "argumentsHash", invocation.getArgumentsHash()));
    }

    /**
     * Claims the right to (re)send the resume notification: true for exactly one worker per retry window, even
     * with several Core API instances scanning at once (the row lock serializes them and the loser sees the
     * attempt the winner recorded). False when the orchestrator already took the decision.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimResumeAttempt(UUID organizationId, UUID approvalId, Instant retryBefore) {
        ToolApproval approval = approvals.lockByOrganizationAndId(organizationId, approvalId).orElse(null);
        if (approval == null || approval.getResumeAcknowledgedAt() != null
                || (approval.getLastResumeAttemptAt() != null && !approval.getLastResumeAttemptAt().isBefore(retryBefore))) {
            return false;
        }
        approval.recordResumeAttempt(Instant.now(clock));
        return true;
    }

    /**
     * The execution is already terminal (failed, reaped, finished) but its approval is still open: close it so it
     * can never run a tool, never restarts anything and never lingers in the pending list. A pending request is
     * CANCELLED; a decision nobody took is closed (outbox acknowledged). The call is marked FAILED, its sealed
     * arguments are discarded and the history stays. Idempotent.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void closeObsolete(UUID organizationId, UUID approvalId) {
        ToolApproval peek = approvals.findByOrganizationIdAndId(organizationId, approvalId).orElse(null);
        if (peek == null) {
            return;
        }
        AgentExecution execution = executions.lockByOrganizationAndId(organizationId, peek.getExecutionId()).orElse(null);
        ToolInvocation invocation = invocations.lockByOrganizationAndId(organizationId, peek.getInvocationId()).orElse(null);
        ToolApproval approval = approvals.lockByOrganizationAndId(organizationId, approvalId).orElse(null);
        if (execution == null || invocation == null || approval == null || !execution.getStatus().isTerminal()) {
            return;
        }
        boolean pending = approval.getStatus() == ApprovalStatus.PENDING;
        boolean undelivered = (approval.getStatus() == ApprovalStatus.APPROVED || approval.getStatus() == ApprovalStatus.REJECTED)
                && approval.getResumeAcknowledgedAt() == null;
        if (!pending && !undelivered) {
            return;
        }
        Instant now = Instant.now(clock);
        if (pending) {
            approval.cancel(now);
        } else {
            approval.acknowledgeResume(now);
        }
        UUID sealed = invocation.getArgumentsRef();
        if (invocation.getStatus() == InvocationStatus.AWAITING_APPROVAL || invocation.getStatus() == InvocationStatus.APPROVED) {
            invocation.fail("EXECUTION_ENDED", "The execution ended before the action could run.", now);
        }
        invocation.clearArguments();
        audit.record(organizationId, null, "agent.approval.cancelled", "agent", approval.getAgentId(),
                AgentSupport.details("executionId", approval.getExecutionId(), "approvalId", approvalId,
                        "tool", approval.getToolCode(), "approvalStatus", approval.getStatus().name()));
        if (sealed != null) {
            vault.discard(organizationId, sealed); // last: SecretStore.delete clears the persistence context
        }
    }
}
