package com.aiworkforce.core.agent;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Test access to the package-private workflow maintenance. {@link Probe#newWorker()} builds a worker from the same
 * collaborators and with no memory, exactly like a Core API process after a restart.
 */
@TestConfiguration(proxyBeanMethods = false)
public class WorkflowProbe {

    public static final class Worker {

        private final WorkflowMaintenance maintenance;

        Worker(WorkflowMaintenance maintenance) {
            this.maintenance = maintenance;
        }

        /** One pass over every open run (what the scheduled maintenance does). */
        public int advanceOpenRuns() {
            return maintenance.advanceOpenRuns();
        }
    }

    public static final class Probe {

        private final WorkflowRunRepository runs;
        private final WorkflowScheduler scheduler;

        Probe(WorkflowRunRepository runs, WorkflowScheduler scheduler) {
            this.runs = runs;
            this.scheduler = scheduler;
        }

        public Worker newWorker() {
            return new Worker(new WorkflowMaintenance(runs, scheduler));
        }
    }

    @Bean
    Probe workflowProbe(WorkflowRunRepository runs, WorkflowScheduler scheduler) {
        return new Probe(runs, scheduler);
    }
}
