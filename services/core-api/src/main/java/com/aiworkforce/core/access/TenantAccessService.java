package com.aiworkforce.core.access;

import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.identity.AuthenticatedUser;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant check performed at the start of every organization-scoped operation:
 *
 * <ol>
 *   <li>the caller is already authenticated and resolved from {@code (iss, sub)};</li>
 *   <li>an ACTIVE membership of the caller in the requested organization must exist — otherwise
 *       404, so the existence of other organizations is not revealed (suspended and removed
 *       members get the same answer);</li>
 *   <li>the effective permissions are loaded from the database for this request;</li>
 *   <li>the required permission is checked (403 if missing).</li>
 * </ol>
 *
 * The organization id in the URL only <em>selects</em> an organization; this check decides
 * whether the selection is allowed.
 */
@Service
public class TenantAccessService {

    private final MembershipRepository memberships;
    private final MembershipRoleRepository membershipRoles;

    public TenantAccessService(MembershipRepository memberships, MembershipRoleRepository membershipRoles) {
        this.memberships = memberships;
        this.membershipRoles = membershipRoles;
    }

    @Transactional(readOnly = true)
    public OrganizationAccess requireMember(UUID organizationId, AuthenticatedUser user) {
        return findActiveAccess(organizationId, user.userId())
                .orElseThrow(() -> new ResourceNotFoundException("organization"));
    }

    /**
     * Current access of any user (not necessarily the caller), e.g. to re-check at acceptance
     * time that the author of an invitation is still allowed to grant its role.
     */
    @Transactional(readOnly = true)
    public Optional<OrganizationAccess> findActiveAccess(UUID organizationId, UUID userId) {
        return memberships.findByOrganizationIdAndUserId(organizationId, userId)
                .filter(m -> m.getStatus() == Membership.Status.ACTIVE)
                .map(membership -> {
                    Set<UUID> roleIds = membershipRoles
                            .findByOrganizationIdAndIdMembershipId(organizationId, membership.getId()).stream()
                            .map(MembershipRole::getRoleId).collect(Collectors.toSet());
                    Set<Permission> permissions =
                            membershipRoles.permissionsOfMembershipInOrganization(organizationId, membership.getId());
                    return new OrganizationAccess(organizationId, membership.getId(), userId, permissions, roleIds);
                });
    }

    @Transactional(readOnly = true)
    public OrganizationAccess require(UUID organizationId, AuthenticatedUser user, Permission permission) {
        OrganizationAccess access = requireMember(organizationId, user);
        access.require(permission);
        return access;
    }
}
