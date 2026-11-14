package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.ApprovalService.ApprovalDecided;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Durable follow-up of tool approvals, driven by PostgreSQL (no broker):
 * <ul>
 *   <li><b>Expiry</b>: pending approvals past their deadline expire and the paused execution fails.</li>
 *   <li><b>Resume outbox</b>: a decided approval is notified to the orchestrator right after commit
 *       (best effort) and then retried every scan until the orchestrator takes it into account (it
 *       acknowledges by calling {@code resume}). A notification lost to a crash or a restart is therefore
 *       re-sent; duplicates are harmless because {@code resume} succeeds once per pause.</li>
 *   <li><b>Give-up</b>: a decision that stays unacknowledged for too long fails the run ({@code RESUME_FAILED}).</li>
 * </ul>
 */
@Component
class ApprovalMaintenance {

    private static final Logger log = LoggerFactory.getLogger(ApprovalMaintenance.class);
    private static final int BATCH = 50;
    private static final long RETRY_AFTER_SECONDS = 10;

    private final ToolApprovalRepository approvals;
    private final ToolInvocationRepository invocations;
    private final ApprovalLifecycle lifecycle;
    private final OrchestratorGateway gateway;
    private final ThreadPoolTaskExecutor executor;
    private final ExecutionProperties properties;
    private final Clock clock;

    ApprovalMaintenance(ToolApprovalRepository approvals, ToolInvocationRepository invocations, ApprovalLifecycle lifecycle, OrchestratorGateway gateway,
            @Qualifier("executionDispatchExecutor") ThreadPoolTaskExecutor executor, ExecutionProperties properties,
            Clock clock) {
        this.approvals = approvals;
        this.invocations = invocations;
        this.lifecycle = lifecycle;
        this.gateway = gateway;
        this.executor = executor;
        this.properties = properties;
        this.clock = clock;
    }

    /** Immediate, best-effort notification once the decision is committed. The scan below is the safety net. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onDecided(ApprovalDecided event) {
        try {
            executor.execute(() -> notifyOrchestrator(event));
        } catch (TaskRejectedException e) {
            log.warn("resume of execution {} will be retried by the outbox scan (pool saturated)", event.executionId());
        }
    }

    private void notifyOrchestrator(ApprovalDecided event) {
        try {
            gateway.resume(event.organizationId(), event.executionId());
        } catch (RuntimeException e) {
            log.warn("resume of execution {} could not be delivered now; it will be retried", event.executionId());
        }
    }

    @Scheduled(fixedDelayString = "${app.executions.approval-maintenance-ms:10000}",
            initialDelayString = "${app.executions.approval-maintenance-ms:10000}")
    void run() {
        expireDue();
        closeObsolete();
        redeliver();
    }

    /** Package-visible for tests. Closes open approvals, and calls still RUNNING, whose execution is already terminal. */
    int closeObsolete() {
        List<ToolApproval> open = approvals.findOpenOfTerminalExecutionsOrganizationWide(PageRequest.of(0, BATCH));
        for (ToolApproval approval : open) {
            try {
                lifecycle.closeObsolete(approval.getOrganizationId(), approval.getId());
            } catch (RuntimeException e) {
                log.warn("could not close obsolete approval {}: {}", approval.getId(), e.getClass().getSimpleName());
            }
        }
        List<ToolInvocation> running = invocations.findRunningOfTerminalExecutionsOrganizationWide(PageRequest.of(0, BATCH));
        for (ToolInvocation invocation : running) {
            try {
                lifecycle.closeRunning(invocation.getOrganizationId(), invocation.getId());
            } catch (RuntimeException e) {
                log.warn("could not close running call {}: {}", invocation.getId(), e.getClass().getSimpleName());
            }
        }
        return open.size() + running.size();
    }

    /** Package-visible for tests. Returns how many approvals were processed. */
    int expireDue() {
        List<ToolApproval> due = approvals.findExpiredPendingOrganizationWide(Instant.now(clock), PageRequest.of(0, BATCH));
        for (ToolApproval approval : due) {
            try {
                lifecycle.expireIfDue(approval.getOrganizationId(), approval.getId());
            } catch (RuntimeException e) {
                log.warn("could not expire approval {}: {}", approval.getId(), e.getClass().getSimpleName());
            }
        }
        return due.size();
    }

    /** Package-visible for tests. Returns how many notifications were (re)sent. */
    int redeliver() {
        Instant now = Instant.now(clock);
        List<ToolApproval> open = approvals.findUnacknowledgedDecisionsOrganizationWide(now.minusSeconds(RETRY_AFTER_SECONDS),
                PageRequest.of(0, BATCH));
        int sent = 0;
        for (ToolApproval approval : open) {
            try {
                if (approval.getDecidedAt() != null && approval.getDecidedAt().isBefore(now.minus(properties.resumeGiveUp()))) {
                    lifecycle.giveUpResume(approval.getOrganizationId(), approval.getId());
                    continue;
                }
                if (!lifecycle.claimResumeAttempt(approval.getOrganizationId(), approval.getId(), now.minusSeconds(RETRY_AFTER_SECONDS))) {
                    continue; // another worker owns this retry window, or the orchestrator already took the decision
                }
                gateway.resume(approval.getOrganizationId(), approval.getExecutionId());
                sent++;
            } catch (RuntimeException e) {
                log.warn("resume of execution {} not delivered: {}", approval.getExecutionId(), e.getClass().getSimpleName());
            }
        }
        return sent;
    }
}
