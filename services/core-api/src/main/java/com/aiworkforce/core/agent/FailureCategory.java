package com.aiworkforce.core.agent;

/** The workflow failure taxonomy. The category, not the raw error code, drives every retry and recovery decision. */
public enum FailureCategory {
    /** Provider briefly unavailable, rate limited or timed out, or the orchestrator was not reachable. Retryable if nothing external started. */
    TRANSIENT_PROVIDER_FAILURE,
    /** Invalid credential, model, configuration or request. Never retried automatically; a person may retry after fixing it. */
    PERMANENT_PROVIDER_FAILURE,
    /** A tool returned a definite error. Not assumed idempotent: never retried. */
    TOOL_FAILURE,
    /** An external action may have run and its result is lost. NEVER replayed. */
    OUTCOME_UNKNOWN,
    /** Permission, participation or agent state no longer allows the work. Never retried. */
    AUTHORIZATION_FAILURE,
    /** Budget, deadline, step timeout or cancellation. Terminal for the step. */
    WORKFLOW_LIMIT_EXCEEDED,
    /** An error code this version does not know: treated as not retryable. */
    UNCLASSIFIED
}
