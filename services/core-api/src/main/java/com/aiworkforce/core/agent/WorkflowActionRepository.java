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

/** Platform actions of coordinator turns, always queried within one organization. */
@TenantScoped
interface WorkflowActionRepository extends JpaRepository<WorkflowAction, UUID> {

    Optional<WorkflowAction> findByOrganizationIdAndExecutionIdAndToolCallId(UUID organizationId, UUID executionId, String toolCallId);

    @Query("select a from WorkflowAction a where a.organizationId = :organizationId and a.executionId = :executionId and a.status = 'ACCEPTED'")
    Optional<WorkflowAction> findAcceptedByOrganizationAndExecution(@Param("organizationId") UUID organizationId,
            @Param("executionId") UUID executionId);

    Page<WorkflowAction> findByOrganizationIdAndRunId(UUID organizationId, UUID runId, Pageable pageable);

    @Query("select count(a) > 0 from WorkflowAction a where a.organizationId = :organizationId and a.runId = :runId "
            + "and a.workerAgentId = :workerAgentId and a.taskHash = :taskHash and a.status = 'ACCEPTED' and a.action = 'workflow.delegate'")
    boolean existsAcceptedDelegationByOrganization(@Param("organizationId") UUID organizationId, @Param("runId") UUID runId,
            @Param("workerAgentId") UUID workerAgentId, @Param("taskHash") String taskHash);

    @Query("select count(a) from WorkflowAction a where a.organizationId = :organizationId and a.executionId = :executionId and a.status = 'REJECTED'")
    long countRejectedByOrganizationAndExecution(@Param("organizationId") UUID organizationId, @Param("executionId") UUID executionId);

    List<WorkflowAction> findByOrganizationIdAndRunIdOrderByCreatedAtAsc(UUID organizationId, UUID runId);
}
