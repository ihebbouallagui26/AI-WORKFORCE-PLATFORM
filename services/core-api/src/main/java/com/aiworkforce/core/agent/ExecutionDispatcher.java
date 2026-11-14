package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.ExecutionService.ExecutionRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Hands a newly committed execution to the orchestrator, off the request thread.
 *
 * <p>The hand-over is submitted explicitly to a bounded pool (no {@code @Async}, so a rejection is
 * visible here instead of surfacing as an HTTP 500 after the commit). Every persisted execution ends
 * up in a truthful state:
 * <ul>
 *   <li>submitted and delivered: the orchestrator takes over (PENDING, then RUNNING);</li>
 *   <li>pool saturated: the execution is failed at once with {@code DISPATCH_CAPACITY_EXCEEDED};</li>
 *   <li>delivery failed: failed with {@code ORCHESTRATOR_UNAVAILABLE} <b>only if still PENDING</b>
 *       (see {@link ExecutionCoordinator#failIfPending}).</li>
 * </ul>
 * The HTTP call to the orchestrator never runs on the request thread.
 */
@Component
class ExecutionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ExecutionDispatcher.class);

    static final String CAPACITY_EXCEEDED = "DISPATCH_CAPACITY_EXCEEDED";
    static final String ORCHESTRATOR_UNAVAILABLE = "ORCHESTRATOR_UNAVAILABLE";

    private final OrchestratorGateway gateway;
    private final ExecutionCoordinator coordinator;
    private final ThreadPoolTaskExecutor executor;

    ExecutionDispatcher(OrchestratorGateway gateway, ExecutionCoordinator coordinator,
            @Qualifier("executionDispatchExecutor") ThreadPoolTaskExecutor executor) {
        this.gateway = gateway;
        this.coordinator = coordinator;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRequested(ExecutionRequested event) {
        try {
            executor.execute(() -> dispatch(event));
        } catch (TaskRejectedException e) {
            log.warn("execution {} not dispatched: hand-over pool saturated", event.executionId());
            closeAsFailed(event, CAPACITY_EXCEEDED, "The system is busy. Try again in a moment.");
        }
    }

    private void dispatch(ExecutionRequested event) {
        try {
            gateway.dispatch(event.organizationId(), event.executionId());
        } catch (RuntimeException e) {
            log.warn("execution {} could not be dispatched", event.executionId());
            closeAsFailed(event, ORCHESTRATOR_UNAVAILABLE, "The AI orchestrator is unavailable. Try again later.");
        }
    }

    /** Fails the execution if it is still PENDING; never throws (the reaper is the safety net). */
    private void closeAsFailed(ExecutionRequested event, String code, String message) {
        try {
            if (!coordinator.failIfPending(event.organizationId(), event.executionId(), code, message)) {
                log.info("execution {} already started or final: dispatch error ignored", event.executionId());
            }
        } catch (RuntimeException e) {
            log.error("execution {} could not be closed after a dispatch error: {}", event.executionId(),
                    e.getClass().getSimpleName());
        }
    }
}
