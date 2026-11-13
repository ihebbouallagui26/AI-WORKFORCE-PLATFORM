package com.aiworkforce.core.position;

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

/** Positions, always queried within one organization. */
@TenantScoped
interface PositionRepository extends JpaRepository<Position, UUID> {

    Optional<Position> findByOrganizationIdAndId(UUID organizationId, UUID id);

    List<Position> findByOrganizationIdAndIdIn(UUID organizationId, Collection<UUID> ids);

    long countByOrganizationId(UUID organizationId);

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);

    boolean existsByOrganizationIdAndCodeAndIdNot(UUID organizationId, String code, UUID id);

    boolean existsByOrganizationIdAndTitleIgnoreCase(UUID organizationId, String title);

    boolean existsByOrganizationIdAndTitleIgnoreCaseAndIdNot(UUID organizationId, String title, UUID id);

    @Query("""
            select p from Position p
            where p.organizationId = :organizationId
              and (:status is null or p.status = :status)
              and (:departmentId is null or p.departmentId = :departmentId)
              and (:q is null or lower(p.title) like :q escape '!' or lower(p.code) like :q escape '!')""")
    Page<Position> searchByOrganization(@Param("organizationId") UUID organizationId,
            @Param("status") ArchiveStatus status, @Param("departmentId") UUID departmentId,
            @Param("q") String q, Pageable pageable);
}
