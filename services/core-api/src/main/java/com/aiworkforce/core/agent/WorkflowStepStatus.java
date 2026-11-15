package com.aiworkforce.core.agent;

/**
 * Status of one step (one child execution): {@code QUEUED → RUNNING ⇄ WAITING_APPROVAL → SUCCEEDED | FAILED | CANCELLED}.
 * QUEUED (C3.4) is accepted, persisted work that has no child execution yet: the scheduler dispatches it when capacity,
 * budget and the workflow's concurrency bound allow, or cancels it.
 */
public enum WorkflowStepStatus {
    QUEUED,
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isFinal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
