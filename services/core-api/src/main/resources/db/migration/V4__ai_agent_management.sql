-- V4 — AI agent management (Sprint B3): agents, provider connections and bindings, skills, tools,
-- memory configuration, team participation of agents. Model: docs/database/AI_AGENT_DATA_MODEL.md
--
-- An agent is an organization-owned AI identity. It is NOT a user account (no external identity,
-- no membership) and NOT an employee profile. Nothing here executes an agent: execution is Phase C.
--
-- Tenant isolation: every table carries organization_id and every relation between tenant tables is
-- a composite foreign key (organization_id, id).

-- ---------------------------------------------------------------- permissions
INSERT INTO permissions (code, description) VALUES
    ('agent.read',          'View AI agents, their configuration and history'),
    ('agent.create',        'Create AI agents'),
    ('agent.manage',        'Edit agents: identity, providers, skills, memory, department'),
    ('agent.activate',      'Activate and pause AI agents'),
    ('agent.archive',       'Archive AI agents'),
    ('agent.tool.manage',   'Grant and revoke tools to AI agents'),
    ('provider.read',       'View AI provider connections (never credentials)'),
    ('provider.connect',    'Create AI provider connections'),
    ('provider.manage',     'Edit, re-credential and revoke provider connections'),
    ('provider.test',       'Validate provider credentials and list models'),
    ('skill.read',          'View skills'),
    ('skill.manage',        'Create, edit and delete skills');

-- Built-in roles of existing organizations. OWNER and ADMIN: everything; MEMBER: read-only on agents
-- and skills (provider connections are not shown to plain members). Custom roles are never widened.
INSERT INTO role_permissions (role_id, organization_id, permission_code)
SELECT r.id, r.organization_id, p.code
FROM roles r
JOIN permissions p ON p.code IN (
    'agent.read', 'agent.create', 'agent.manage', 'agent.activate', 'agent.archive', 'agent.tool.manage',
    'provider.read', 'provider.connect', 'provider.manage', 'provider.test', 'skill.read', 'skill.manage')
WHERE r.code IN ('OWNER', 'ADMIN')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, organization_id, permission_code)
SELECT r.id, r.organization_id, p.code
FROM roles r
JOIN permissions p ON p.code IN ('agent.read', 'skill.read')
WHERE r.code = 'MEMBER'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- secrets (encrypted at rest)
-- Provider credentials are stored ONLY here, encrypted by the application (AES-256-GCM, random
-- nonce, organization and entry id bound as additional authenticated data). The master key never
-- reaches the database. Raw secrets never appear in any other table, log or API response.
CREATE TABLE secret_entries (
    id               uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    ciphertext       bytea       NOT NULL,
    nonce            bytea       NOT NULL,
    key_version      integer     NOT NULL DEFAULT 1,
    created_at       timestamptz NOT NULL,
    CONSTRAINT pk_secret_entries PRIMARY KEY (id),
    CONSTRAINT fk_secret_entries_organization_id FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT uq_secret_entries_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT ck_secret_entries_nonce CHECK (octet_length(nonce) = 12)
);

-- ---------------------------------------------------------------- provider connections
CREATE TABLE provider_connections (
    id                  uuid        NOT NULL,
    organization_id     uuid        NOT NULL,
    provider_type       text        NOT NULL,
    display_name        text        NOT NULL,
    account_identifier  text,
    status              text        NOT NULL DEFAULT 'PENDING',
    status_detail       text,
    credential_ref      uuid,
    last_tested_at      timestamptz,
    created_by          uuid        NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    version             bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_provider_connections PRIMARY KEY (id),
    CONSTRAINT fk_provider_connections_organization_id FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT uq_provider_connections_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT fk_provider_connections_credential FOREIGN KEY (organization_id, credential_ref)
        REFERENCES secret_entries (organization_id, id),
    CONSTRAINT fk_provider_connections_created_by FOREIGN KEY (created_by) REFERENCES user_accounts (id),
    CONSTRAINT ck_provider_connections_type CHECK (provider_type IN ('ANTHROPIC', 'OPENAI', 'GEMINI')),
    CONSTRAINT ck_provider_connections_status CHECK (status IN ('PENDING', 'VALID', 'INVALID', 'REVOKED')),
    CONSTRAINT ck_provider_connections_display_name CHECK (length(display_name) BETWEEN 2 AND 100),
    CONSTRAINT ck_provider_connections_account CHECK (account_identifier IS NULL OR length(account_identifier) <= 200),
    CONSTRAINT ck_provider_connections_detail CHECK (status_detail IS NULL OR length(status_detail) <= 300),
    -- A revoked connection holds no credential; any other connection holds one.
    CONSTRAINT ck_provider_connections_credential CHECK ((status = 'REVOKED') = (credential_ref IS NULL))
);
CREATE UNIQUE INDEX uq_provider_connections_organization_id_lower_name ON provider_connections (organization_id, lower(display_name));
CREATE INDEX ix_provider_connections_organization_id_type ON provider_connections (organization_id, provider_type);

