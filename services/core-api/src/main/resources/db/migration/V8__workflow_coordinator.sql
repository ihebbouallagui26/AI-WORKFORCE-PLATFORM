-- V8 — AI coordinator and dynamic delegation (Sprint C3.2).
--
-- * workflow_definitions / workflow_runs gain a MODE: PIPELINE (C3.1, unchanged) or COORDINATED. A coordinated definition names
--   its coordinator agent (it is also an explicit participant); the mode and the coordinator are frozen in the run snapshot.
-- * workflow_steps may now be COORDINATOR turns as well as WORKER steps; a coordinated run has up to 8 worker steps plus up to 9
--   coordinator turns, so the rank and the rank counter limits are widened (they are only an ordinal of the run).
-- * workflow_actions: the platform-native actions of a coordinator turn (workflow.delegate, workflow.finish). They are NOT tool
--   invocations and have nothing to do with MCP: they are validated and recorded by the Core API, idempotent per
--   (coordinator execution, provider tool_call_id), and persisted BEFORE any worker execution is created.
-- agent_executions is untouched. Every new structure is organization-scoped with composite keys. V1..V7 are untouched.

ALTER TABLE workflow_definitions
    ADD COLUMN mode                 varchar(12) NOT NULL DEFAULT 'PIPELINE',
    ADD COLUMN coordinator_agent_id uuid,
    ADD CONSTRAINT ck_workflow_definitions_mode CHECK (mode IN ('PIPELINE', 'COORDINATED')),
    -- A pipeline never has a coordinator; a coordinated definition names one once it is configured (it cannot start without).
    ADD CONSTRAINT ck_workflow_definitions_coordinator CHECK (mode = 'COORDINATED' OR coordinator_agent_id IS NULL),
    ADD CONSTRAINT fk_workflow_definitions_coordinator FOREIGN KEY (organization_id, coordinator_agent_id)
        REFERENCES agents (organization_id, id);

ALTER TABLE workflow_runs
    ADD COLUMN mode varchar(12) NOT NULL DEFAULT 'PIPELINE',
    ADD CONSTRAINT ck_workflow_runs_mode CHECK (mode IN ('PIPELINE', 'COORDINATED')),
    DROP CONSTRAINT ck_workflow_runs_steps,
    ADD CONSTRAINT ck_workflow_runs_steps CHECK (total_steps BETWEEN 1 AND 8 AND next_rank BETWEEN 1 AND 41);

ALTER TABLE workflow_steps
    DROP CONSTRAINT ck_workflow_steps_role,
    ADD CONSTRAINT ck_workflow_steps_role CHECK (role IN ('WORKER', 'COORDINATOR')),
    DROP CONSTRAINT ck_workflow_steps_rank,
    ADD CONSTRAINT ck_workflow_steps_rank CHECK (step_rank BETWEEN 1 AND 40 AND attempt BETWEEN 1 AND 3);

CREATE TABLE workflow_actions (
    id               uuid          NOT NULL,
    organization_id  uuid          NOT NULL,
    run_id           uuid          NOT NULL,
    step_id          uuid          NOT NULL,
    execution_id     uuid          NOT NULL,
    tool_call_id     varchar(100)  NOT NULL,
    action           varchar(20)   NOT NULL,
    status           varchar(12)   NOT NULL,
    code             varchar(40)   NOT NULL DEFAULT 'OK',
    message          varchar(300)  NOT NULL DEFAULT '',
    arguments_hash   varchar(64)   NOT NULL,
    participant_ref  varchar(16),
    worker_agent_id  uuid,
    task             varchar(1500),
    task_hash        varchar(64),
    input_ranks      varchar(40)   NOT NULL DEFAULT '',
    result           text,
    result_step_id   uuid,
    created_at       timestamptz   NOT NULL,
    row_version      bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_workflow_actions PRIMARY KEY (id),
    CONSTRAINT fk_workflow_actions_run FOREIGN KEY (organization_id, run_id) REFERENCES workflow_runs (organization_id, id),
    CONSTRAINT fk_workflow_actions_step FOREIGN KEY (organization_id, step_id) REFERENCES workflow_steps (organization_id, id),
    CONSTRAINT fk_workflow_actions_execution FOREIGN KEY (organization_id, execution_id)
        REFERENCES agent_executions (organization_id, id),
    CONSTRAINT fk_workflow_actions_worker FOREIGN KEY (organization_id, worker_agent_id) REFERENCES agents (organization_id, id),
    CONSTRAINT fk_workflow_actions_result_step FOREIGN KEY (organization_id, result_step_id)
        REFERENCES workflow_steps (organization_id, id),
    -- Idempotency scope: the coordinator execution and the provider tool_call_id.
    CONSTRAINT uq_workflow_actions_call UNIQUE (organization_id, execution_id, tool_call_id),
    CONSTRAINT ck_workflow_actions_action CHECK (action IN ('workflow.delegate', 'workflow.finish')),
    CONSTRAINT ck_workflow_actions_status CHECK (status IN ('ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_workflow_actions_result_size CHECK (result IS NULL OR char_length(result) <= 65536)
);
-- Exactly one control action per coordinator decision turn.
CREATE UNIQUE INDEX uq_workflow_actions_one_per_turn ON workflow_actions (organization_id, execution_id) WHERE status = 'ACCEPTED';
-- Anti-loop: the same task is never delegated twice to the same worker within a run.
CREATE UNIQUE INDEX uq_workflow_actions_no_repeat ON workflow_actions (run_id, worker_agent_id, task_hash)
    WHERE status = 'ACCEPTED' AND action = 'workflow.delegate';
CREATE INDEX ix_workflow_actions_organization_id_run_id ON workflow_actions (organization_id, run_id, created_at);
