package com.aiworkforce.core.workforce;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Employee lookups needed by the department, position and team modules. Defined here (shared
 * kernel) and implemented by the employee module, so those modules never depend on it: the
 * module graph stays acyclic (employee → department/position; department/position/team →
 * workforce). Methods perform no permission check: callers have already checked access to the
 * organization, and every method is organization-scoped.
 */
public interface EmployeeDirectory {

    @io.swagger.v3.oas.annotations.media.Schema(name = "EmployeeReference")
    record EmployeeRef(UUID id, String displayName, EmploymentStatus status) {
    }

    Map<UUID, EmployeeRef> findRefs(UUID organizationId, Collection<UUID> employeeIds);

    /**
     * An employee that may take a responsibility (department head, team leader, manager, team
     * member): it must exist in the organization (404, never revealing other organizations) and
     * be current, i.e. not INACTIVE (409).
     */
    EmployeeRef requireCurrent(UUID organizationId, UUID employeeId);

    /** Current (not INACTIVE) employees per department. */
    Map<UUID, Long> currentCountByDepartment(UUID organizationId);

    long currentCountInDepartment(UUID organizationId, UUID departmentId);

    /** All employees ever assigned, whatever their status (deletion safety). */
    long totalCountInDepartment(UUID organizationId, UUID departmentId);

    Map<UUID, Long> currentCountByPosition(UUID organizationId);

    long totalCountInPosition(UUID organizationId, UUID positionId);
}
