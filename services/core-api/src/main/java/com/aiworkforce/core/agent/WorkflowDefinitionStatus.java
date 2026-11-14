package com.aiworkforce.core.agent;

/** A definition is edited while ACTIVE; an ARCHIVED one can no longer be started or modified. Runs keep their snapshot. */
public enum WorkflowDefinitionStatus {
    ACTIVE,
    ARCHIVED
}