-- ---------------------------------------------------------------- agents
CREATE TABLE agents (
    id                   uuid        NOT NULL,
    organization_id      uuid        NOT NULL,
    handle               text        NOT NULL,
    name                 text        NOT NULL,
    avatar_tone          smallint    NOT NULL DEFAULT 1,
    title                text        NOT NULL DEFAULT '',
    description          text        NOT NULL DEFAULT '',
    department_id        uuid,
    responsibilities     text[]      NOT NULL DEFAULT '{}',
    system_instructions  text        NOT NULL DEFAULT '',
    status               text        NOT NULL DEFAULT 'DRAFT',
    config_version       integer     NOT NULL DEFAULT 1,
    created_by           uuid        NOT NULL,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    version              bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_agents PRIMARY KEY (id),
    CONSTRAINT fk_agents_organization_id FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT uq_agents_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT uq_agents_organization_id_handle UNIQUE (organization_id, handle),
    CONSTRAINT fk_agents_department FOREIGN KEY (organization_id, department_id)
        REFERENCES departments (organization_id, id),
    CONSTRAINT fk_agents_created_by FOREIGN KEY (created_by) REFERENCES user_accounts (id),
    CONSTRAINT ck_agents_handle CHECK (handle ~ '^[a-z0-9][a-z0-9-]{1,39}$'),
    CONSTRAINT ck_agents_name CHECK (length(name) BETWEEN 2 AND 100),
    CONSTRAINT ck_agents_avatar_tone CHECK (avatar_tone BETWEEN 1 AND 8),
    CONSTRAINT ck_agents_title CHECK (length(title) <= 100),
    CONSTRAINT ck_agents_description CHECK (length(description) <= 1000),
    CONSTRAINT ck_agents_responsibilities CHECK (cardinality(responsibilities) <= 20),
    CONSTRAINT ck_agents_system_instructions CHECK (length(system_instructions) <= 8000),
    CONSTRAINT ck_agents_status CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'ARCHIVED')),
    CONSTRAINT ck_agents_config_version CHECK (config_version >= 1)
);
CREATE INDEX ix_agents_organization_id_status ON agents (organization_id, status);
CREATE INDEX ix_agents_organization_id_department_id ON agents (organization_id, department_id);
CREATE INDEX ix_agents_organization_id_lower_name ON agents (organization_id, lower(name));

-- ---------------------------------------------------------------- agent provider bindings
-- One PRIMARY provider per agent, optional ordered FALLBACKs. Provider-specific options are a small
-- JSON object validated against an allowlist by the application (never credentials).
CREATE TABLE agent_provider_bindings (
    id               uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    agent_id         uuid        NOT NULL,
    connection_id    uuid        NOT NULL,
    model_id         text        NOT NULL,
    role             text        NOT NULL,
    priority         integer     NOT NULL,
    config           text        NOT NULL DEFAULT '{}',
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_agent_provider_bindings PRIMARY KEY (id),
    CONSTRAINT fk_agent_provider_bindings_agent FOREIGN KEY (organization_id, agent_id)
        REFERENCES agents (organization_id, id),
    CONSTRAINT fk_agent_provider_bindings_connection FOREIGN KEY (organization_id, connection_id)
        REFERENCES provider_connections (organization_id, id),
    CONSTRAINT uq_agent_provider_bindings_priority UNIQUE (agent_id, priority),
    CONSTRAINT uq_agent_provider_bindings_target UNIQUE (agent_id, connection_id, model_id),
    CONSTRAINT ck_agent_provider_bindings_role CHECK (
        (role = 'PRIMARY' AND priority = 0) OR (role = 'FALLBACK' AND priority >= 1)),
    CONSTRAINT ck_agent_provider_bindings_model CHECK (model_id ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}$'),
    CONSTRAINT ck_agent_provider_bindings_config CHECK (length(config) <= 2000)
);
CREATE UNIQUE INDEX uq_agent_provider_bindings_primary ON agent_provider_bindings (agent_id) WHERE role = 'PRIMARY';
CREATE INDEX ix_agent_provider_bindings_organization_id_connection_id ON agent_provider_bindings (organization_id, connection_id);

