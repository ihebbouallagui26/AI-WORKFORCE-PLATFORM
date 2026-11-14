package com.aiworkforce.core.agent;

import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Test access to the package-private approval maintenance. {@link #newWorker()} builds a brand-new worker
 * from the same collaborators: it carries no memory, exactly like a Core API process after a restart.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MaintenanceProbe {

    public static final class Worker {

        private final ApprovalMaintenance maintenance;

        Worker(ApprovalMaintenance maintenance) {
            this.maintenance = maintenance;
        }

        public int expireDue() {
            return maintenance.expireDue();
        }

        public int closeObsolete() {
            return maintenance.closeObsolete();
        }

        public int redeliver() {
            return maintenance.redeliver();
        }
    }

    public static final class Probe {

        private final ToolApprovalRepository approvals;
        private final ToolInvocationRepository invocations;
        private final ApprovalLifecycle lifecycle;
        private final OrchestratorGateway gateway;
        private final ThreadPoolTaskExecutor executor;
        private final ExecutionProperties properties;
        private final Clock clock;

        Probe(ToolApprovalRepository approvals, ToolInvocationRepository invocations, ApprovalLifecycle lifecycle, OrchestratorGateway gateway,
                ThreadPoolTaskExecutor executor, ExecutionProperties properties, Clock clock) {
            this.approvals = approvals;
            this.invocations = invocations;
            this.lifecycle = lifecycle;
            this.gateway = gateway;
            this.executor = executor;
            this.properties = properties;
            this.clock = clock;
        }

        public Worker newWorker() {
            return new Worker(new ApprovalMaintenance(approvals, invocations, lifecycle, gateway, executor, properties, clock));
        }

        /** Waits (bounded) until the after-commit notifications handed to the pool have all run. */
        public void quiesce() throws InterruptedException {
            for (int i = 0; i < 200; i++) {
                var pool = executor.getThreadPoolExecutor();
                if (pool.getActiveCount() == 0 && pool.getQueue().isEmpty()) {
                    return;
                }
                Thread.sleep(25);
            }
            throw new IllegalStateException("notification pool did not drain");
        }

        /** A lifecycle call made with the WRONG organization must change nothing. */
        public void expireAs(UUID organizationId, UUID approvalId) {
            lifecycle.expireIfDue(organizationId, approvalId);
            lifecycle.closeObsolete(organizationId, approvalId);
        }

    }

    @Bean
    Probe maintenanceProbe(ToolApprovalRepository approvals, ToolInvocationRepository invocations, ApprovalLifecycle lifecycle, OrchestratorGateway gateway,
            @Qualifier("executionDispatchExecutor") ThreadPoolTaskExecutor executor, ExecutionProperties properties, Clock clock) {
        return new Probe(approvals, invocations, lifecycle, gateway, executor, properties, clock);
    }
}
