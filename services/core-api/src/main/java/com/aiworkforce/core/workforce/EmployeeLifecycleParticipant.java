package com.aiworkforce.core.workforce;

import java.util.UUID;

/**
 * Hook for modules that hold responsibilities of an employee (department head, team leader,
 * team memberships). The employee module calls every participant when an employee is
 * deactivated: first {@link #beforeDeactivation} (may refuse with a ConflictException), then
 * {@link #afterDeactivation} in the same transaction.
 */
public interface EmployeeLifecycleParticipant {

    void beforeDeactivation(UUID organizationId, UUID employeeId);

    void afterDeactivation(UUID organizationId, UUID employeeId);
}
