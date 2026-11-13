package com.aiworkforce.core.workforce;

/** Lifecycle of departments, positions and teams: archived rows stay for history, never deleted silently. */
public enum ArchiveStatus {
    ACTIVE,
    ARCHIVED
}
