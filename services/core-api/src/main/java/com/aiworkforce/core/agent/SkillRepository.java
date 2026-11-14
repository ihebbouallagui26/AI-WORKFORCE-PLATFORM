package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.AgentParts.Skill;
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

/** Skills, always queried within one organization. */
@TenantScoped
interface SkillRepository extends JpaRepository<Skill, UUID> {

    Optional<Skill> findByOrganizationIdAndId(UUID organizationId, UUID id);

    List<Skill> findByOrganizationIdAndIdIn(UUID organizationId, Collection<UUID> ids);

    long countByOrganizationId(UUID organizationId);

    boolean existsByOrganizationIdAndNameIgnoreCase(UUID organizationId, String name);

    boolean existsByOrganizationIdAndNameIgnoreCaseAndIdNot(UUID organizationId, String name, UUID id);

    @Query("""
            select s from Skill s
            where s.organizationId = :organizationId
              and (:active is null or s.active = :active)
              and (:category is null or lower(s.category) = :category)
              and (:q is null or lower(s.name) like :q escape '!')""")
    Page<Skill> searchByOrganization(@Param("organizationId") UUID organizationId, @Param("active") Boolean active,
            @Param("category") String category, @Param("q") String q, Pageable pageable);
}
