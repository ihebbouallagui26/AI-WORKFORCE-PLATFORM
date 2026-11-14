package com.aiworkforce.core.team;

import com.aiworkforce.core.common.persistence.TenantScoped;
import com.aiworkforce.core.workforce.ArchiveStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Teams, always queried within one organization. */
@TenantScoped
interface TeamRepository extends JpaRepository<Team, UUID> {

    Optional<Team> findByOrganizationIdAndId(UUID organizationId, UUID id);

    List<Team> findByOrganizationIdAndIdIn(UUID organizationId, Collection<UUID> ids);

    long countByOrganizationId(UUID organizationId);

    boolean existsByOrganizationIdAndNameIgnoreCase(UUID organizationId, String name);

    boolean existsByOrganizationIdAndNameIgnoreCaseAndIdNot(UUID organizationId, String name, UUID id);

    boolean existsByOrganizationIdAndLeaderEmployeeIdAndStatus(UUID organizationId, UUID leaderEmployeeId,
            ArchiveStatus status);

    List<Team> findByOrganizationIdAndLeaderEmployeeId(UUID organizationId, UUID leaderEmployeeId);

    @Query("""
            select t from Team t
            where t.organizationId = :organizationId
              and (:status is null or t.status = :status)
              and (:departmentId is null or t.departmentId = :departmentId)
              and (:q is null or lower(t.name) like :q escape '!')""")
    Page<Team> searchByOrganization(@Param("organizationId") UUID organizationId,
            @Param("status") ArchiveStatus status, @Param("departmentId") UUID departmentId,
            @Param("q") String q, Pageable pageable);
}
