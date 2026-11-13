package com.aiworkforce.core.access;

import java.util.EnumSet;
import java.util.Set;

/** Roles created with every organization. They cannot be modified or deleted. */
public enum BuiltInRole {

    /** Full administration, including ownership management. */
    OWNER("Owner", "Full organization administration, including ownership.", EnumSet.allOf(Permission.class)),

    /** Normal administration, without ownership management. */
    ADMIN("Administrator", "Organization administration without ownership management.",
            EnumSet.complementOf(EnumSet.of(Permission.OWNERSHIP_MANAGE))),

    /** Standard access. */
    MEMBER("Member", "Standard access to the organization.",
            EnumSet.of(Permission.ORGANIZATION_READ, Permission.MEMBER_READ, Permission.ROLE_READ));

    private final String displayName;
    private final String description;
    private final Set<Permission> permissions;

    BuiltInRole(String displayName, String description, Set<Permission> permissions) {
        this.displayName = displayName;
        this.description = description;
        this.permissions = Set.copyOf(permissions);
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
