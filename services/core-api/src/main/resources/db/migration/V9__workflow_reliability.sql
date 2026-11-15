-- V9 — Workflow reliability: failure policies, classified failures, durable retry scheduling, step deadlines and manual
-- recovery (Sprint C3.3).
--
-- * A step row is already one ATTEMPT (unique (run, rank, attempt)): attempts are kept, never overwritten, so no new attempt
--   table is needed. The step now also records WHY it was launched (initial, automatic retry, manual retry), how its failure
--   is classified, whether retrying it is provably safe, and WHEN the next attempt is due (durable: a restart loses nothing).
-- * workflow_definitions carry the failure policy (FAIL_FAST | CONTINUE | RETRY), the recovery mode (NONE | MANUAL) and an
--   optional per-step timeout; a run freezes them in its snapshot and mirrors the policy for reporting.
-- * workflow_runs gain the non-terminal status WAITING_RECOVERY (a failed step whose outcome is known and safe, waiting for a
--   person), and a bounded counter of manual retries.
-- V1..V8 are untouched. Everything stays organization-scoped; the Core API is the only writer.

ALTER TABLE workflow_definitions
    ADD COLUMN failure_policy       varchar(12) NOT NULL DEFAULT 'FAIL_FAST',
    ADD COLUMN recovery_mode        varchar(10) NOT NULL DEFAULT 'NONE',
    ADD COLUMN step_timeout_seconds integer,
    ADD CONSTRAINT ck_workflow_definitions_failure_policy CHECK (failure_policy IN ('FAIL_FAST', 'CONTINUE', 'RETRY')),
    ADD CONSTRAINT ck_workflow_definitions_recovery_mode CHECK (recovery_mode IN ('NONE', 'MANUAL')),
    ADD CONSTRAINT ck_workflow_definitions_step_timeout CHECK (step_timeout_seconds IS NULL OR step_timeout_seconds BETWEEN 10 AND 86400),
    -- A manual recovery stops the run at a failure: it makes no sense when failures are continued past.
    ADD CONSTRAINT ck_workflow_definitions_recovery_policy CHECK (recovery_mode = 'NONE' OR failure_policy <> 'CONTINUE');

ALTER TABLE workflow_runs
    ADD COLUMN failure_policy  varchar(12) NOT NULL DEFAULT 'FAIL_FAST',
    ADD COLUMN manual_retries  integer     NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_workflow_runs_failure_policy CHECK (failure_policy IN ('FAIL_FAST', 'CONTINUE', 'RETRY')),
    ADD CONSTRAINT ck_workflow_runs_manual_retries CHECK (manual_retries BETWEEN 0 AND 3),
    DROP CONSTRAINT ck_workflow_runs_status,
    ADD CONSTRAINT ck_workflow_runs_status CHECK (status IN
        ('PENDING', 'RUNNING', 'WAITING_APPROVAL', 'WAITING_RECOVERY', 'CANCEL_REQUESTED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMEOUT'));

ALTER TABLE workflow_steps
    ADD COLUMN launch_reason       varchar(12) NOT NULL DEFAULT 'INITIAL',
    ADD COLUMN failure_category    varchar(32),
    ADD COLUMN retry_safe          boolean,
    ADD COLUMN next_attempt_at     timestamptz,
    ADD COLUMN deadline_at         timestamptz,
    ADD COLUMN retry_requested_by  uuid,
    DROP CONSTRAINT ck_workflow_steps_rank,
    -- 1 initial + up to 2 automatic + up to 3 manual attempts of a rank (automatic retries never follow a manual one).
    ADD CONSTRAINT ck_workflow_steps_rank CHECK (step_rank BETWEEN 1 AND 40 AND attempt BETWEEN 1 AND 6),
    ADD CONSTRAINT ck_workflow_steps_launch_reason CHECK (launch_reason IN ('INITIAL', 'AUTO_RETRY', 'MANUAL_RETRY')),
    ADD CONSTRAINT ck_workflow_steps_failure_category CHECK (failure_category IS NULL OR failure_category IN
        ('TRANSIENT_PROVIDER_FAILURE', 'PERMANENT_PROVIDER_FAILURE', 'TOOL_FAILURE', 'OUTCOME_UNKNOWN', 'AUTHORIZATION_FAILURE',
         'WORKFLOW_LIMIT_EXCEEDED', 'UNCLASSIFIED')),
    ADD CONSTRAINT fk_workflow_steps_retry_requested_by FOREIGN KEY (retry_requested_by) REFERENCES user_accounts (id);

-- The scheduler looks for the failed steps whose retry is due.
CREATE INDEX ix_workflow_steps_next_attempt ON workflow_steps (run_id) WHERE next_attempt_at IS NOT NULL;
