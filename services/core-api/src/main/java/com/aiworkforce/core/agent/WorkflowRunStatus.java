package com.aiworkforce.core.agent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a workflow run. The Core API is the only writer.
 * {@code PENDING → RUNNING ⇄ WAITING_APPROVAL → SUCCEEDED | FAILED | TIMEOUT};
 * {@code RUNNING → WAITING_RECOVERY → RUNNING} (C3.3: a failed step whose outcome is known and safe waits for a person);
 * {@code CANCEL_REQUESTED} (cooperative) is reachable from every open state and settles to {@code CANCELLED}.
 * Terminal states never change.
 */
public enum WorkflowRunStatus {
    PENDING,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_RECOVERY,
    CANCEL_REQUESTED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMEOUT;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == TIMEOUT;
    }

    public boolean isOpen() {
        return !isTerminal();
    }

    public boolean canTransitionTo(WorkflowRunStatus next) {
        Set<WorkflowRunStatus> allowed = switch (this) {
            case PENDING -> EnumSet.of(RUNNING, FAILED, TIMEOUT, CANCEL_REQUESTED);
            case RUNNING -> EnumSet.of(WAITING_APPROVAL, WAITING_RECOVERY, SUCCEEDED, FAILED, TIMEOUT, CANCEL_REQUESTED);
            case WAITING_RECOVERY -> EnumSet.of(RUNNING, FAILED, TIMEOUT, CANCEL_REQUESTED);
            case WAITING_APPROVAL -> EnumSet.of(RUNNING, FAILED, TIMEOUT, CANCEL_REQUESTED);
            case CANCEL_REQUESTED -> EnumSet.of(CANCELLED, TIMEOUT);
            case SUCCEEDED, FAILED, CANCELLED, TIMEOUT -> EnumSet.noneOf(WorkflowRunStatus.class);
        };
        return allowed.contains(next);
    }
}
