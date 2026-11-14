package com.aiworkforce.core.agent;

import com.aiworkforce.core.common.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Persistent parts of an agent's configuration. Nested entities keep the (many) small mappings in
 * one place; each table is organization-scoped through composite foreign keys.
 */
public final class AgentParts {

    private AgentParts() {
    }

    // ------------------------------------------------------------------ provider binding

    @Entity(name = "ProviderBinding")
    @Table(name = "agent_provider_bindings")
    public static class ProviderBinding {

        @Id
        private UUID id;

        @Column(name = "organization_id", nullable = false, updatable = false)
        private UUID organizationId;

        @Column(name = "agent_id", nullable = false, updatable = false)
        private UUID agentId;

        @Column(name = "connection_id", nullable = false, updatable = false)
        private UUID connectionId;

        @Column(name = "model_id", nullable = false)
        private String modelId;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private BindingRole role;

        @Column(nullable = false)
        private int priority;

        @Column(nullable = false)
        private String config;

        @Column(name = "created_at", nullable = false, updatable = false)
        private Instant createdAt;

        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt;

        @Version
        private long version;

        protected ProviderBinding() {
        }

        ProviderBinding(UUID organizationId, UUID agentId, UUID connectionId, String modelId, BindingRole role,
                int priority, String config, Instant now) {
            this.id = Ids.newId();
            this.organizationId = organizationId;
            this.agentId = agentId;
            this.connectionId = connectionId;
            this.modelId = modelId;
            this.role = role;
            this.priority = priority;
            this.config = config;
            this.createdAt = now;
            this.updatedAt = now;
        }

        void update(String modelId, String config, Instant now) {
            this.modelId = modelId;
            this.config = config;
            this.updatedAt = now;
        }

        void reposition(BindingRole role, int priority, Instant now) {
            this.role = role;
            this.priority = priority;
            this.updatedAt = now;
        }

        public UUID getId() { return id; }
        public UUID getAgentId() { return agentId; }
        public UUID getConnectionId() { return connectionId; }
        public String getModelId() { return modelId; }
        public BindingRole getRole() { return role; }
        public int getPriority() { return priority; }
        public String getConfig() { return config; }
        public long getVersion() { return version; }
    }

    // ------------------------------------------------------------------ skill

    @Entity(name = "Skill")
    @Table(name = "skills")
    public static class Skill {

        @Id
        private UUID id;

        @Column(name = "organization_id", nullable = false, updatable = false)
        private UUID organizationId;

        @Column(nullable = false)
        private String name;

        @Column(nullable = false)
        private String category;

        @Column(nullable = false)
        private String description;

        @Column(nullable = false)
        private boolean active;

        @Column(name = "created_at", nullable = false, updatable = false)
        private Instant createdAt;

        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt;

        @Version
        private long version;

        protected Skill() {
        }

        Skill(UUID organizationId, String name, String category, String description, Instant now) {
            this.id = Ids.newId();
            this.organizationId = organizationId;
            this.name = name;
            this.category = category;
            this.description = description;
            this.active = true;
            this.createdAt = now;
            this.updatedAt = now;
        }

        void update(String name, String category, String description, boolean active, Instant now) {
            this.name = name;
            this.category = category;
            this.description = description;
            this.active = active;
            this.updatedAt = now;
        }

        public UUID getId() { return id; }
        public UUID getOrganizationId() { return organizationId; }
        public String getName() { return name; }
        public String getCategory() { return category; }
        public String getDescription() { return description; }
        public boolean isActive() { return active; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
        public long getVersion() { return version; }
    }

    @Entity(name = "AgentSkill")
    @Table(name = "agent_skills")
    public static class AgentSkill {

        @Embeddable
        public record Key(@Column(name = "agent_id") UUID agentId, @Column(name = "skill_id") UUID skillId)
                implements Serializable {
        }

        @EmbeddedId
        private Key id;

        @Column(name = "organization_id", nullable = false, updatable = false)
        private UUID organizationId;

        @Column(name = "assigned_at", nullable = false, updatable = false)
        private Instant assignedAt;

        @Column(name = "assigned_by", updatable = false)
        private UUID assignedBy;

        protected AgentSkill() {
        }

        AgentSkill(UUID organizationId, UUID agentId, UUID skillId, UUID assignedBy, Instant now) {
            this.id = new Key(agentId, skillId);
            this.organizationId = organizationId;
            this.assignedBy = assignedBy;
            this.assignedAt = now;
        }

        public UUID getAgentId() { return id.agentId(); }
        public UUID getSkillId() { return id.skillId(); }
    }

    // ------------------------------------------------------------------ tools

    /** Global registry entry (metadata for future MCP integration); read-only. */
    @Entity(name = "ToolDefinition")
    @Immutable
    @Table(name = "tool_definitions")
    public static class ToolDefinition {

        @Id
        private String code;

        @Column(nullable = false)
        private String name;

        @Column(nullable = false)
        private String description;

        @Column(nullable = false)
        private String category;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private ToolRisk risk;

        @Enumerated(EnumType.STRING)
        @Column(name = "max_level", nullable = false)
        private ToolLevel maxLevel;

