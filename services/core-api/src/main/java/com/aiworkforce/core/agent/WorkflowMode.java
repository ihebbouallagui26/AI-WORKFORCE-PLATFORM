package com.aiworkforce.core.agent;

/**
 * How a workflow decides what runs next. PIPELINE (C3.1): the definition lists ordered static steps and the Core API
 * picks the next one. COORDINATED (C3.2): an explicitly chosen coordinator agent asks, one decision turn at a time, for a
 * worker to be delegated a task, or for the workflow to finish; the Core API validates and executes every decision.
 */
public enum WorkflowMode {
    PIPELINE,
    COORDINATED
}
