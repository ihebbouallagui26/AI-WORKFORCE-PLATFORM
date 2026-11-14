package com.aiworkforce.core.agent;

/** What a journal entry records. There is no agent-to-agent kind in C3.1: agents never talk to each other. */
public enum WorkflowMessageKind {
    OBJECTIVE,
    ASSIGNMENT,
    RESULT,
    STATUS
}
