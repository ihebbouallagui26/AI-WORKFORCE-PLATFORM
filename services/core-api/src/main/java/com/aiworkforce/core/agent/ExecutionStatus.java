package com.aiworkforce.core.agent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Status of an agent execution. {@code PENDING → RUNNING → SUCCEEDED | FAILED}, and
 * {@code PENDING → FAILED} when dispatching fails or the execution times out. Since C2 a running execution
 * may wait for a human decision on a tool call ({@code RUNNING → WAITING_APPROVAL → RUNNING | FAILED}).
 * Terminal states are final.
 * Cancellation is not supported in C1 (it cannot be made consistent without a way to stop a running provider call).
 */
public enum ExecutionStatus {
    PENDING,
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED;

    private static final Set<ExecutionStatus> FROM_PENDING = EnumSet.of(RUNNING, FAILED);
    private static final Set<ExecutionStatus> FROM_RUNNING = EnumSet.of(WAITING_APPROVAL, SUCCEEDED, FAILED);
    private static final Set<ExecutionStatus> FROM_WAITING = EnumSet.of(RUNNING, FAILED);

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }

    public boolean canTransitionTo(ExecutionStatus next) {
        return switch (this) {
            case PENDING -> FROM_PENDING.contains(next);
            case RUNNING -> FROM_RUNNING.contains(next);
            case WAITING_APPROVAL -> FROM_WAITING.contains(next);
            case SUCCEEDED, FAILED -> false;
        };
    }
}
