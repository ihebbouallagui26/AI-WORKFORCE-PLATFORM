package com.aiworkforce.core.agent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Status of one tool invocation.
 * {@code AWAITING_APPROVAL → APPROVED | REJECTED | EXPIRED | FAILED}, {@code APPROVED → RUNNING → SUCCEEDED | FAILED};
 * {@code DENIED} is final at creation. A RUNNING invocation is never restarted: if its outcome is lost it
 * ends FAILED (a call runs at most once).
 */
public enum InvocationStatus {
    DENIED,
    AWAITING_APPROVAL,
    APPROVED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    REJECTED,
    EXPIRED;

    public boolean isFinal() {
        return this == DENIED || this == SUCCEEDED || this == FAILED || this == REJECTED || this == EXPIRED;
    }

    public boolean canTransitionTo(InvocationStatus next) {
        Set<InvocationStatus> allowed = switch (this) {
            case AWAITING_APPROVAL -> EnumSet.of(APPROVED, REJECTED, EXPIRED, FAILED);
            case APPROVED -> EnumSet.of(RUNNING, FAILED);
            case RUNNING -> EnumSet.of(SUCCEEDED, FAILED);
            case DENIED, SUCCEEDED, FAILED, REJECTED, EXPIRED -> EnumSet.noneOf(InvocationStatus.class);
        };
        return allowed.contains(next);
    }
}
