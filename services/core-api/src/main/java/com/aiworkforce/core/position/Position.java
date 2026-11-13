package com.aiworkforce.core.position;

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

/** Professional job title. Not an authorization role: it never changes anyone's permissions. */
@Entity
@Table(name = "positions")
public class Position {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String description;

    @Column(name = "department_id")
    private UUID departmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ArchiveStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Position() {
    }

    Position(UUID organizationId, String title, String code, String description, UUID departmentId, Instant now) {
        this.id = Ids.newId();
        this.organizationId = organizationId;
        this.title = title;
        this.code = code;
        this.description = description;
        this.departmentId = departmentId;
        this.status = ArchiveStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void update(String title, String code, String description, Instant now) {
        this.title = title;
        this.code = code;
        this.description = description;
        this.updatedAt = now;
    }

    void assignDepartment(UUID departmentId, Instant now) {
        this.departmentId = departmentId;
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
    public String getTitle() { return title; }
    public String getCode() { return code; }
    public String getDescription() { return description; }
    public UUID getDepartmentId() { return departmentId; }
    public ArchiveStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
