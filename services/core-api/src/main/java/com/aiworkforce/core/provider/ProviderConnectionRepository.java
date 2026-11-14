package com.aiworkforce.core.provider;

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

/** Provider connections, always queried within one organization. */
@TenantScoped
interface ProviderConnectionRepository extends JpaRepository<ProviderConnection, UUID> {

    Optional<ProviderConnection> findByOrganizationIdAndId(UUID organizationId, UUID id);

    List<ProviderConnection> findByOrganizationIdAndIdIn(UUID organizationId, Collection<UUID> ids);

    long countByOrganizationId(UUID organizationId);

    boolean existsByOrganizationIdAndDisplayNameIgnoreCase(UUID organizationId, String displayName);

    boolean existsByOrganizationIdAndDisplayNameIgnoreCaseAndIdNot(UUID organizationId, String displayName, UUID id);

    @Query("""
            select c from ProviderConnection c
            where c.organizationId = :organizationId
              and (:type is null or c.providerType = :type)
              and (:status is null or c.status = :status)""")
    Page<ProviderConnection> searchByOrganization(@Param("organizationId") UUID organizationId,
            @Param("type") ProviderType type, @Param("status") ConnectionStatus status, Pageable pageable);
}
