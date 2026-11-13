package com.aiworkforce.core.access;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;

/** Permission granted by a role. The organization id is stored for the composite foreign key. */
@Entity
@Table(name = "role_permissions")
public class RolePermission {

    @Embeddable
    public record Key(
            @Column(name = "role_id") UUID roleId,
            @Convert(converter = PermissionConverter.class) @Column(name = "permission_code") Permission permission)
            implements Serializable {
    }

    @EmbeddedId
    private Key id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    protected RolePermission() {
    }

    RolePermission(Role role, Permission permission) {
        this.id = new Key(role.getId(), permission);
        this.organizationId = role.getOrganizationId();
    }

    public Permission getPermission() {
        return id.permission();
    }

    public UUID getRoleId() {
        return id.roleId();
    }
}