-- ---------------------------------------------------------------- skills
CREATE TABLE skills (
    id               uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    name             text        NOT NULL,
    category         text        NOT NULL DEFAULT '',
    description      text        NOT NULL DEFAULT '',
    active           boolean     NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_skills PRIMARY KEY (id),
    CONSTRAINT fk_skills_organization_id FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT uq_skills_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT ck_skills_name CHECK (length(name) BETWEEN 2 AND 60),
    CONSTRAINT ck_skills_category CHECK (length(category) <= 40),
    CONSTRAINT ck_skills_description CHECK (length(description) <= 300)
);
CREATE UNIQUE INDEX uq_skills_organization_id_lower_name ON skills (organization_id, lower(name));

CREATE TABLE agent_skills (
    agent_id         uuid        NOT NULL,
    skill_id         uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    assigned_at      timestamptz NOT NULL,
    assigned_by      uuid,
    CONSTRAINT pk_agent_skills PRIMARY KEY (agent_id, skill_id),
    CONSTRAINT fk_agent_skills_agent FOREIGN KEY (organization_id, agent_id) REFERENCES agents (organization_id, id),
    CONSTRAINT fk_agent_skills_skill FOREIGN KEY (organization_id, skill_id) REFERENCES skills (organization_id, id),
    CONSTRAINT fk_agent_skills_assigned_by FOREIGN KEY (assigned_by) REFERENCES user_accounts (id)
);
CREATE INDEX ix_agent_skills_organization_id_skill_id ON agent_skills (organization_id, skill_id);

