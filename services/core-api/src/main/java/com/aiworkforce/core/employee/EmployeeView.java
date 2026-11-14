package com.aiworkforce.core.employee;

import com.aiworkforce.core.access.Membership;
import com.aiworkforce.core.department.DepartmentRef;
import com.aiworkforce.core.position.PositionRef;
import com.aiworkforce.core.workforce.EmployeeDirectory.EmployeeRef;
import com.aiworkforce.core.workforce.EmploymentStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read model of an employee profile (never the entity). The linked platform account is only
 * exposed as an opaque membership id and its status: no e-mail or identity data of the account.
 */
public record EmployeeView(
        UUID id,
        UUID organizationId,
        UUID membershipId,
        Membership.Status membershipStatus,
        String employeeNumber,
        String displayName,
        String professionalEmail,
        PositionRef position,
        DepartmentRef department,
        EmployeeRef manager,
        EmploymentStatus status,
        LocalDate startDate,
        LocalDate endDate,
        String description,
        long directReportCount,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
