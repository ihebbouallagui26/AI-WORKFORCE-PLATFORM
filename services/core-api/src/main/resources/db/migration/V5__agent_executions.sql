-- V5 — Supervised AI agent executions (Sprint C1).
--
-- An execution is ONE human-initiated run of ONE agent for ONE organization (text generation only:
-- no tool, shell, file or MCP action). The Core API owns this record; the Python orchestrator
-- changes it only through authenticated internal endpoints, never directly.
--
-- State machine (enforced by the application, guarded by CHECK constraints):
--   PENDING -> RUNNING -> SUCCEEDED | FAILED      PENDING -> FAILED (dispatch failure, timeout)
-- Terminal states are final. Nothing here stores a provider credential: only the connection id.

-- ---------------------------------------------------------------- permissions
INSERT INTO permissions (code, description) VALUES
    ('agent.execute',        'Launch a supervised execution of an active AI agent'),
    ('agent.execution.read', 'View agent executions: instructions given and results produced');

-- OWNER and ADMIN only. Plain members and custom roles are never widened.
INSERT INTO role_permissions (role_id, organization_id, permission_code)
SELECT r.id, r.organization_id, p.code
FROM roles r
JOIN permissions p ON p.code IN ('agent.execute', 'agent.execution.read')
WHERE r.code IN ('OWNER', 'ADMIN')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- executions
CREATE TABLE agent_executions (
    id                     uuid         NOT NULL,
    organization_id        uuid         NOT NULL,
    agent_id               uuid         NOT NULL,
    requested_by           uuid         NOT NULL,
    task                   text         NOT NULL,
    status                 varchar(16)  NOT NULL,
    provider_type          varchar(16)  NOT NULL,
    connection_id          uuid         NOT NULL,
    model_id               varchar(128) NOT NULL,
    result                 text,
    input_tokens           integer,
    output_tokens          integer,
    error_code             varchar(40),
    error_message          varchar(300),
    correlation_id         varchar(64),
    credential_resolved_at timestamptz,
    created_at             timestamptz  NOT NULL,
    started_at             timestamptz,
    completed_at           timestamptz,
    updated_at             timestamptz  NOT NULL,
    version                bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_agent_executions PRIMARY KEY (id),
    CONSTRAINT uq_agent_executions_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT fk_agent_executions_organization_id FOREIGN KEY (organization_id) REFERENCES organizations (id),
    -- The agent must belong to the same organization.
    CONSTRAINT fk_agent_executions_agent FOREIGN KEY (organization_id, agent_id) REFERENCES agents (organization_id, id),
    CONSTRAINT fk_agent_executions_requested_by FOREIGN KEY (requested_by) REFERENCES user_accounts (id),
    CONSTRAINT fk_agent_executions_connection FOREIGN KEY (organization_id, connection_id)
        REFERENCES provider_connections (organization_id, id),
    CONSTRAINT ck_agent_executions_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_agent_executions_task CHECK (char_length(btrim(task)) BETWEEN 1 AND 8000),
    -- Terminal states carry a completion time; a result only exists on success, an error only on failure.
    CONSTRAINT ck_agent_executions_completed CHECK ((status IN ('SUCCEEDED', 'FAILED')) = (completed_at IS NOT NULL)),
    CONSTRAINT ck_agent_executions_result CHECK (result IS NULL OR status = 'SUCCEEDED'),
    CONSTRAINT ck_agent_executions_error CHECK ((error_code IS NOT NULL) = (status = 'FAILED')),
    CONSTRAINT ck_agent_executions_started CHECK (status = 'PENDING' OR started_at IS NOT NULL OR status = 'FAILED'),
    CONSTRAINT ck_agent_executions_usage CHECK (
        (input_tokens IS NULL OR input_tokens >= 0) AND (output_tokens IS NULL OR output_tokens >= 0))
);

CREATE INDEX ix_agent_executions_agent_created ON agent_executions (organization_id, agent_id, created_at DESC);
CREATE INDEX ix_agent_executions_org_created ON agent_executions (organization_id, created_at DESC);
-- Used by the concurrency limit and the stale-execution reaper.
CREATE INDEX ix_agent_executions_open ON agent_executions (organization_id, created_at) WHERE status IN ('PENDING', 'RUNNING');
