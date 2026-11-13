package com.aiworkforce.core.workforce;

/** Employment status of a human employee. INACTIVE employees keep their record but hold no responsibility. */
public enum EmploymentStatus {
    ACTIVE,
    ON_LEAVE,
    INACTIVE;

    /** Still employed: may be a manager, department head, team leader or team member. */
    public boolean isCurrent() {
        return this != INACTIVE;
    }
}
