-- V6 — Controlled tool execution and human approval (Sprint C2).
--
-- * The B3 tool registry gains the facts the platform needs to DECIDE about a call: input schema,
--   execution policy, required level, timeout, output limit, policy version. The default policy is
--   DENIED: a tool must be explicitly enabled for execution by a migration.
-- * tool_invocations: every tool call an agent requests (allowed, denied or waiting), with encrypted
--   arguments (secret_entries, AES-256-GCM), a canonical argument hash and a bounded result.
-- * tool_approvals: the human decision bound to ONE exact invocation (execution, agent, tool, call id,
--   arguments hash, policy version). It doubles as the durable outbox of the resume notification.
-- * agent_executions may now be WAITING_APPROVAL (all historical states stay valid).
-- Every table is organization-scoped with composite foreign keys. V1..V5 are untouched.

-- ---------------------------------------------------------------- permissions
INSERT INTO permissions (code, description) VALUES
    ('approval.read',   'View tool approval requests of AI agent executions'),
    ('approval.decide', 'Approve or reject a tool action requested by an AI agent');

-- OWNER and ADMIN only: plain members and custom roles are never widened.
INSERT INTO role_permissions (role_id, organization_id, permission_code)
SELECT r.id, r.organization_id, p.code
FROM roles r
JOIN permissions p ON p.code IN ('approval.read', 'approval.decide')
WHERE r.code IN ('OWNER', 'ADMIN')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- tool registry (global, read-only for the runtime role)
ALTER TABLE tool_definitions
    ADD COLUMN input_schema      text    NOT NULL DEFAULT '{"type":"object","additionalProperties":false}',
    ADD COLUMN execution_policy  text    NOT NULL DEFAULT 'DENIED',
    ADD COLUMN required_level    text    NOT NULL DEFAULT 'READ',
    ADD COLUMN timeout_seconds   integer NOT NULL DEFAULT 15,
    ADD COLUMN max_output_bytes  integer NOT NULL DEFAULT 8192,
    ADD COLUMN policy_version    integer NOT NULL DEFAULT 1;
ALTER TABLE tool_definitions
    ADD CONSTRAINT ck_tool_definitions_policy CHECK (execution_policy IN ('READ_ONLY', 'APPROVAL_REQUIRED', 'DENIED')),
    ADD CONSTRAINT ck_tool_definitions_required_level CHECK (required_level IN ('READ', 'WRITE', 'EXECUTE')),
    ADD CONSTRAINT ck_tool_definitions_timeout CHECK (timeout_seconds BETWEEN 1 AND 120),
    ADD CONSTRAINT ck_tool_definitions_output CHECK (max_output_bytes BETWEEN 256 AND 65536),
    ADD CONSTRAINT ck_tool_definitions_policy_version CHECK (policy_version >= 1),
    ADD CONSTRAINT ck_tool_definitions_schema CHECK ((input_schema::jsonb ->> 'type') = 'object'),
    -- A tool that can write or execute can never be fully automatic, and a HIGH risk tool is never READ_ONLY.
    ADD CONSTRAINT ck_tool_definitions_read_only CHECK (
        execution_policy <> 'READ_ONLY' OR (required_level = 'READ' AND risk <> 'HIGH'));