        @Column(name = "mcp_server")
        private String mcpServer;

        @Column(name = "mcp_tool")
        private String mcpTool;

        @Column(nullable = false)
        private boolean enabled;

        protected ToolDefinition() {
        }

        public String getCode() { return code; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public String getCategory() { return category; }
        public ToolRisk getRisk() { return risk; }
        public ToolLevel getMaxLevel() { return maxLevel; }
        public String getMcpServer() { return mcpServer; }
        public String getMcpTool() { return mcpTool; }
        public boolean isEnabled() { return enabled; }
    }

    @Entity(name = "ToolGrant")
    @Table(name = "agent_tool_grants")
    public static class ToolGrant {

        @Embeddable
        public record Key(@Column(name = "agent_id") UUID agentId, @Column(name = "tool_code") String toolCode)
                implements Serializable {
        }

        @EmbeddedId
        private Key id;

        @Column(name = "organization_id", nullable = false, updatable = false)
        private UUID organizationId;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private ToolLevel level;

        @Column(name = "approval_required", nullable = false)
        private boolean approvalRequired;

        @Column(name = "granted_by", updatable = false)
        private UUID grantedBy;

        @Column(name = "granted_at", nullable = false, updatable = false)
        private Instant grantedAt;

        protected ToolGrant() {
        }

        ToolGrant(UUID organizationId, UUID agentId, String toolCode, ToolLevel level, boolean approvalRequired,
                UUID grantedBy, Instant now) {
            this.id = new Key(agentId, toolCode);
            this.organizationId = organizationId;
            this.level = level;
            this.approvalRequired = approvalRequired;
            this.grantedBy = grantedBy;
            this.grantedAt = now;
        }

        public UUID getAgentId() { return id.agentId(); }
        public String getToolCode() { return id.toolCode(); }
        public ToolLevel getLevel() { return level; }
        public boolean isApprovalRequired() { return approvalRequired; }
    }

    // ------------------------------------------------------------------ memory

    /** Memory policy only: no embeddings, no retrieval. Disabled by default. */
    @Entity(name = "MemoryConfig")
    @Table(name = "agent_memory_configs")
    public static class MemoryConfig {

        @Id
        @Column(name = "agent_id")
        private UUID agentId;

        @Column(name = "organization_id", nullable = false, updatable = false)
        private UUID organizationId;

        @Column(nullable = false)
        private boolean enabled;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private MemoryScope scope;

        @Enumerated(EnumType.STRING)
        @Column(name = "access_mode", nullable = false)
        private MemoryAccess accessMode;

        @Column(name = "retention_days")
        private Integer retentionDays;

        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt;

        @Version
        private long version;

        protected MemoryConfig() {
        }

        MemoryConfig(UUID organizationId, UUID agentId, Instant now) {
            this.organizationId = organizationId;
            this.agentId = agentId;
            this.enabled = false;
            this.scope = MemoryScope.PROJECT;
            this.accessMode = MemoryAccess.AGENT_ONLY;
            this.updatedAt = now;
        }

        void update(boolean enabled, MemoryScope scope, MemoryAccess accessMode, Integer retentionDays, Instant now) {
            this.enabled = enabled;
            this.scope = scope;
            this.accessMode = accessMode;
            this.retentionDays = retentionDays;
            this.updatedAt = now;
        }

        public UUID getAgentId() { return agentId; }
        public boolean isEnabled() { return enabled; }
        public MemoryScope getScope() { return scope; }
        public MemoryAccess getAccessMode() { return accessMode; }
        public Integer getRetentionDays() { return retentionDays; }
        public Instant getUpdatedAt() { return updatedAt; }
    }

    // ------------------------------------------------------------------ team participation

    /** An AI agent in a team, with history. Separate from human team members: no polymorphic reference. */
    @Entity(name = "TeamAgentMember")
    @Table(name = "team_agent_members")
    public static class TeamAgentMember {

        @Id
        private UUID id;

        @Column(name = "organization_id", nullable = false, updatable = false)
        private UUID organizationId;

        @Column(name = "team_id", nullable = false, updatable = false)
        private UUID teamId;

        @Column(name = "agent_id", nullable = false, updatable = false)
        private UUID agentId;

        @Column(name = "joined_at", nullable = false, updatable = false)
        private Instant joinedAt;

        @Column(name = "left_at")
        private Instant leftAt;

        @Column(name = "added_by", updatable = false)
        private UUID addedBy;

        protected TeamAgentMember() {
        }

        TeamAgentMember(UUID organizationId, UUID teamId, UUID agentId, UUID addedBy, Instant now) {
            this.id = Ids.newId();
            this.organizationId = organizationId;
            this.teamId = teamId;
            this.agentId = agentId;
            this.addedBy = addedBy;
            this.joinedAt = now;
        }

        void leave(Instant now) {
            this.leftAt = now;
        }

        public UUID getTeamId() { return teamId; }
        public UUID getAgentId() { return agentId; }
        public Instant getJoinedAt() { return joinedAt; }
        public Instant getLeftAt() { return leftAt; }
    }
}
