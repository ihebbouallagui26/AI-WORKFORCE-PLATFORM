package com.aiworkforce.core.agent;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Durable advancement of workflow runs, driven by PostgreSQL only (no broker). Every pass reads the open runs and lets the
 * scheduler move each one forward in its own transaction: a finished child execution becomes the next step, a stuck run hits
 * its deadline, a cancellation is settled. Because all the state is in the database, this is ALSO the recovery mechanism
 * after a restart of the Core API or of the orchestrator, and repeating a pass is harmless (idempotent).
 */
@Component
class WorkflowMaintenance {

    private static final Logger log = LoggerFactory.getLogger(WorkflowMaintenance.class);
    private static final int BATCH = 50;

    private final WorkflowRunRepository runs;
    private final WorkflowScheduler scheduler;

    WorkflowMaintenance(WorkflowRunRepository runs, WorkflowScheduler scheduler) {
        this.runs = runs;
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelayString = "${app.workflows.maintenance-ms:3000}", initialDelayString = "${app.workflows.maintenance-ms:3000}")
    void run() {
        advanceOpenRuns();
    }

    /** Package-visible for tests. Returns how many runs were looked at. */
    int advanceOpenRuns() {
        List<WorkflowRun> open = runs.findOpenOrganizationWide(PageRequest.of(0, BATCH));
        for (WorkflowRun run : open) {
            try {
                scheduler.advanceById(run.getOrganizationId(), run.getId());
            } catch (RuntimeException e) {
                log.warn("could not advance workflow run {}: {}", run.getId(), e.getClass().getSimpleName());
            }
        }
        return open.size();
    }
}
