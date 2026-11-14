package com.aiworkforce.core.agent;

import com.aiworkforce.core.common.persistence.TenantScoped;
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
import jakarta.persistence.LockModeType;

/** Agent executions, always queried within one organization (except the system-wide stale reaper). */
@TenantScoped
interface ExecutionRepository extends JpaRepository<AgentExecution, UUID> {

    Optional<AgentExecution> findByOrganizationIdAndId(UUID organizationId, UUID id);

    /** Row lock: state transitions of one execution are serialized (no duplicate completion). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from AgentExecution e where e.organizationId = :organizationId and e.id = :id")
    Optional<AgentExecution> lockByOrganizationAndId(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    @Query("""
            select e from AgentExecution e
            where e.organizationId = :organizationId
              and (:agentId is null or e.agentId = :agentId)
              and (:status is null or e.status = :status)""")
    Page<AgentExecution> searchByOrganization(@Param("organizationId") UUID organizationId,
            @Param("agentId") UUID agentId, @Param("status") ExecutionStatus status, Pageable pageable);

    @Query("""
            select count(e) from AgentExecution e
            where e.organizationId = :organizationId and e.status in (
                com.aiworkforce.core.agent.ExecutionStatus.PENDING, com.aiworkforce.core.agent.ExecutionStatus.RUNNING)""")
    long countOpenByOrganization(@Param("organizationId") UUID organizationId);

    /** Open executions older than the cut-off, across organizations (system maintenance only). */
    @Query("""
            select e from AgentExecution e
            where e.status in (com.aiworkforce.core.agent.ExecutionStatus.PENDING,
                               com.aiworkforce.core.agent.ExecutionStatus.RUNNING)
              and e.createdAt < :cutoff
            order by e.createdAt""")
    List<AgentExecution> findStaleOpenExecutionsOrganizationWide(@Param("cutoff") Instant cutoff, Pageable limit);
}
