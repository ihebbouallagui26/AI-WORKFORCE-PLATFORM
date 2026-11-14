-- V7 — Deterministic multi-agent workflow pipeline (Sprint C3.1).
--
-- * workflow_definitions: a named, versioned pipeline of ORDERED STATIC STEPS (JSON text) with explicit limits.
-- * workflow_participants: the agents explicitly chosen for a definition (never inherited from teams).
-- * workflow_runs: one run of a definition. The definition is copied into `snapshot` when the run starts, so
--   editing or archiving the definition never changes a run in progress. The Core API owns all workflow state.
-- * workflow_steps: one step = exactly ONE existing C1 agent_executions row (no workflow state is added to
--   agent_executions). A rank may have several attempts (automatic retry of a transient provider failure).
-- * workflow_messages: bounded, append-only journal (objective, assignment, result, status). A trigger refuses
--   UPDATE and DELETE.
-- Every table is organization-scoped with composite foreign keys. V1..V6 are untouched.

-- ---------------------------------------------------------------- permissions
INSERT INTO permissions (code, description) VALUES
    ('workflow.read',    'View multi-agent workflows, their runs, steps and messages'),
    ('workflow.manage',  'Create and configure multi-agent workflows and choose their participants'),
    ('workflow.execute', 'Start and cancel multi-agent workflow runs');

-- OWNER and ADMIN only: plain members and custom roles are never widened.
INSERT INTO role_permissions (role_id, organization_id, permission_code)
SELECT r.id, r.organization_id, p.code
FROM roles r
JOIN permissions p ON p.code IN ('workflow.read', 'workflow.manage', 'workflow.execute')
WHERE r.code IN ('OWNER', 'ADMIN')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- definitions
CREATE TABLE workflow_definitions (
    id                   uuid          NOT NULL,
    organization_id      uuid          NOT NULL,
    name                 varchar(120)  NOT NULL,
    description          varchar(2000) NOT NULL DEFAULT '',
    status               varchar(16)   NOT NULL DEFAULT 'ACTIVE',
    version              integer       NOT NULL DEFAULT 1,
    steps                text          NOT NULL DEFAULT '[]',
    max_steps            integer       NOT NULL DEFAULT 8,
    max_retries_per_step integer       NOT NULL DEFAULT 0,
    token_budget         integer       NOT NULL DEFAULT 200000,
    deadline_seconds     integer       NOT NULL DEFAULT 1800,
    created_by           uuid          NOT NULL,
    created_at           timestamptz   NOT NULL,
    updated_at           timestamptz   NOT NULL,
    archived_at          timestamptz,
    row_version          bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_workflow_definitions PRIMARY KEY (id),
    CONSTRAINT uq_workflow_definitions_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT fk_workflow_definitions_organization_id FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_workflow_definitions_created_by FOREIGN KEY (created_by) REFERENCES user_accounts (id),
    CONSTRAINT ck_workflow_definitions_name CHECK (char_length(btrim(name)) BETWEEN 1 AND 120),
    CONSTRAINT ck_workflow_definitions_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_workflow_definitions_archived CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL)),
    CONSTRAINT ck_workflow_definitions_version CHECK (version >= 1),
    CONSTRAINT ck_workflow_definitions_max_steps CHECK (max_steps BETWEEN 1 AND 8),
    CONSTRAINT ck_workflow_definitions_retries CHECK (max_retries_per_step BETWEEN 0 AND 2),
    CONSTRAINT ck_workflow_definitions_budget CHECK (token_budget BETWEEN 1000 AND 10000000),
    CONSTRAINT ck_workflow_definitions_deadline CHECK (deadline_seconds BETWEEN 30 AND 86400),
    CONSTRAINT ck_workflow_definitions_steps_size CHECK (char_length(steps) <= 65536)
);
CREATE INDEX ix_workflow_definitions_organization_id_status ON workflow_definitions (organization_id, status);

