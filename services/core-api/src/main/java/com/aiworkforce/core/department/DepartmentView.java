package com.aiworkforce.core.department;

import com.aiworkforce.core.workforce.ArchiveStatus;
import com.aiworkforce.core.workforce.EmployeeDirectory.EmployeeRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read model of a department (never the entity). */
public record DepartmentView(
        UUID id,
        UUID organizationId,
        String name,
        String code,
        String description,
        UUID parentId,
        EmployeeRef head,
        ArchiveStatus status,
        long employeeCount,
        long agentCount,
        long childCount,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    /** A department with its sub-departments, for the hierarchy view. */
    public record Node(DepartmentView department, List<Node> children) {
    }
}
