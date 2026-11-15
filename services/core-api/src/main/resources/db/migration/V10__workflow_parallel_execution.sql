-- V10 — Controlled parallel execution (Sprint C3.4).
--
-- What the existing model already represents, and therefore was NOT duplicated:
--   * a worker attempt = a workflow_steps row (C3.1/C3.3); several ranks may already be live at once (the live-rank unique index is
--     per rank), a group is just steps launched together;
--   * a delegation = a workflow_actions row (C3.2), linked to its step by result_step_id;
--   * retries, failure categories, deadlines = columns of workflow_steps (C3.3).
-- What was missing, and is added here:
--   * QUEUED work: accepted work must be persisted BEFORE it is dispatched and survive a restart when no capacity is free. A step
--     can therefore exist without a child execution yet (execution_id NULL, status QUEUED) and receives its execution when the
--     scheduler dispatches it. The same row later carries the attempt, so no second "work item" table exists.
--   * group_no: which fan-out group (stage, or coordinator turn) a step belongs to.
--   * reserved_tokens: per-step and per-run token reservations so that concurrent workers cannot jointly overspend the budget.
--   * max_parallel_workers: the per-workflow concurrency bound (1 = the sequential behaviour of C3.1 / C3.2, the default).
--   * several accepted delegations in ONE coordinator turn (the "one accepted action per turn" index is replaced by "one finish").
-- V1..V9 are untouched. Everything stays organization-scoped; the Core API is the only writer.

ALTER TABLE workflow_definitions
    ADD COLUMN max_parallel_workers integer NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_workflow_definitions_parallel CHECK (max_parallel_workers BETWEEN 1 AND 3),
    -- Manual recovery holds the run on ONE failed step: it is not defined for a group of concurrent steps.
    ADD CONSTRAINT ck_workflow_definitions_parallel_recovery CHECK (max_parallel_workers = 1 OR recovery_mode = 'NONE');

ALTER TABLE workflow_runs
    ADD COLUMN max_parallel_workers integer NOT NULL DEFAULT 1,
    ADD COLUMN reserved_tokens      integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_workflow_runs_parallel CHECK (max_parallel_workers BETWEEN 1 AND 3),
    ADD CONSTRAINT ck_workflow_runs_reserved CHECK (reserved_tokens >= 0);

ALTER TABLE workflow_steps
    ALTER COLUMN execution_id DROP NOT NULL,
    ADD COLUMN group_no        integer,
    ADD COLUMN reserved_tokens integer NOT NULL DEFAULT 0,
    DROP CONSTRAINT ck_workflow_steps_status,
    ADD CONSTRAINT ck_workflow_steps_status CHECK (status IN ('QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    -- Only queued work (or queued work that was cancelled before it ever ran) has no child execution.
    ADD CONSTRAINT ck_workflow_steps_execution CHECK (execution_id IS NOT NULL OR status IN ('QUEUED', 'CANCELLED')),
    ADD CONSTRAINT ck_workflow_steps_group CHECK (group_no IS NULL OR group_no BETWEEN 1 AND 40),
    ADD CONSTRAINT ck_workflow_steps_reserved CHECK (reserved_tokens >= 0);

-- A queued rank is as "taken" as a running one: a rank can never be queued or scheduled twice.
DROP INDEX uq_workflow_steps_live_rank;
CREATE UNIQUE INDEX uq_workflow_steps_live_rank ON workflow_steps (run_id, step_rank)
    WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED');
-- The scheduler looks for the queued work of a run.
CREATE INDEX ix_workflow_steps_queued ON workflow_steps (run_id, step_rank) WHERE status = 'QUEUED';

-- A coordinator turn may now accept several delegations (up to the workflow's parallel bound); it still has at most one finish.
DROP INDEX uq_workflow_actions_one_per_turn;
CREATE UNIQUE INDEX uq_workflow_actions_one_finish_per_turn ON workflow_actions (organization_id, execution_id)
    WHERE status = 'ACCEPTED' AND action = 'workflow.finish';
