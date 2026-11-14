package com.aiworkforce.core.access;

import java.util.EnumSet;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;

/**
 * Anti-escalation rules for granting and revoking roles and permissions.
 *
 * <ul>
 *   <li>The OWNER role can only be granted or revoked by someone holding {@code ownership.manage}
 *       (i.e. an OWNER) — an ADMIN cannot create owners.</li>
 *   <li>Any other role can only be granted or revoked by someone who holds <em>all</em> of its
 *       permissions: nobody can delegate a permission they do not have.</li>
 *   <li>{@code ownership.manage} can never be put in a custom role.</li>
 * </ul>
 */
final class RoleGrantPolicy {

    private RoleGrantPolicy() {
    }

    static void requireCanGrantOrRevoke(OrganizationAccess actor, RoleView role) {
        if (role.isOwnerRole()) {
            actor.require(Permission.OWNERSHIP_MANAGE);
            return;
        }
        if (!actor.hasAll(role.permissions())) {
            throw new AccessDeniedException("Cannot grant or revoke a role with permissions you do not hold");
        }
    }

    static void requireCanDefineCustomRole(OrganizationAccess actor, Set<Permission> permissions) {
        if (permissions.contains(Permission.OWNERSHIP_MANAGE)) {
            throw new AccessDeniedException("ownership.manage is reserved to the built-in OWNER role");
        }
        if (!actor.hasAll(permissions)) {
            Set<Permission> missing = EnumSet.noneOf(Permission.class);
            missing.addAll(permissions);
            missing.removeAll(actor.permissions());
            throw new AccessDeniedException("Cannot grant permissions you do not hold: " + missing.size());
        }
    }
}