-- Reference tools served by the harmless local diagnostic MCP server (the only server the platform can start):
--   diagnostic.echo / diagnostic.add   automatic (READ_ONLY)
--   diagnostic.record_note             changes nothing outside the call, but is modelled as a WRITE
--                                      so the whole approval workflow can be exercised end to end.
-- The pre-existing B3 placeholders keep the default policy DENIED: they have no server and no schema.
INSERT INTO tool_definitions (code, name, description, category, risk, max_level, mcp_server, mcp_tool,
                              input_schema, execution_policy, required_level, timeout_seconds, max_output_bytes) VALUES
    ('diagnostic.echo', 'Echo text', 'Returns the given text unchanged (diagnostic reference tool)', 'Diagnostic',
     'LOW', 'READ', 'diagnostic', 'echo',
     '{"type":"object","properties":{"text":{"type":"string","maxLength":1000}},"required":["text"],"additionalProperties":false}',
     'READ_ONLY', 'READ', 10, 4096),
    ('diagnostic.add', 'Add two integers', 'Adds two integers (diagnostic reference tool)', 'Diagnostic',
     'LOW', 'READ', 'diagnostic', 'add',
     '{"type":"object","properties":{"a":{"type":"integer","minimum":-1000000,"maximum":1000000},"b":{"type":"integer","minimum":-1000000,"maximum":1000000}},"required":["a","b"],"additionalProperties":false}',
     'READ_ONLY', 'READ', 10, 4096),
    ('diagnostic.record_note', 'Record a note', 'Records a short note and returns a receipt (diagnostic reference tool that requires human approval)', 'Diagnostic',
     'MEDIUM', 'WRITE', 'diagnostic', 'record_note',
     '{"type":"object","properties":{"text":{"type":"string","minLength":1,"maxLength":500}},"required":["text"],"additionalProperties":false}',
     'APPROVAL_REQUIRED', 'WRITE', 10, 4096);

