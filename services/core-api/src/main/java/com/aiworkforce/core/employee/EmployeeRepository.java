package com.aiworkforce.core.employee;

import com.aiworkforce.core.common.persistence.TenantScoped;
import com.aiworkforce.core.workforce.EmploymentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Employee profiles, always queried within one organization. */
@TenantScoped
interface EmployeeRepository extends JpaRepository<EmployeeProfile, UUID> {

    Optional<EmployeeProfile> findByOrganizationIdAndId(UUID organizationId, UUID id);

    List<EmployeeProfile> findByOrganizationIdAndIdIn(UUID organizationId, Collection<UUID> ids);

    Optional<EmployeeProfile> findByOrganizationIdAndMembershipId(UUID organizationId, UUID membershipId);

    boolean existsByOrganizationIdAndEmployeeNumber(UUID organizationId, String employeeNumber);

    boolean existsByOrganizationIdAndEmployeeNumberAndIdNot(UUID organizationId, String employeeNumber, UUID id);

    long countByOrganizationIdAndDepartmentId(UUID organizationId, UUID departmentId);

    long countByOrganizationIdAndPositionId(UUID organizationId, UUID positionId);

    long countByOrganizationIdAndDepartmentIdAndStatusNot(UUID organizationId, UUID departmentId, EmploymentStatus status);

    /** Reports that are still employed (a manager with such reports cannot be deactivated). */
    long countByOrganizationIdAndManagerIdAndStatusNot(UUID organizationId, UUID managerId, EmploymentStatus status);

    @Query("""
            select e from EmployeeProfile e
            where e.organizationId = :organizationId
              and (:status is null or e.status = :status)
              and (:departmentId is null or e.departmentId = :departmentId)
              and (:positionId is null or e.positionId = :positionId)
              and (:managerId is null or e.managerId = :managerId)
              and (:q is null or lower(e.displayName) like :q escape '!'
                   or lower(e.professionalEmail) like :q escape '!'
                   or lower(e.employeeNumber) like :q escape '!')""")
    Page<EmployeeProfile> searchByOrganization(@Param("organizationId") UUID organizationId,
            @Param("status") EmploymentStatus status, @Param("departmentId") UUID departmentId,
            @Param("positionId") UUID positionId, @Param("managerId") UUID managerId, @Param("q") String q,
            Pageable pageable);

    /** Rows of {@code [departmentId, count]} for employees that are still employed. */
    @Query("""
            select e.departmentId, count(e) from EmployeeProfile e
            where e.organizationId = :organizationId and e.departmentId is not null
              and e.status <> com.aiworkforce.core.workforce.EmploymentStatus.INACTIVE
            group by e.departmentId""")
    List<Object[]> countCurrentByOrganizationGroupedByDepartment(@Param("organizationId") UUID organizationId);

    /** Rows of {@code [positionId, count]} for employees that are still employed. */
    @Query("""
            select e.positionId, count(e) from EmployeeProfile e
            where e.organizationId = :organizationId and e.positionId is not null
              and e.status <> com.aiworkforce.core.workforce.EmploymentStatus.INACTIVE
            group by e.positionId""")
    List<Object[]> countCurrentByOrganizationGroupedByPosition(@Param("organizationId") UUID organizationId);

    /** Rows of {@code [managerId, count]} of reports that are still employed. */
    @Query("""
            select e.managerId, count(e) from EmployeeProfile e
            where e.organizationId = :organizationId and e.managerId in :managerIds
              and e.status <> com.aiworkforce.core.workforce.EmploymentStatus.INACTIVE
            group by e.managerId""")
    List<Object[]> countReportsByOrganizationGroupedByManager(@Param("organizationId") UUID organizationId,
            @Param("managerIds") Collection<UUID> managerIds);
}
