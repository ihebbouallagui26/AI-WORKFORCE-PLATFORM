package com.aiworkforce.core.agent;

import com.aiworkforce.core.common.persistence.TenantScoped;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Agents, always queried within one organization. */
@TenantScoped
interface AgentRepository extends JpaRepository<Agent, UUID> {

    Optional<Agent> findByOrganizationIdAndId(UUID organizationId, UUID id);

    List<Agent> findByOrganizationIdAndIdIn(UUID organizationId, Collection<UUID> ids);

    long countByOrganizationId(UUID organizationId);

    boolean existsByOrganizationIdAndHandle(UUID organizationId, String handle);

    @Query("""
            select a from Agent a
            where a.organizationId = :organizationId
              and (:status is null or a.status = :status)
              and (:departmentId is null or a.departmentId = :departmentId)
              and (:q is null or lower(a.name) like :q escape '!' or lower(a.handle) like :q escape '!'
                   or lower(a.title) like :q escape '!')""")
    Page<Agent> searchByOrganization(@Param("organizationId") UUID organizationId, @Param("status") AgentStatus status,
            @Param("departmentId") UUID departmentId, @Param("q") String q, Pageable pageable);

    /** Rows of {@code [departmentId, count]} of agents that are not archived. */
    @Query("""
            select a.departmentId, count(a) from Agent a
            where a.organizationId = :organizationId and a.departmentId is not null
              and a.status <> com.aiworkforce.core.agent.AgentStatus.ARCHIVED
            group by a.departmentId""")
    List<Object[]> countLiveByOrganizationGroupedByDepartment(@Param("organizationId") UUID organizationId);

    long countByOrganizationIdAndDepartmentIdAndStatusNot(UUID organizationId, UUID departmentId, AgentStatus status);
}
