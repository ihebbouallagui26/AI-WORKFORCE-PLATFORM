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

/** Tool invocations, always queried within one organization. */
@TenantScoped
interface ToolInvocationRepository extends JpaRepository<ToolInvocation, UUID> {

    Optional<ToolInvocation> findByOrganizationIdAndId(UUID organizationId, UUID id);

    /** Row lock: the transitions of one invocation are serialized (single winner for begin/complete). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ToolInvocation i where i.organizationId = :organizationId and i.id = :id")
    Optional<ToolInvocation> lockByOrganizationAndId(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    Optional<ToolInvocation> findByOrganizationIdAndExecutionIdAndToolCallId(UUID organizationId, UUID executionId,
            String toolCallId);

    List<ToolInvocation> findByOrganizationIdAndExecutionIdOrderBySequenceAsc(UUID organizationId, UUID executionId);

    /** Calls still RUNNING although their execution already ended: nobody will ever complete them (system maintenance only). */
    @Query("""
            select i from ToolInvocation i
            where i.status = com.aiworkforce.core.agent.InvocationStatus.RUNNING
              and exists (select 1 from AgentExecution e where e.organizationId = i.organizationId and e.id = i.executionId
                          and e.status in (com.aiworkforce.core.agent.ExecutionStatus.SUCCEEDED, com.aiworkforce.core.agent.ExecutionStatus.FAILED))
            order by i.startedAt""")
    List<ToolInvocation> findRunningOfTerminalExecutionsOrganizationWide(org.springframework.data.domain.Pageable limit);

    long countByOrganizationIdAndExecutionId(UUID organizationId, UUID executionId);

    /** True when a call of this execution ever started: an external effect may exist, so it is never retried. */
    @Query("select count(i) > 0 from ToolInvocation i where i.organizationId = :organizationId "
            + "and i.executionId = :executionId and i.startedAt is not null")
    boolean existsStartedByOrganizationAndExecution(@Param("organizationId") UUID organizationId,
            @Param("executionId") UUID executionId);

    /** True while a call of this execution is RUNNING (its outcome is not known yet). */
    @Query("select count(i) > 0 from ToolInvocation i where i.organizationId = :organizationId "
            + "and i.executionId = :executionId and i.status = com.aiworkforce.core.agent.InvocationStatus.RUNNING")
    boolean existsRunningByOrganizationAndExecution(@Param("organizationId") UUID organizationId,
            @Param("executionId") UUID executionId);

    @Query("""
            select i from ToolInvocation i
            where i.organizationId = :organizationId and i.executionId = :executionId""")
    Page<ToolInvocation> pageByOrganizationAndExecution(@Param("organizationId") UUID organizationId,
            @Param("executionId") UUID executionId, Pageable pageable);

    @Query("""
            select i from ToolInvocation i
            where i.organizationId = :organizationId
              and (:agentId is null or i.agentId = :agentId)
              and (:status is null or i.status = :status)""")
    Page<ToolInvocation> searchByOrganization(@Param("organizationId") UUID organizationId,
            @Param("agentId") UUID agentId, @Param("status") InvocationStatus status, Pageable pageable);
}
