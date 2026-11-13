package com.aiworkforce.core.access;

import com.aiworkforce.core.common.persistence.TenantScoped;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Roles, always queried within one organization. */
@TenantScoped
interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByOrganizationIdAndId(UUID organizationId, UUID id);

    Optional<Role> findByOrganizationIdAndCode(UUID organizationId, BuiltInRole code);

    List<Role> findByOrganizationIdOrderByBuiltInDescNameAsc(UUID organizationId);

    List<Role> findByOrganizationIdAndIdIn(UUID organizationId, Collection<UUID> ids);

    boolean existsByOrganizationIdAndNameIgnoreCase(UUID organizationId, String name);

    boolean existsByOrganizationIdAndNameIgnoreCaseAndIdNot(UUID organizationId, String name, UUID id);

    long countByOrganizationIdAndBuiltInFalse(UUID organizationId);
}
