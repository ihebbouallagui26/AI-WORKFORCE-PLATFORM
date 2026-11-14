package com.aiworkforce.core.agent;

/** Status of a human approval request. Only PENDING can change; every other state is final. */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    CANCELLED;

    public boolean isFinal() {
        return this != PENDING;
    }
}
