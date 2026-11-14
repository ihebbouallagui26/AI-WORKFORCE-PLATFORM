package com.aiworkforce.core.agent;

import com.aiworkforce.core.common.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An organization-owned AI identity. It is not a user account and not an employee profile: it has
 * no login, no membership and no credentials of its own. {@code configVersion} counts configuration
 * changes (identity, providers, skills, tools, memory, department) for the history.
 */
@Entity
@Table(name = "agents")
public class Agent {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String handle;

    @Column(nullable = false)
    private String name;

    @Column(name = "avatar_tone", nullable = false)
    private short avatarTone;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String description;

    @Column(name = "department_id")
    private UUID departmentId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private List<String> responsibilities;

    @Column(name = "system_instructions", nullable = false)
    private String systemInstructions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentStatus status;

    @Column(name = "config_version", nullable = false)
    private int configVersion;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Agent() {
    }

    Agent(UUID organizationId, String handle, String name, int avatarTone, String title, String description,
            UUID departmentId, List<String> responsibilities, String systemInstructions, UUID createdBy, Instant now) {
        this.id = Ids.newId();
        this.organizationId = organizationId;
        this.handle = handle;
        this.name = name;
        this.avatarTone = (short) avatarTone;
        this.title = title;
        this.description = description;
        this.departmentId = departmentId;
        this.responsibilities = List.copyOf(responsibilities);
        this.systemInstructions = systemInstructions;
        this.status = AgentStatus.DRAFT;
        this.configVersion = 1;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void updateIdentity(String name, int avatarTone, String title, String description, List<String> responsibilities,
            String systemInstructions, Instant now) {
        this.name = name;
        this.avatarTone = (short) avatarTone;
        this.title = title;
        this.description = description;
        this.responsibilities = List.copyOf(responsibilities);
        this.systemInstructions = systemInstructions;
        touch(now);
    }

    void assignDepartment(UUID departmentId, Instant now) {
        this.departmentId = departmentId;
        touch(now);
    }

    void changeStatus(AgentStatus status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }

    /** Records one configuration change. */
    void touch(Instant now) {
        this.configVersion++;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getHandle() { return handle; }
    public String getName() { return name; }
    public int getAvatarTone() { return avatarTone; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public UUID getDepartmentId() { return departmentId; }
    public List<String> getResponsibilities() { return responsibilities; }
    String getSystemInstructions() { return systemInstructions; }
    public AgentStatus getStatus() { return status; }
    public int getConfigVersion() { return configVersion; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
