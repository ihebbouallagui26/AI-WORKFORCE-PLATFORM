import { bounded } from '../ai-agents/execution-timeline.model';
import { FailurePolicy, RecoveryMode, WorkflowMode } from '../../core/workflows/workflows.models';

export const MODE_TEXT: Readonly<Record<WorkflowMode, { label: string; help: string }>> = {
  PIPELINE: {
    label: 'Pipeline',
    help: 'You define the stages. Steps of the same stage run in parallel; the next stage starts when the whole stage is resolved.',
  },
  COORDINATED: {
    label: 'Coordinated',
    help: 'A coordinator agent plans and delegates tasks to the participants. It cannot add participants, change limits or bypass authorization and tool approval: the Core API validates every delegation. Independent tasks may run in parallel.',
  },
};

export const POLICY_TEXT: Readonly<Record<FailurePolicy, string>> = {
  FAIL_FAST: 'Stop at the first failure',
  CONTINUE: 'Continue past failures (reported as partial)',
  RETRY: 'Retry safe failures, then stop',
};

export const POLICY_HELP: Readonly<Record<FailurePolicy, string>> = {
  FAIL_FAST: 'Nothing after a failed step starts; siblings of a parallel group are stopped; completed results are kept.',
  CONTINUE: 'A failed worker is recorded and reported to the next stage or the coordinator, never as a success. It never continues past a limit or a revoked permission.',
  RETRY: 'A failed step is retried automatically ONLY when the failure is temporary and no external action started, within the retry limit; then the workflow stops.',
};

export const RECOVERY_TEXT: Readonly<Record<RecoveryMode, string>> = {
  NONE: 'A failure ends the workflow',
  MANUAL: 'A safe failure waits for a person to retry the step',
};

/** Text that came from an agent or a person, masked of secret-looking values and bounded for display. */
export function safeText(text: string | null | undefined, max: number): string {
  const result = bounded(text, max);
  return result.clipped ? `${result.text}…` : result.text;
}
