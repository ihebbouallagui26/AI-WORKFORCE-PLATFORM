package com.aiworkforce.core.access;

import com.aiworkforce.core.common.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Organization-scoped role. Built-in roles have a {@link BuiltInRole} code; custom roles do not. */
@Entity
@Table(name = "roles")
public class Role {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "code", updatable = false)
    private BuiltInRole code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(name = "built_in", nullable = false, updatable = false)
    private boolean builtIn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Role() {
    }

    private Role(UUID organizationId, BuiltInRole code, String name, String description, Instant now) {
        this.id = Ids.newId();
        this.organizationId = organizationId;
        this.code = code;
        this.name = name;
        this.description = description;
        this.builtIn = code != null;
        this.createdAt = now;
        this.updatedAt = now;
    }

    static Role builtIn(UUID organizationId, BuiltInRole code, Instant now) {
        return new Role(organizationId, code, code.displayName(), code.description(), now);
    }

    static Role custom(UUID organizationId, String name, String description, Instant now) {
        return new Role(organizationId, null, name, description, now);
    }

    void rename(String name, String description, Instant now) {
        this.name = name;
        this.description = description;
        this.updatedAt = now;
    }

    void touch(Instant now) {
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public BuiltInRole getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public boolean isOwnerRole() {
        return code == BuiltInRole.OWNER;
    }
}