CREATE TABLE workflow_participants (
    id               uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    definition_id    uuid        NOT NULL,
    agent_id         uuid        NOT NULL,
    added_by         uuid        NOT NULL,
    added_at         timestamptz NOT NULL,
    CONSTRAINT pk_workflow_participants PRIMARY KEY (id),
    CONSTRAINT fk_workflow_participants_definition FOREIGN KEY (organization_id, definition_id)
        REFERENCES workflow_definitions (organization_id, id),
    -- The agent must belong to the same organization.
    CONSTRAINT fk_workflow_participants_agent FOREIGN KEY (organization_id, agent_id)
        REFERENCES agents (organization_id, id),
    CONSTRAINT fk_workflow_participants_added_by FOREIGN KEY (added_by) REFERENCES user_accounts (id),
    CONSTRAINT uq_workflow_participants_definition_agent UNIQUE (definition_id, agent_id)
);
CREATE INDEX ix_workflow_participants_organization_id_definition_id ON workflow_participants (organization_id, definition_id);

-- ---------------------------------------------------------------- runs
CREATE TABLE workflow_runs (
    id                   uuid          NOT NULL,
    organization_id      uuid          NOT NULL,
    definition_id        uuid          NOT NULL,
    definition_version   integer       NOT NULL,
    snapshot             text          NOT NULL,
    requested_by         uuid          NOT NULL,
    request_id           varchar(64),
    objective            text          NOT NULL,
    status               varchar(20)   NOT NULL,
    total_steps          integer       NOT NULL,
    next_rank            integer       NOT NULL DEFAULT 1,
    message_sequence     integer       NOT NULL DEFAULT 0,
    tokens_used          integer       NOT NULL DEFAULT 0,
    token_budget         integer       NOT NULL,
    deadline_at          timestamptz   NOT NULL,
    result               text,
    error_code           varchar(40),
    error_message        varchar(300),
    cancel_requested_at  timestamptz,
    cancel_requested_by  uuid,
    correlation_id       varchar(64),
    created_at           timestamptz   NOT NULL,
    started_at           timestamptz,
    completed_at         timestamptz,
    updated_at           timestamptz   NOT NULL,
    row_version          bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_workflow_runs PRIMARY KEY (id),
    CONSTRAINT uq_workflow_runs_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT fk_workflow_runs_definition FOREIGN KEY (organization_id, definition_id)
        REFERENCES workflow_definitions (organization_id, id),
    CONSTRAINT fk_workflow_runs_requested_by FOREIGN KEY (requested_by) REFERENCES user_accounts (id),
    CONSTRAINT fk_workflow_runs_cancel_requested_by FOREIGN KEY (cancel_requested_by) REFERENCES user_accounts (id),
    CONSTRAINT ck_workflow_runs_status CHECK (status IN
        ('PENDING', 'RUNNING', 'WAITING_APPROVAL', 'CANCEL_REQUESTED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMEOUT')),
    CONSTRAINT ck_workflow_runs_objective CHECK (char_length(btrim(objective)) BETWEEN 1 AND 2000),
    CONSTRAINT ck_workflow_runs_steps CHECK (total_steps BETWEEN 1 AND 8 AND next_rank BETWEEN 1 AND total_steps + 1),
    CONSTRAINT ck_workflow_runs_tokens CHECK (tokens_used >= 0 AND token_budget > 0),
    -- Terminal states carry a completion time; a result only exists on success.
    CONSTRAINT ck_workflow_runs_completed CHECK
        ((status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMEOUT')) = (completed_at IS NOT NULL)),
    CONSTRAINT ck_workflow_runs_result CHECK (result IS NULL OR status IN ('SUCCEEDED', 'CANCELLED')),
    CONSTRAINT ck_workflow_runs_error CHECK (error_code IS NULL OR status IN ('FAILED', 'TIMEOUT', 'CANCELLED'))
);
-- A repeated launch request of the same person never creates a second run.
CREATE UNIQUE INDEX uq_workflow_runs_request ON workflow_runs (organization_id, requested_by, request_id)
    WHERE request_id IS NOT NULL;
CREATE INDEX ix_workflow_runs_organization_id_created_at ON workflow_runs (organization_id, created_at DESC);
CREATE INDEX ix_workflow_runs_open ON workflow_runs (updated_at)
    WHERE status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL', 'CANCEL_REQUESTED');

-- ---------------------------------------------------------------- steps (one step = one agent_executions row)
CREATE TABLE workflow_steps (
    id               uuid          NOT NULL,
    organization_id  uuid          NOT NULL,
    run_id           uuid          NOT NULL,
    step_rank        integer       NOT NULL,
    attempt          integer       NOT NULL DEFAULT 1,
    name             varchar(120)  NOT NULL,
    agent_id         uuid          NOT NULL,
    role             varchar(12)   NOT NULL DEFAULT 'WORKER',
    execution_id     uuid          NOT NULL,
    status           varchar(20)   NOT NULL,
    input_ranks      varchar(40)   NOT NULL DEFAULT '',
    result_summary   varchar(2000),
    error_code       varchar(40),
    error_message    varchar(300),
    created_at       timestamptz   NOT NULL,
    started_at       timestamptz,
    completed_at     timestamptz,
    updated_at       timestamptz   NOT NULL,
    row_version      bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_workflow_steps PRIMARY KEY (id),
    CONSTRAINT uq_workflow_steps_organization_id_id UNIQUE (organization_id, id),
    CONSTRAINT fk_workflow_steps_run FOREIGN KEY (organization_id, run_id) REFERENCES workflow_runs (organization_id, id),
    CONSTRAINT fk_workflow_steps_agent FOREIGN KEY (organization_id, agent_id) REFERENCES agents (organization_id, id),
    -- The linked execution must be an execution of the same organization.
    CONSTRAINT fk_workflow_steps_execution FOREIGN KEY (organization_id, execution_id)
        REFERENCES agent_executions (organization_id, id),
    -- An execution belongs to at most one step.
    CONSTRAINT uq_workflow_steps_execution UNIQUE (organization_id, execution_id),
    CONSTRAINT uq_workflow_steps_attempt UNIQUE (run_id, step_rank, attempt),
    CONSTRAINT ck_workflow_steps_rank CHECK (step_rank BETWEEN 1 AND 8 AND attempt BETWEEN 1 AND 3),
    CONSTRAINT ck_workflow_steps_role CHECK (role IN ('WORKER')),
    CONSTRAINT ck_workflow_steps_status CHECK (status IN ('RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_workflow_steps_completed CHECK
        ((status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')) = (completed_at IS NOT NULL))
);
-- At most one live or successful attempt per rank: a rank can never be scheduled twice.
CREATE UNIQUE INDEX uq_workflow_steps_live_rank ON workflow_steps (run_id, step_rank)
    WHERE status IN ('RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED');
CREATE INDEX ix_workflow_steps_organization_id_run_id ON workflow_steps (organization_id, run_id);

-- ---------------------------------------------------------------- messages (append-only)
CREATE TABLE workflow_messages (
    id               uuid          NOT NULL,
    organization_id  uuid          NOT NULL,
    run_id           uuid          NOT NULL,
    sequence         integer       NOT NULL,
    step_rank        integer,
    kind             varchar(12)   NOT NULL,
    body             varchar(4000) NOT NULL,
    created_at       timestamptz   NOT NULL,
    CONSTRAINT pk_workflow_messages PRIMARY KEY (id),
    CONSTRAINT fk_workflow_messages_run FOREIGN KEY (organization_id, run_id) REFERENCES workflow_runs (organization_id, id),
    CONSTRAINT uq_workflow_messages_sequence UNIQUE (run_id, sequence),
    CONSTRAINT ck_workflow_messages_kind CHECK (kind IN ('OBJECTIVE', 'ASSIGNMENT', 'RESULT', 'STATUS'))
);
CREATE INDEX ix_workflow_messages_organization_id_run_id ON workflow_messages (organization_id, run_id, sequence);

CREATE FUNCTION workflow_messages_are_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'workflow_messages is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workflow_messages_append_only
    BEFORE UPDATE OR DELETE ON workflow_messages
    FOR EACH ROW EXECUTE FUNCTION workflow_messages_are_append_only();
