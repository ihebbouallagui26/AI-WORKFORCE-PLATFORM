package com.aiworkforce.core.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Row of the global permission catalog (read-only at runtime, owned by migrations). */
@Entity
@Immutable
@Table(name = "permissions")
public class PermissionDefinition {

    @Id
    private String code;

    @Column(nullable = false)
    private String description;

    protected PermissionDefinition() {
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
