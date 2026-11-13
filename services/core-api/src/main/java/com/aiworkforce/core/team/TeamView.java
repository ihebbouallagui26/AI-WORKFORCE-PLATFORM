package com.aiworkforce.core.team;

import com.aiworkforce.core.department.DepartmentRef;
import com.aiworkforce.core.workforce.ArchiveStatus;
import com.aiworkforce.core.workforce.EmployeeDirectory.EmployeeRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read models of teams (never the entities). */
public record TeamView(
        UUID id,
        UUID organizationId,
        String name,
        String description,
        DepartmentRef department,
        EmployeeRef leader,
        ArchiveStatus status,
        long memberCount,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    /** A participation of an employee in a team. */
    public record Member(EmployeeRef employee, Instant joinedAt, Instant leftAt, boolean leader) {
    }

    /** A team with its current members. */
    public record Detail(TeamView team, List<Member> members) {
    }
}
