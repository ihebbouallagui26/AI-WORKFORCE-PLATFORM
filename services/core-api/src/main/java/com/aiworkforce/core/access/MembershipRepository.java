package com.aiworkforce.core.access;

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

/** Memberships, always queried within one organization. */
@TenantScoped
interface MembershipRepository extends JpaRepository<Membership, UUID> {

    Optional<Membership> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    Optional<Membership> findByOrganizationIdAndId(UUID organizationId, UUID id);

    List<Membership> findByOrganizationIdAndIdIn(UUID organizationId, Collection<UUID> ids);

    Page<Membership> findByOrganizationIdAndStatusIn(
            UUID organizationId, Collection<Membership.Status> statuses, Pageable pageable);

    boolean existsByOrganizationIdAndUserIdInAndStatusIn(
            UUID organizationId, Collection<UUID> userIds, Collection<Membership.Status> statuses);

    /** Organizations in which the user has an ACTIVE membership (used to list "my organizations"). */
    @Query("""
            select m.organizationId from Membership m
            where m.userId = :userId and m.status = com.aiworkforce.core.access.Membership.Status.ACTIVE""")
    List<UUID> findActiveOrganizationIdsOfUser(@Param("userId") UUID userId);

    /**
     * Serialises membership and ownership changes of one organization (row lock until the end of
     * the transaction), so concurrent requests cannot both remove "the other" last owner.
     */
    @Query(value = "SELECT CAST(id AS text) FROM organizations WHERE id = :organizationId FOR UPDATE", nativeQuery = true)
    Optional<String> lockOrganization(@Param("organizationId") UUID organizationId);
}
