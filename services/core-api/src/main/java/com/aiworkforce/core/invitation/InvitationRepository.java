package com.aiworkforce.core.invitation;

import com.aiworkforce.core.common.persistence.TenantScoped;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Invitations, always queried within one organization — except the lookup by token hash. */
@TenantScoped
interface InvitationRepository extends JpaRepository<OrganizationInvitation, UUID> {

    Optional<OrganizationInvitation> findByOrganizationIdAndId(UUID organizationId, UUID id);

    Page<OrganizationInvitation> findByOrganizationIdAndStatus(
            UUID organizationId, OrganizationInvitation.Status status, Pageable pageable);

    Optional<OrganizationInvitation> findByOrganizationIdAndEmailAndStatus(
            UUID organizationId, String email, OrganizationInvitation.Status status);

    /**
     * Acceptance lookup. The token hash is the capability: whoever holds the token learns which
     * organization it belongs to. The row is locked so that a token can be consumed only once.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from OrganizationInvitation i where i.tokenHash = :tokenHash")
    Optional<OrganizationInvitation> findOrganizationInvitationByTokenHashForUpdate(
            @Param("tokenHash") String tokenHash);
}
