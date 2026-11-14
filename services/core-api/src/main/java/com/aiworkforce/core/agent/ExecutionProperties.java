package com.aiworkforce.core.agent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits of agent executions.
 *
 * @param timeout                     an execution still open after this long is failed with {@code TIMEOUT}
 * @param maxConcurrentPerOrganization open (PENDING, RUNNING or WAITING_APPROVAL) executions allowed per organization (cost and abuse bound)
 * @param maxResultChars              results are truncated to this length before being stored
 * @param dispatchMaxThreads          threads of the hand-over pool (short HTTP calls to the orchestrator)
 * @param dispatchQueueCapacity       hand-overs allowed to wait; beyond that a launch is failed with DISPATCH_CAPACITY_EXCEEDED
 * @param approvalTimeout             how long a human has to decide a tool approval before it expires (and the run fails)
 * @param resumeGiveUp                a decided approval whose resume is still not taken after this long fails the run
 * @param maxToolCallsPerExecution    tool calls one execution may request (denied ones included)
 * @param maxToolArgumentBytes        size limit of the canonical arguments of one tool call
 * @param maxToolOutputChars          tool results are truncated to this length before being stored
 */
@ConfigurationProperties(prefix = "app.executions")
public record ExecutionProperties(Duration timeout, Integer maxConcurrentPerOrganization, Integer maxResultChars,
        Integer dispatchMaxThreads, Integer dispatchQueueCapacity, Duration approvalTimeout, Duration resumeGiveUp,
        Integer maxToolCallsPerExecution, Integer maxToolArgumentBytes, Integer maxToolOutputChars) {

    public ExecutionProperties {
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        maxConcurrentPerOrganization =
                maxConcurrentPerOrganization == null || maxConcurrentPerOrganization < 1 ? 3 : maxConcurrentPerOrganization;
        maxResultChars = maxResultChars == null || maxResultChars < 1000 ? 60_000 : maxResultChars;
        dispatchMaxThreads = dispatchMaxThreads == null || dispatchMaxThreads < 1 ? 4 : dispatchMaxThreads;
        dispatchQueueCapacity = dispatchQueueCapacity == null || dispatchQueueCapacity < 0 ? 50 : dispatchQueueCapacity;
        approvalTimeout = approvalTimeout == null ? Duration.ofMinutes(15) : approvalTimeout;
        resumeGiveUp = resumeGiveUp == null ? Duration.ofMinutes(15) : resumeGiveUp;
        maxToolCallsPerExecution = maxToolCallsPerExecution == null || maxToolCallsPerExecution < 1 ? 10 : maxToolCallsPerExecution;
        maxToolArgumentBytes = maxToolArgumentBytes == null || maxToolArgumentBytes < 64 ? 8192 : maxToolArgumentBytes;
        maxToolOutputChars = maxToolOutputChars == null || maxToolOutputChars < 256 ? 8000 : maxToolOutputChars;
    }
}
