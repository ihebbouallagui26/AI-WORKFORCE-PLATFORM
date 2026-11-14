package com.aiworkforce.core.agent;

import com.aiworkforce.core.common.persistence.TenantScoped;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Workflow definitions, always queried within one organization. */
@TenantScoped
interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {

    Optional<WorkflowDefinition> findByOrganizationIdAndId(UUID organizationId, UUID id);

    /** Row lock: edits and run starts of one definition are serialized, so a run snapshots a consistent version. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from WorkflowDefinition d where d.organizationId = :organizationId and d.id = :id")
    Optional<WorkflowDefinition> lockByOrganizationAndId(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    @Query("""
            select d from WorkflowDefinition d
            where d.organizationId = :organizationId and (:status is null or d.status = :status)""")
    Page<WorkflowDefinition> searchByOrganization(@Param("organizationId") UUID organizationId,
            @Param("status") WorkflowDefinitionStatus status, Pageable pageable);
}
