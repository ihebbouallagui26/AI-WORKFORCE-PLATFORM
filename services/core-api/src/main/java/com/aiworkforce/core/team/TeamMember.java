package com.aiworkforce.core.team;

import com.aiworkforce.core.common.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Participation of a human employee in a team, with history: a member who leaves keeps the row
 * with {@code leftAt} set. AI agents will join teams in B3 through their own table, not through
 * a polymorphic reference here.
 */
@Entity
@Table(name = "team_members")
public class TeamMember {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    @Column(name = "added_by", updatable = false)
    private UUID addedBy;

    protected TeamMember() {
    }

    TeamMember(UUID organizationId, UUID teamId, UUID employeeId, UUID addedBy, Instant now) {
        this.id = Ids.newId();
        this.organizationId = organizationId;
        this.teamId = teamId;
        this.employeeId = employeeId;
        this.addedBy = addedBy;
        this.joinedAt = now;
    }

    void leave(Instant now) {
        this.leftAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getTeamId() { return teamId; }
    public UUID getEmployeeId() { return employeeId; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getLeftAt() { return leftAt; }
    public boolean isCurrent() { return leftAt == null; }
}