-- ---------------------------------------------------------------- tools
-- Global registry of tool definitions (metadata for future MCP integration), seeded here and
-- read-only at runtime. Defining a tool grants nothing: nothing is executed in B3.
CREATE TABLE tool_definitions (
    code         text        NOT NULL,
    name         text        NOT NULL,
    description  text        NOT NULL,
    category     text        NOT NULL,
    risk         text        NOT NULL,
    max_level    text        NOT NULL,
    mcp_server   text,
    mcp_tool     text,
    enabled      boolean     NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_tool_definitions PRIMARY KEY (code),
    CONSTRAINT ck_tool_definitions_code CHECK (code ~ '^[a-z][a-z0-9_.-]{1,59}$'),
    CONSTRAINT ck_tool_definitions_risk CHECK (risk IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_tool_definitions_max_level CHECK (max_level IN ('READ', 'WRITE', 'EXECUTE'))
);
INSERT INTO tool_definitions (code, name, description, category, risk, max_level, mcp_server, mcp_tool) VALUES
    ('project.files.read', 'Read project files',    'Read files of a project workspace',                    'Files',         'LOW',    'READ',    'filesystem', 'read_file'),
    ('code.analyze',       'Analyze code',          'Static analysis and code understanding (read only)',   'Code',          'LOW',    'READ',    NULL,         NULL),
    ('docs.search',        'Search documentation',  'Search organization and project documentation',        'Knowledge',     'LOW',    'READ',    NULL,         NULL),
    ('reports.create',     'Create reports',        'Create and update reports and documents',              'Reporting',     'MEDIUM', 'WRITE',   NULL,         NULL),
    ('tests.run',          'Run tests',             'Run the test suite of a project in a sandbox',         'Quality',       'MEDIUM', 'EXECUTE', NULL,         NULL),
    ('git.access',         'Access Git',            'Read history and propose changes in a Git repository', 'Source control', 'HIGH',  'WRITE',   'git',        NULL);

-- Tool grants: least privilege (an agent has none until granted). approval_required is forced to true
-- by the application for HIGH risk tools. project_id is reserved for per-project scope: projects do
-- not exist in the backend yet, so it must stay NULL (the constraint is relaxed when they do).
CREATE TABLE agent_tool_grants (
    agent_id           uuid        NOT NULL,
    tool_code          text        NOT NULL,
    organization_id    uuid        NOT NULL,
    level              text        NOT NULL,
    approval_required  boolean     NOT NULL DEFAULT true,
    project_id         uuid,
    granted_by         uuid,
    granted_at         timestamptz NOT NULL,
    CONSTRAINT pk_agent_tool_grants PRIMARY KEY (agent_id, tool_code),
    CONSTRAINT fk_agent_tool_grants_agent FOREIGN KEY (organization_id, agent_id) REFERENCES agents (organization_id, id),
    CONSTRAINT fk_agent_tool_grants_tool FOREIGN KEY (tool_code) REFERENCES tool_definitions (code),
    CONSTRAINT fk_agent_tool_grants_granted_by FOREIGN KEY (granted_by) REFERENCES user_accounts (id),
    CONSTRAINT ck_agent_tool_grants_level CHECK (level IN ('READ', 'WRITE', 'EXECUTE')),
    CONSTRAINT ck_agent_tool_grants_project CHECK (project_id IS NULL)
);
CREATE INDEX ix_agent_tool_grants_organization_id_tool_code ON agent_tool_grants (organization_id, tool_code);

-- ---------------------------------------------------------------- memory configuration
-- Configuration only: no embeddings, no retrieval. Disabled by default. The future memory engine
-- (PostgreSQL + pgvector, every row organization-scoped) reads this policy.
CREATE TABLE agent_memory_configs (
    agent_id         uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    enabled          boolean     NOT NULL DEFAULT false,
    scope            text        NOT NULL DEFAULT 'PROJECT',
    access_mode      text        NOT NULL DEFAULT 'AGENT_ONLY',
    retention_days   integer,
    updated_at       timestamptz NOT NULL,
    version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_agent_memory_configs PRIMARY KEY (agent_id),
    CONSTRAINT fk_agent_memory_configs_agent FOREIGN KEY (organization_id, agent_id) REFERENCES agents (organization_id, id),
    CONSTRAINT ck_agent_memory_configs_scope CHECK (scope IN ('PROJECT', 'ORGANIZATION')),
    CONSTRAINT ck_agent_memory_configs_access CHECK (access_mode IN ('AGENT_ONLY', 'TEAM_MEMBERS', 'ORGANIZATION_MEMBERS')),
    CONSTRAINT ck_agent_memory_configs_retention CHECK (retention_days IS NULL OR retention_days BETWEEN 1 AND 3650)
);

-- ---------------------------------------------------------------- agents in teams
-- Separate from team_members (humans): no polymorphic member_type/member_id.
CREATE TABLE team_agent_members (
    id               uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    team_id          uuid        NOT NULL,
    agent_id         uuid        NOT NULL,
    joined_at        timestamptz NOT NULL,
    left_at          timestamptz,
    added_by         uuid,
    CONSTRAINT pk_team_agent_members PRIMARY KEY (id),
    CONSTRAINT fk_team_agent_members_team FOREIGN KEY (organization_id, team_id) REFERENCES teams (organization_id, id),
    CONSTRAINT fk_team_agent_members_agent FOREIGN KEY (organization_id, agent_id) REFERENCES agents (organization_id, id),
    CONSTRAINT fk_team_agent_members_added_by FOREIGN KEY (added_by) REFERENCES user_accounts (id),
    CONSTRAINT ck_team_agent_members_dates CHECK (left_at IS NULL OR left_at >= joined_at)
);
CREATE UNIQUE INDEX uq_team_agent_members_current ON team_agent_members (team_id, agent_id) WHERE left_at IS NULL;
CREATE INDEX ix_team_agent_members_organization_id_team_id ON team_agent_members (organization_id, team_id);
CREATE INDEX ix_team_agent_members_organization_id_agent_id ON team_agent_members (organization_id, agent_id);
