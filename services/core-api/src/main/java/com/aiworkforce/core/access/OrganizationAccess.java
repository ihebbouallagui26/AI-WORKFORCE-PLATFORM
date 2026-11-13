package com.aiworkforce.core.access;

import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;

/**
 * Result of the tenant check for one request: the caller is an ACTIVE member of
 * {@code organizationId}, with the permissions computed from the database at that moment.
 * Only {@link TenantAccessService} creates instances.
 */
public record OrganizationAccess(
        UUID organizationId,
        UUID membershipId,
        UUID userId,
        Set<Permission> permissions,
        Set<UUID> roleIds) {

    public OrganizationAccess {
        permissions = Set.copyOf(permissions);
        roleIds = Set.copyOf(roleIds);
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    public boolean hasAll(Set<Permission> required) {
        return permissions.containsAll(required);
    }

    public void require(Permission permission) {
        if (!has(permission)) {
            throw new AccessDeniedException("Missing permission " + permission.code());
        }
    }
}
