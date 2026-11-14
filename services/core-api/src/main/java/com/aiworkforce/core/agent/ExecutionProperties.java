package com.aiworkforce.core.agent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits of agent executions.
 *
 * @param timeout                     an execution still open after this long is failed with {@code TIMEOUT}
 * @param maxConcurrentPerOrganization open (PENDING or RUNNING) executions allowed per organization (cost and abuse bound)
 * @param maxResultChars              results are truncated to this length before being stored
 * @param dispatchMaxThreads          threads of the hand-over pool (short HTTP calls to the orchestrator)
 * @param dispatchQueueCapacity       hand-overs allowed to wait; beyond that a launch is failed with DISPATCH_CAPACITY_EXCEEDED
 */
@ConfigurationProperties(prefix = "app.executions")
public record ExecutionProperties(Duration timeout, Integer maxConcurrentPerOrganization, Integer maxResultChars,
        Integer dispatchMaxThreads, Integer dispatchQueueCapacity) {

    public ExecutionProperties {
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        maxConcurrentPerOrganization =
                maxConcurrentPerOrganization == null || maxConcurrentPerOrganization < 1 ? 3 : maxConcurrentPerOrganization;
        maxResultChars = maxResultChars == null || maxResultChars < 1000 ? 60_000 : maxResultChars;
        dispatchMaxThreads = dispatchMaxThreads == null || dispatchMaxThreads < 1 ? 4 : dispatchMaxThreads;
        dispatchQueueCapacity = dispatchQueueCapacity == null || dispatchQueueCapacity < 0 ? 50 : dispatchQueueCapacity;
    }
}
