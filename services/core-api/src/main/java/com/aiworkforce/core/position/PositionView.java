package com.aiworkforce.core.position;

import com.aiworkforce.core.department.DepartmentRef;
import com.aiworkforce.core.workforce.ArchiveStatus;
import java.time.Instant;
import java.util.UUID;

/** Read model of a position (never the entity). */
public record PositionView(
        UUID id,
        UUID organizationId,
        String title,
        String code,
        String description,
        DepartmentRef department,
        ArchiveStatus status,
        long employeeCount,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
