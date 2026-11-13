package com.aiworkforce.core.team;

import com.aiworkforce.core.common.persistence.TenantScoped;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Team memberships, always queried within one organization. */
@TenantScoped
interface TeamMemberRepository extends JpaRepository<TeamMember, UUID> {

    List<TeamMember> findByOrganizationIdAndTeamIdOrderByJoinedAtDesc(UUID organizationId, UUID teamId);

    List<TeamMember> findByOrganizationIdAndTeamIdAndLeftAtIsNullOrderByJoinedAtAsc(UUID organizationId, UUID teamId);

    Optional<TeamMember> findByOrganizationIdAndTeamIdAndEmployeeIdAndLeftAtIsNull(UUID organizationId, UUID teamId,
            UUID employeeId);

    List<TeamMember> findByOrganizationIdAndEmployeeIdAndLeftAtIsNull(UUID organizationId, UUID employeeId);

    boolean existsByOrganizationIdAndTeamIdAndEmployeeIdAndLeftAtIsNull(UUID organizationId, UUID teamId,
            UUID employeeId);

    /** Rows of {@code [teamId, count]} of current members. */
    @Query("""
            select m.teamId, count(m) from TeamMember m
            where m.organizationId = :organizationId and m.teamId in :teamIds and m.leftAt is null
            group by m.teamId""")
    List<Object[]> countCurrentByOrganizationGroupedByTeam(@Param("organizationId") UUID organizationId,
            @Param("teamIds") Collection<UUID> teamIds);
}
