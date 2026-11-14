package com.aiworkforce.core.agent;

/** Status of one step (one child execution): {@code RUNNING ⇄ WAITING_APPROVAL → SUCCEEDED | FAILED | CANCELLED}. */
public enum WorkflowStepStatus {
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isFinal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
