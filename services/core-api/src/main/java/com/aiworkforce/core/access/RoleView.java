package com.aiworkforce.core.access;

import java.util.Set;
import java.util.UUID;

/** Read model of a role with its permissions (used by APIs and by other modules). */
public record RoleView(
        UUID id,
        UUID organizationId,
        BuiltInRole code,
        String name,
        String description,
        boolean builtIn,
        Set<Permission> permissions) {

    public boolean isOwnerRole() {
        return code == BuiltInRole.OWNER;
    }
}
