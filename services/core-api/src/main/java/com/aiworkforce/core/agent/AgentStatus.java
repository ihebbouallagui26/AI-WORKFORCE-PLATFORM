package com.aiworkforce.core.agent;

/**
 * Lifecycle of an AI agent. ACTIVE means "enabled and eligible for future execution": it does not
 * mean the agent is running anything (execution is Phase C). ARCHIVED is terminal.
 */
public enum AgentStatus {
    DRAFT,
    ACTIVE,
    PAUSED,
    ARCHIVED
}
