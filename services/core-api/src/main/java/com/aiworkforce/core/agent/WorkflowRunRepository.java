package com.aiworkforce.core.agent;

import com.aiworkforce.core.common.persistence.TenantScoped;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Workflow runs, always queried within one organization (except the system-wide scheduler scan). */
@TenantScoped
interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {

    Optional<WorkflowRun> findByOrganizationIdAndId(UUID organizationId, UUID id);

    /** Row lock: every transition and every step scheduling of one run is serialized (no duplicate step). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from WorkflowRun r where r.organizationId = :organizationId and r.id = :id")
    Optional<WorkflowRun> lockByOrganizationAndId(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    Optional<WorkflowRun> findByOrganizationIdAndRequestedByAndRequestId(UUID organizationId, UUID requestedBy, String requestId);

    @Query("""
            select r from WorkflowRun r
            where r.organizationId = :organizationId
              and (:status is null or r.status = :status)
              and (:definitionId is null or r.definitionId = :definitionId)""")
    Page<WorkflowRun> searchByOrganization(@Param("organizationId") UUID organizationId,
            @Param("status") WorkflowRunStatus status, @Param("definitionId") UUID definitionId, Pageable pageable);

    @Query("""
            select count(r) from WorkflowRun r
            where r.organizationId = :organizationId and r.status in (
                com.aiworkforce.core.agent.WorkflowRunStatus.PENDING, com.aiworkforce.core.agent.WorkflowRunStatus.RUNNING,
                com.aiworkforce.core.agent.WorkflowRunStatus.WAITING_APPROVAL, com.aiworkforce.core.agent.WorkflowRunStatus.WAITING_RECOVERY,
                com.aiworkforce.core.agent.WorkflowRunStatus.CANCEL_REQUESTED)""")
    long countOpenByOrganization(@Param("organizationId") UUID organizationId);

    /** Runs that still need the scheduler, across organizations (system maintenance only). */
    @Query("""
            select r from WorkflowRun r where r.status in (
                com.aiworkforce.core.agent.WorkflowRunStatus.PENDING, com.aiworkforce.core.agent.WorkflowRunStatus.RUNNING,
                com.aiworkforce.core.agent.WorkflowRunStatus.WAITING_APPROVAL, com.aiworkforce.core.agent.WorkflowRunStatus.WAITING_RECOVERY,
                com.aiworkforce.core.agent.WorkflowRunStatus.CANCEL_REQUESTED)
            order by r.updatedAt""")
    List<WorkflowRun> findOpenOrganizationWide(Pageable limit);
}
