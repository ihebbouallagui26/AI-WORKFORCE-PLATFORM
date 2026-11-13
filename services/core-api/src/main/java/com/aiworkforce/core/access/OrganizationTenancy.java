package com.aiworkforce.core.access;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access-control bootstrap of a new organization, run in the transaction that creates the
 * organization: built-in roles plus the creator's ACTIVE membership with the OWNER role.
 * Either everything is committed with the organization, or nothing is.
 */
@Service
public class OrganizationTenancy {

    private final RoleService roles;
    private final MembershipService memberships;

    public OrganizationTenancy(RoleService roles, MembershipService memberships) {
        this.roles = roles;
        this.memberships = memberships;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void bootstrap(UUID organizationId, UUID ownerUserId) {
        Map<BuiltInRole, Role> builtIns = roles.createBuiltInRoles(organizationId);
        memberships.createOwnerMembership(organizationId, ownerUserId, builtIns.get(BuiltInRole.OWNER));
    }
}
