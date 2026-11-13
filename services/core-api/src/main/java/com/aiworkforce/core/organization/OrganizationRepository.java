package com.aiworkforce.core.organization;

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Organizations (tenant roots). Callers always pass ids that the tenant check has already
 * authorised (see {@link OrganizationService}); this repository is never exposed to other modules.
 */
interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Page<Organization> findByIdIn(Collection<UUID> ids, Pageable pageable);
}