-- ---------------------------------------------------------------- executions may wait for a human
ALTER TABLE agent_executions DROP CONSTRAINT ck_agent_executions_status;
ALTER TABLE agent_executions ADD CONSTRAINT ck_agent_executions_status
    CHECK (status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED'));
DROP INDEX ix_agent_executions_open;
CREATE INDEX ix_agent_executions_open ON agent_executions (organization_id, updated_at)
    WHERE status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL');

-- ---------------------------------------------------------------- tool invocations
CREATE TABLE tool_invocations (
    id                uuid         NOT NULL,
    organization_id   uuid         NOT NULL,
    execution_id      uuid         NOT NULL,
    agent_id          uuid         NOT NULL,
    tool_code         text         NOT NULL,
    tool_call_id      varchar(100) NOT NULL,
    sequence          integer      NOT NULL,
    status            varchar(20)  NOT NULL,
    policy            varchar(20)  NOT NULL,
    policy_version    integer      NOT NULL,
    arguments_ref     uuid,
    arguments_redacted text        NOT NULL,
    arguments_hash    varchar(64)  NOT NULL,
    reason            varchar(500),
    result            text,
    result_truncated  boolean      NOT NULL DEFAULT false,
    error_code        varchar(40),
    error_message     varchar(300),
    requested_at      timestamptz  NOT NULL,
    started_at        timestamptz,
    completed_at      timestamptz,
    updated_at        timestamptz  NOT NULL,
    version           bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_tool_invocations PRIMARY KEY (id),
    CONSTRAINT uq_tool_invocations_organization_id_id UNIQUE (organization_id, id),
    -- One row per provider tool call of an execution: a replayed request cannot create a second invocation.
    CONSTRAINT uq_tool_invocations_call UNIQUE (execution_id, tool_call_id),
    CONSTRAINT fk_tool_invocations_organization_id FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_tool_invocations_execution FOREIGN KEY (organization_id, execution_id) REFERENCES agent_executions (organization_id, id),
    CONSTRAINT fk_tool_invocations_agent FOREIGN KEY (organization_id, agent_id) REFERENCES agents (organization_id, id),
    CONSTRAINT fk_tool_invocations_tool FOREIGN KEY (tool_code) REFERENCES tool_definitions (code),
    CONSTRAINT ck_tool_invocations_status CHECK (status IN
        ('DENIED', 'AWAITING_APPROVAL', 'APPROVED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT ck_tool_invocations_policy CHECK (policy IN ('READ_ONLY', 'APPROVAL_REQUIRED', 'DENIED')),
    CONSTRAINT ck_tool_invocations_hash CHECK (arguments_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tool_invocations_sequence CHECK (sequence >= 1),
    CONSTRAINT ck_tool_invocations_final CHECK (
        (status IN ('DENIED', 'SUCCEEDED', 'FAILED', 'REJECTED', 'EXPIRED')) = (completed_at IS NOT NULL)),
    CONSTRAINT ck_tool_invocations_started CHECK (status NOT IN ('RUNNING', 'SUCCEEDED') OR started_at IS NOT NULL)
);
CREATE INDEX ix_tool_invocations_execution ON tool_invocations (organization_id, execution_id, sequence);
CREATE INDEX ix_tool_invocations_org_requested ON tool_invocations (organization_id, requested_at DESC);

-- ---------------------------------------------------------------- tool approvals (decision + durable resume outbox)
CREATE TABLE tool_approvals (
    id                     uuid         NOT NULL,
    organization_id        uuid         NOT NULL,
    execution_id           uuid         NOT NULL,
    agent_id               uuid         NOT NULL,
    invocation_id          uuid         NOT NULL,
    tool_code              text         NOT NULL,
    mcp_server             text,
    tool_call_id           varchar(100) NOT NULL,
    risk                   varchar(10)  NOT NULL,
    description            varchar(600) NOT NULL,
    reason                 varchar(500),
    arguments_redacted     text         NOT NULL,
    arguments_hash         varchar(64)  NOT NULL,
    policy_version         integer      NOT NULL,
    status                 varchar(12)  NOT NULL,
    requested_at           timestamptz  NOT NULL,
    expires_at             timestamptz  NOT NULL,
    decided_by             uuid,
    decided_at             timestamptz,
    rejection_reason       varchar(500),
    resume_acknowledged_at timestamptz,
    resume_attempts        integer      NOT NULL DEFAULT 0,
    last_resume_attempt_at timestamptz,
    updated_at             timestamptz  NOT NULL,
    version                bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_tool_approvals PRIMARY KEY (id),
    CONSTRAINT uq_tool_approvals_organization_id_id UNIQUE (organization_id, id),
    -- One approval per invocation: it can never be reused for another call.
    CONSTRAINT uq_tool_approvals_invocation UNIQUE (invocation_id),
    CONSTRAINT fk_tool_approvals_organization_id FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_tool_approvals_invocation FOREIGN KEY (organization_id, invocation_id) REFERENCES tool_invocations (organization_id, id),
    CONSTRAINT fk_tool_approvals_execution FOREIGN KEY (organization_id, execution_id) REFERENCES agent_executions (organization_id, id),
    CONSTRAINT fk_tool_approvals_agent FOREIGN KEY (organization_id, agent_id) REFERENCES agents (organization_id, id),
    CONSTRAINT fk_tool_approvals_decided_by FOREIGN KEY (decided_by) REFERENCES user_accounts (id),
    CONSTRAINT ck_tool_approvals_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_tool_approvals_hash CHECK (arguments_hash ~ '^[0-9a-f]{64}$'),
    -- A human decision always names the human and the time; nothing else carries them.
    CONSTRAINT ck_tool_approvals_decision CHECK (
        (status IN ('APPROVED', 'REJECTED')) = (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT ck_tool_approvals_expiry CHECK (expires_at > requested_at),
    CONSTRAINT ck_tool_approvals_attempts CHECK (resume_attempts >= 0)
);
CREATE INDEX ix_tool_approvals_org_status ON tool_approvals (organization_id, status, requested_at DESC);
CREATE INDEX ix_tool_approvals_execution ON tool_approvals (organization_id, execution_id);
-- Outbox scans: pending expirations and decisions whose resume notification is not acknowledged yet.
CREATE INDEX ix_tool_approvals_pending_expiry ON tool_approvals (expires_at) WHERE status = 'PENDING';
CREATE INDEX ix_tool_approvals_unacknowledged ON tool_approvals (decided_at)
    WHERE status IN ('APPROVED', 'REJECTED') AND resume_acknowledged_at IS NULL;
