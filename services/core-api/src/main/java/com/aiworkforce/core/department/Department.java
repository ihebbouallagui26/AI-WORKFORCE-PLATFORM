package com.aiworkforce.core.department;

import com.aiworkforce.core.common.id.Ids;
import com.aiworkforce.core.workforce.ArchiveStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Organizational unit, hierarchical through {@code parentId}. Configurable per organization. */
@Entity
@Table(name = "departments")
public class Department {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String description;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "head_employee_id")
    private UUID headEmployeeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ArchiveStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Department() {
    }

    Department(UUID organizationId, String name, String code, String description, UUID parentId, Instant now) {
        this.id = Ids.newId();
        this.organizationId = organizationId;
        this.name = name;
        this.code = code;
        this.description = description;
        this.parentId = parentId;
        this.status = ArchiveStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void update(String name, String code, String description, Instant now) {
        this.name = name;
        this.code = code;
        this.description = description;
        this.updatedAt = now;
    }

    void moveTo(UUID parentId, Instant now) {
        this.parentId = parentId;
        this.updatedAt = now;
    }

    void assignHead(UUID employeeId, Instant now) {
        this.headEmployeeId = employeeId;
        this.updatedAt = now;
    }

    void archive(Instant now) {
        this.status = ArchiveStatus.ARCHIVED;
        this.updatedAt = now;
    }

    void restore(Instant now) {
        this.status = ArchiveStatus.ACTIVE;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getName() { return name; }
    public String getCode() { return code; }
    public String getDescription() { return description; }
    public UUID getParentId() { return parentId; }
    public UUID getHeadEmployeeId() { return headEmployeeId; }
    public ArchiveStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
