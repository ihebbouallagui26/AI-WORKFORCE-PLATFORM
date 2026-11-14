package com.aiworkforce.core.agent;

import com.aiworkforce.core.common.persistence.TenantScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Steps of workflow runs, always queried within one organization. */
@TenantScoped
interface WorkflowStepRepository extends JpaRepository<WorkflowStep, UUID> {

    Page<WorkflowStep> findByOrganizationIdAndRunId(UUID organizationId, UUID runId, Pageable pageable);

    List<WorkflowStep> findByOrganizationIdAndRunIdOrderByStepRankAscAttemptAsc(UUID organizationId, UUID runId);

    Optional<WorkflowStep> findByOrganizationIdAndExecutionId(UUID organizationId, UUID executionId);

    /** The most recent attempt of the highest rank scheduled so far (the one that decides what happens next). */
    @Query("""
            select s from WorkflowStep s where s.organizationId = :organizationId and s.runId = :runId
            order by s.stepRank desc, s.attempt desc""")
    List<WorkflowStep> findLatestByOrganizationAndRun(@Param("organizationId") UUID organizationId,
            @Param("runId") UUID runId, Pageable limit);
}
