package com.aiworkforce.core.agent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Status of an agent execution. {@code PENDING → RUNNING → SUCCEEDED | FAILED}, and
 * {@code PENDING → FAILED} when dispatching fails or the execution times out. Terminal states are final.
 * Cancellation is not supported in C1 (it cannot be made consistent without a way to stop a running provider call).
 */
public enum ExecutionStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED;

    private static final Set<ExecutionStatus> FROM_PENDING = EnumSet.of(RUNNING, FAILED);
    private static final Set<ExecutionStatus> FROM_RUNNING = EnumSet.of(SUCCEEDED, FAILED);

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }

    public boolean canTransitionTo(ExecutionStatus next) {
        return switch (this) {
            case PENDING -> FROM_PENDING.contains(next);
            case RUNNING -> FROM_RUNNING.contains(next);
            case SUCCEEDED, FAILED -> false;
        };
    }
}
