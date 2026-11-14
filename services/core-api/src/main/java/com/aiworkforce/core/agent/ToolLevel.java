package com.aiworkforce.core.agent;

/** Permission level of a tool grant, ordered from least to most powerful. */
public enum ToolLevel {
    READ,
    WRITE,
    EXECUTE;

    public boolean atMost(ToolLevel limit) {
        return ordinal() <= limit.ordinal();
    }
}
