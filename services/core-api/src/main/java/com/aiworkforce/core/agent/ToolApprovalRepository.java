package com.aiworkforce.core.agent;

import com.aiworkforce.core.common.persistence.TenantScoped;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tool approvals, always queried within one organization (except the system-wide maintenance scans). */
@TenantScoped
interface ToolApprovalRepository extends JpaRepository<ToolApproval, UUID> {

    Optional<ToolApproval> findByOrganizationIdAndId(UUID organizationId, UUID id);

    Optional<ToolApproval> findByOrganizationIdAndInvocationId(UUID organizationId, UUID invocationId);

    /** Row lock: a decision, an expiry and a resume acknowledgement of one approval are serialized. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ToolApproval a where a.organizationId = :organizationId and a.id = :id")
    Optional<ToolApproval> lockByOrganizationAndId(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    @Query("""
            select a from ToolApproval a
            where a.organizationId = :organizationId
              and (:status is null or a.status = :status)
              and (:executionId is null or a.executionId = :executionId)""")
    Page<ToolApproval> searchByOrganization(@Param("organizationId") UUID organizationId,
            @Param("status") ApprovalStatus status, @Param("executionId") UUID executionId, Pageable pageable);

    /** Pending approvals whose time is up, across organizations (system maintenance only). */
    @Query("select a from ToolApproval a where a.status = com.aiworkforce.core.agent.ApprovalStatus.PENDING and a.expiresAt <= :now order by a.expiresAt")
    List<ToolApproval> findExpiredPendingOrganizationWide(@Param("now") Instant now, Pageable limit);

    /**
     * Open approvals (still pending, or decided but never taken by the orchestrator) whose execution is already
     * terminal: nobody can act on them any more (system maintenance only).
     */
    @Query("""
            select a from ToolApproval a
            where (a.status = com.aiworkforce.core.agent.ApprovalStatus.PENDING
                   or (a.status in (com.aiworkforce.core.agent.ApprovalStatus.APPROVED, com.aiworkforce.core.agent.ApprovalStatus.REJECTED)
                       and a.resumeAcknowledgedAt is null))
              and exists (select 1 from AgentExecution e where e.organizationId = a.organizationId and e.id = a.executionId
                          and e.status in (com.aiworkforce.core.agent.ExecutionStatus.SUCCEEDED, com.aiworkforce.core.agent.ExecutionStatus.FAILED))
            order by a.requestedAt""")
    List<ToolApproval> findOpenOfTerminalExecutionsOrganizationWide(Pageable limit);

    /** Decisions the orchestrator has not acknowledged yet, across organizations (outbox scan). */
    @Query("""
            select a from ToolApproval a
            where a.status in (com.aiworkforce.core.agent.ApprovalStatus.APPROVED, com.aiworkforce.core.agent.ApprovalStatus.REJECTED)
              and a.resumeAcknowledgedAt is null
              and (a.lastResumeAttemptAt is null or a.lastResumeAttemptAt < :retryBefore)
            order by a.decidedAt""")
    List<ToolApproval> findUnacknowledgedDecisionsOrganizationWide(@Param("retryBefore") Instant retryBefore, Pageable limit);
}
