package com.aiworkforce.core.agent;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fails executions that stay PENDING or RUNNING beyond {@code app.executions.timeout} (an orchestrator
 * that crashed or lost the request would otherwise leave them open forever and consume the
 * organization's concurrency budget). It uses the same guarded transition as every other writer.
 */
@Component
class ExecutionReaper {

    private static final Logger log = LoggerFactory.getLogger(ExecutionReaper.class);
    private static final int BATCH = 50;

    private final ExecutionRepository executions;
    private final ExecutionCoordinator coordinator;
    private final ExecutionProperties properties;
    private final Clock clock;

    ExecutionReaper(ExecutionRepository executions, ExecutionCoordinator coordinator, ExecutionProperties properties,
            Clock clock) {
        this.executions = executions;
        this.coordinator = coordinator;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.executions.reaper-interval-ms:30000}", initialDelayString = "${app.executions.reaper-interval-ms:30000}")
    void run() {
        reapStale();
    }

    /** Package-visible for tests. Returns how many executions were failed. */
    int reapStale() {
        Instant cutoff = Instant.now(clock).minus(properties.timeout());
        List<AgentExecution> stale = findStale(cutoff);
        for (AgentExecution execution : stale) {
            try {
                coordinator.failIfOpen(execution.getOrganizationId(), execution.getId(), "TIMEOUT",
                        "The execution did not finish in time.");
            } catch (RuntimeException e) {
                log.warn("could not reap execution {}", execution.getId());
            }
        }
        return stale.size();
    }

    @Transactional(readOnly = true)
    List<AgentExecution> findStale(Instant cutoff) {
        return executions.findStaleOpenExecutionsOrganizationWide(cutoff, PageRequest.of(0, BATCH));
    }
}
