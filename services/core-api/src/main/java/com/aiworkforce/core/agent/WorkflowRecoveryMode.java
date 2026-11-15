package com.aiworkforce.core.agent;

/**
 * {@code NONE}: a failed step ends the run. {@code MANUAL}: a failure whose outcome is KNOWN and safe to repeat (no external
 * effect ever started) holds the run in WAITING_RECOVERY so an authorized person can retry that step; anything else still fails.
 */
public enum WorkflowRecoveryMode {
    NONE,
    MANUAL
}
