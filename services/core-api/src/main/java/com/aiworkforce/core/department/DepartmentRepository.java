package com.aiworkforce.core.department;

import com.aiworkforce.core.common.persistence.TenantScoped;
import com.aiworkforce.core.workforce.ArchiveStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Departments, always queried within one organization. */
@TenantScoped
interface DepartmentRepository extends JpaRepository<Department, UUID> {

    Optional<Department> findByOrganizationIdAndId(UUID organizationId, UUID id);

    List<Department> findByOrganizationIdAndIdIn(UUID organizationId, Collection<UUID> ids);

    List<Department> findByOrganizationId(UUID organizationId, Sort sort);

    long countByOrganizationId(UUID organizationId);

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);

    boolean existsByOrganizationIdAndCodeAndIdNot(UUID organizationId, String code, UUID id);

    boolean existsByOrganizationIdAndParentId(UUID organizationId, UUID parentId);

    boolean existsByOrganizationIdAndParentIdAndStatus(UUID organizationId, UUID parentId, ArchiveStatus status);

    boolean existsByOrganizationIdAndHeadEmployeeIdAndStatus(UUID organizationId, UUID headEmployeeId, ArchiveStatus status);

    /** Number of direct sub-departments per parent: rows of {@code [parentId, count]}. */
    @Query("""
            select d.parentId, count(d) from Department d
            where d.organizationId = :organizationId and d.parentId in :parentIds
            group by d.parentId""")
    List<Object[]> countChildrenByOrganization(@Param("organizationId") UUID organizationId,
            @Param("parentIds") Collection<UUID> parentIds);

    @Query("""
            select d from Department d
            where d.organizationId = :organizationId
              and (:status is null or d.status = :status)
              and (:parentId is null or d.parentId = :parentId)
              and (:rootOnly = false or d.parentId is null)
              and (:q is null or lower(d.name) like :q escape '!' or lower(d.code) like :q escape '!')""")
    Page<Department> searchByOrganization(@Param("organizationId") UUID organizationId,
            @Param("status") ArchiveStatus status, @Param("parentId") UUID parentId,
            @Param("rootOnly") boolean rootOnly, @Param("q") String q, Pageable pageable);
}
