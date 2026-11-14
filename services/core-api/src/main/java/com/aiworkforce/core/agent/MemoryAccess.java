package com.aiworkforce.core.agent;

/** Who may read an agent memory (access restriction). The default is the agent alone. */
public enum MemoryAccess {
    AGENT_ONLY,
    TEAM_MEMBERS,
    ORGANIZATION_MEMBERS
}
