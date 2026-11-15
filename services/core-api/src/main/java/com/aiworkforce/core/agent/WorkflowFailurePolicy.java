package com.aiworkforce.core.agent;

/**
 * What a workflow does when a step fails for good (its automatic retries, if any, are used up).
 * {@code FAIL_FAST} (default): nothing after the failed step starts. {@code CONTINUE}: the failure is kept, reported (never
 * presented as a success) to the next pipeline step that declared it as an input or to the next coordinator decision, and
 * the workflow goes on. {@code RETRY}: like FAIL_FAST once the bounded automatic retries (maxRetriesPerStep, at least 1) fail.
 */
public enum WorkflowFailurePolicy {
    FAIL_FAST,
    CONTINUE,
    RETRY
}
