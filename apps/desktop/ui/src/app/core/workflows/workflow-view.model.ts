/**
 * Pure presentation logic of the workflow UI: how persisted records are SHOWN. Nothing here decides anything about a workflow (the Core API
 * does) and nothing is invented: every value is derived from records the API returned, and what cannot be known is reported as unknown.
 */
import { ExecutionStatus } from '../agents/agents.models';
import { Tone } from '../../shared/utils/presentation';
import {
  AttemptUsage,
  FailureCategory,
  GroupStatus,
  RunStatus,
  RunSummary,
  StepStatus,
  WorkflowAction,
  WorkflowMessage,
  WorkflowRun,
  WorkflowStepView,
  isFinalStep,
  isTerminalRun,
} from './workflows.models';

export type Phase = 'queued' | 'dispatched' | 'running' | 'waiting-approval' | 'completed' | 'failed' | 'unknown-outcome' | 'cancelled';

export interface StepPresentation {
  readonly phase: Phase;
  readonly label: string;
  readonly tone: Tone;
  /** One honest sentence about what the status does and does not mean. */
  readonly explanation: string;
}

/** Live child-execution state of a worker, when the monitor could read it (null = not confirmed). */
export type LiveExecution = ExecutionStatus | null;

/**
 * The status of one attempt as a person should read it. A QUEUED step is never shown as running; a step whose child exists but has not
 * been confirmed running is "dispatched", not "running" (dispatch is a hand-over, not proof of execution); a failed attempt is never
 * shown as a result; an unknown external outcome is shown as such, not as a plain failure or a cancellation.
 */
export function presentStep(step: WorkflowStepView, live: LiveExecution = null): StepPresentation {
  switch (step.status) {
    case 'QUEUED':
      return { phase: 'queued', label: 'Waiting for capacity', tone: 'neutral', explanation: 'Accepted and saved. No execution exists yet: it starts when a slot and budget are free.' };
    case 'RUNNING':
      if (live === 'RUNNING') {
        return { phase: 'running', label: 'Running', tone: 'accent', explanation: 'The agent is working on its task.' };
      }
      if (live === 'PENDING') {
        return { phase: 'dispatched', label: 'Dispatched', tone: 'info', explanation: 'The execution was created and handed to the orchestrator; it has not started working yet.' };
      }
      return { phase: 'dispatched', label: 'Dispatched', tone: 'info', explanation: 'The execution exists; whether the agent is already working is not confirmed.' };
    case 'WAITING_APPROVAL':
      return { phase: 'waiting-approval', label: 'Needs approval', tone: 'warning', explanation: 'Paused: a person must approve or reject a tool action of this agent.' };
    case 'SUCCEEDED':
      return { phase: 'completed', label: 'Completed', tone: 'success', explanation: 'This attempt finished and produced a result.' };
    case 'FAILED':
      if (step.failureCategory === 'OUTCOME_UNKNOWN') {
        return {
          phase: 'unknown-outcome',
          label: 'Outcome unknown',
          tone: 'danger',
          explanation: 'An external action may have run and its result is lost. It is never replayed automatically or by hand.',
        };
      }
      if (step.nextAttemptAt) {
        return { phase: 'failed', label: 'Failed, retry scheduled', tone: 'warning', explanation: 'This attempt failed; a new attempt is scheduled (a new execution).' };
      }
      return { phase: 'failed', label: 'Failed', tone: 'danger', explanation: 'This attempt failed and produced no result.' };
    case 'CANCELLED':
      return { phase: 'cancelled', label: 'Cancelled', tone: 'neutral', explanation: 'Stopped before finishing. Nothing it started is claimed to be undone.' };
  }
}

export const RUN_STATUS_LABEL: Readonly<Record<RunStatus, { label: string; tone: Tone }>> = {
  PENDING: { label: 'Starting', tone: 'neutral' },
  RUNNING: { label: 'Running', tone: 'accent' },
  WAITING_APPROVAL: { label: 'Needs approval', tone: 'warning' },
  WAITING_RECOVERY: { label: 'Waiting for recovery', tone: 'warning' },
  CANCEL_REQUESTED: { label: 'Cancelling', tone: 'warning' },
  SUCCEEDED: { label: 'Succeeded', tone: 'success' },
  FAILED: { label: 'Failed', tone: 'danger' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral' },
  TIMEOUT: { label: 'Timed out', tone: 'danger' },
};

export const GROUP_STATUS_LABEL: Readonly<Record<GroupStatus, { label: string; tone: Tone }>> = {
  QUEUED: { label: 'Queued', tone: 'neutral' },
  RUNNING: { label: 'Running', tone: 'accent' },
  WAITING_FOR_APPROVAL: { label: 'Waiting for approval', tone: 'warning' },
  COMPLETED: { label: 'Completed', tone: 'success' },
  PARTIALLY_FAILED: { label: 'Partially failed', tone: 'warning' },
  FAILED: { label: 'Failed', tone: 'danger' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral' },
};

export const FAILURE_LABEL: Readonly<Record<FailureCategory, string>> = {
  TRANSIENT_PROVIDER_FAILURE: 'Temporary provider failure',
  PERMANENT_PROVIDER_FAILURE: 'Provider configuration problem',
  TOOL_FAILURE: 'Tool failure',
  OUTCOME_UNKNOWN: 'External outcome unknown',
  AUTHORIZATION_FAILURE: 'Authorization failure',
  WORKFLOW_LIMIT_EXCEEDED: 'Workflow limit reached',
  UNCLASSIFIED: 'Unclassified failure',
};

// ---------------------------------------------------------------------------------------------------- layout

export interface WorkerCard {
  readonly rank: number;
  /** The attempt that counts now (the last one of the rank). */
  readonly latest: WorkflowStepView;
  /** Every attempt of the rank in order: a retry never replaces an earlier attempt. */
  readonly attempts: readonly WorkflowStepView[];
}

export interface RunColumn {
  readonly key: string;
  readonly kind: 'stage' | 'coordinator' | 'step';
  readonly label: string;
  /** True when several workers of this column run at the same time. */
  readonly parallel: boolean;
  readonly workers: readonly WorkerCard[];
  readonly status: GroupStatus;
}

/** Status of a set of workers, derived from their latest attempts (same rules as the Core API group summary). */
export function groupStatus(workers: readonly WorkerCard[]): GroupStatus {
  const s = workers.map((w) => w.latest.status);
  const allFinal = s.every(isFinalStep);
  if (allFinal) {
    const bad = s.filter((x) => x === 'FAILED').length;
    const cancelled = s.filter((x) => x === 'CANCELLED').length;
    const ok = s.filter((x) => x === 'SUCCEEDED').length;
    if (bad === 0 && cancelled === 0) return 'COMPLETED';
    if (ok > 0) return 'PARTIALLY_FAILED';
    return bad > 0 ? 'FAILED' : 'CANCELLED';
  }
  if (s.includes('RUNNING')) return 'RUNNING';
  if (s.includes('WAITING_APPROVAL') && !s.includes('QUEUED')) return 'WAITING_FOR_APPROVAL';
  return 'QUEUED';
}

/**
 * Lays the attempts of a run out as columns left to right: workers that share a group number sit in ONE parallel column (fan-out from the
 * previous column, fan-in to the next); a sequential step or a coordinator turn is a column of its own. Order is by rank, deterministic.
 */
export function layoutRun(steps: readonly WorkflowStepView[], mode: 'PIPELINE' | 'COORDINATED' = 'PIPELINE'): readonly RunColumn[] {
  const byRank = new Map<number, WorkflowStepView[]>();
  for (const step of [...steps].sort((a, b) => a.rank - b.rank || a.attempt - b.attempt)) {
    byRank.set(step.rank, [...(byRank.get(step.rank) ?? []), step]);
  }
  const cards: WorkerCard[] = [...byRank.entries()].map(([rank, attempts]) => ({ rank, latest: attempts[attempts.length - 1], attempts }));
  const columns: RunColumn[] = [];
  const grouped = new Map<number, WorkerCard[]>();
  for (const card of cards) {
    const group = card.latest.groupNo;
    if (card.latest.role === 'WORKER' && group !== null) {
      const column = grouped.get(group);
      if (column) {
        column.push(card);
        continue;
      }
      const created = [card];
      grouped.set(group, created);
      columns.push({ key: `group-${group}`, kind: 'stage', label: mode === 'PIPELINE' ? `Stage ${group}` : `Delegated tasks (turn ${group})`, parallel: false, workers: created, status: 'QUEUED' });
      continue;
    }
    const coordinator = card.latest.role === 'COORDINATOR';
    columns.push({
      key: `rank-${card.rank}`,
      kind: coordinator ? 'coordinator' : 'step',
      label: coordinator ? `Coordinator turn ${card.rank}` : `Step ${card.rank}`,
      parallel: false,
      workers: [card],
      status: 'QUEUED',
    });
  }
  return columns.map((c) => ({ ...c, parallel: c.workers.length > 1, status: groupStatus(c.workers) }));
}

// ---------------------------------------------------------------------------------------------------- timeline

export type TimelineKind = 'run' | 'queued' | 'dispatched' | 'delegation' | 'completion' | 'failure' | 'retry' | 'approval' | 'group' | 'journal';

export interface TimelineEvent {
  readonly key: string;
  readonly at: string;
  readonly kind: TimelineKind;
  readonly tone: Tone;
  readonly title: string;
  readonly detail: string | null;
  readonly rank: number | null;
}

/** The approval fields the timeline needs (a subset of the C2 approval DTO). */
export interface TimelineApproval {
  readonly id: string;
  readonly executionId: string;
  readonly toolName: string | null;
  readonly status: string;
  readonly requestedAt: string;
  readonly decidedAt: string | null;
  readonly expiresAt: string | null;
}

/**
 * A chronological view reconstructed from the persisted records of the run (steps, coordinator actions, the run's journal and any approvals
 * loaded). It is a snapshot rebuilt at each refresh, NOT a live event stream: an event appears when the Core API has recorded it.
 */
export function buildTimeline(
  run: WorkflowRun,
  steps: readonly WorkflowStepView[],
  actions: readonly WorkflowAction[],
  messages: readonly WorkflowMessage[],
  approvals: readonly TimelineApproval[] = [],
): readonly TimelineEvent[] {
  const events: TimelineEvent[] = [];
  const add = (e: TimelineEvent) => events.push(e);
  add({ key: 'run:created', at: run.createdAt, kind: 'run', tone: 'neutral', title: 'Workflow launched', detail: null, rank: null });
  if (run.startedAt) {
    add({ key: 'run:started', at: run.startedAt, kind: 'run', tone: 'info', title: 'Workflow started', detail: null, rank: null });
  }
  for (const s of steps) {
    const who = `${s.name}${s.agentName ? ` (${s.agentName})` : ''}`;
    const tag = s.role === 'COORDINATOR' ? 'Coordinator turn' : 'Step';
    if (s.groupNo !== null && s.role === 'WORKER') {
      add({ key: `${s.id}:queued`, at: s.createdAt, kind: 'queued', tone: 'neutral', title: `Queued: ${who}`, detail: `Group ${s.groupNo}`, rank: s.rank });
    }
    if (s.startedAt) {
      const retry = s.launchReason !== 'INITIAL';
      add({
        key: `${s.id}:started`, at: s.startedAt, kind: retry ? 'retry' : 'dispatched', tone: retry ? 'warning' : 'info',
        title: retry ? `Retry executed (attempt ${s.attempt}): ${who}` : `${tag} dispatched: ${who}`,
        detail: retry ? (s.launchReason === 'MANUAL_RETRY' ? 'Requested by a person' : 'Automatic retry') : null, rank: s.rank,
      });
    }
    if (s.status === 'FAILED' && s.nextAttemptAt) {
      add({ key: `${s.id}:retry-scheduled`, at: s.completedAt ?? s.nextAttemptAt, kind: 'retry', tone: 'warning', title: `Retry scheduled: ${who}`, detail: `Due ${s.nextAttemptAt}`, rank: s.rank });
    }
    if (s.completedAt) {
      const presentation = presentStep(s);
      add({
        key: `${s.id}:done`, at: s.completedAt, kind: s.status === 'SUCCEEDED' ? 'completion' : 'failure', tone: presentation.tone,
        title: `${presentation.label}: ${who}${s.attempt > 1 ? ` (attempt ${s.attempt})` : ''}`,
        detail: s.status === 'FAILED' ? (s.errorCode ?? null) : null, rank: s.rank,
      });
    }
  }
  for (const a of actions) {
    const accepted = a.status === 'ACCEPTED';
    const delegate = a.action === 'workflow.delegate';
    add({
      key: `${a.id}:action`, at: a.createdAt, kind: 'delegation', tone: accepted ? 'info' : 'danger',
      title: delegate
        ? (accepted ? `Coordinator delegated a task to ${a.participantRef ?? 'a worker'}` : `Delegation to ${a.participantRef ?? 'a worker'} was refused`)
        : (accepted ? 'Coordinator finished the workflow' : 'Finish was refused'),
      detail: accepted ? a.task : `${a.code}: ${a.message}`, rank: null,
    });
  }
  for (const m of messages) {
    if (m.kind === 'STATUS') {
      add({ key: `${m.id}:journal`, at: m.createdAt, kind: 'journal', tone: 'neutral', title: m.body, detail: null, rank: m.stepRank });
    }
  }
  for (const approval of approvals) {
    add({ key: `${approval.id}:requested`, at: approval.requestedAt, kind: 'approval', tone: 'warning', title: `Approval requested: ${approval.toolName ?? 'a tool'}`, detail: approval.expiresAt ? `Expires ${approval.expiresAt}` : null, rank: null });
    if (approval.decidedAt) {
      const granted = approval.status === 'APPROVED';
      add({ key: `${approval.id}:decided`, at: approval.decidedAt, kind: 'approval', tone: granted ? 'success' : 'danger', title: `Approval ${granted ? 'granted' : approval.status.toLowerCase()}: ${approval.toolName ?? 'a tool'}`, detail: null, rank: null });
    }
  }
  if (run.cancelRequestedAt) {
    add({ key: 'run:cancel', at: run.cancelRequestedAt, kind: 'run', tone: 'warning', title: 'Cancellation requested', detail: 'Nothing new starts; started steps are not undone.', rank: null });
  }
  if (run.completedAt) {
    const status = RUN_STATUS_LABEL[run.status];
    add({ key: 'run:completed', at: run.completedAt, kind: 'run', tone: status.tone, title: `Workflow ${status.label.toLowerCase()}`, detail: run.errorCode, rank: null });
  }
  return events.sort((a, b) => a.at.localeCompare(b.at) || a.key.localeCompare(b.key));
}

// ---------------------------------------------------------------------------------------------------- budget, retry, cancel

export interface BudgetView {
  readonly budget: number;
  readonly used: number;
  readonly reserved: number | null;
  readonly remaining: number;
  /** 0..1 of the budget already used (capped for display, the real overrun is reported separately). */
  readonly utilization: number;
  readonly overrun: number;
}

/** Token budget of a run. Tokens are NOT converted to money: no reliable price data exists. */
export function budgetOf(run: WorkflowRun, summary: RunSummary | null): BudgetView {
  const used = run.tokensUsed;
  const budget = run.tokenBudget;
  return {
    budget,
    used,
    reserved: summary ? summary.reservedTokens : null,
    remaining: Math.max(0, budget - used),
    utilization: budget > 0 ? Math.min(1, used / budget) : 0,
    overrun: Math.max(0, used - budget),
  };
}

export type RetryAvailability =
  | { readonly state: 'available'; readonly rank: number; readonly reason: string }
  | { readonly state: 'unavailable'; readonly why: 'not-waiting' | 'parallel' | 'unknown-outcome' | 'not-safe' | 'budget' | 'permission' | 'no-step'; readonly reason: string };

/** What the person may attempt. It only mirrors the Core API rules for display: the Core API remains the authority (it answers 409 otherwise). */
export function retryAvailability(
  run: WorkflowRun,
  steps: readonly WorkflowStepView[],
  summary: RunSummary | null,
  can: (permission: 'workflow.execute' | 'agent.execute') => boolean,
): RetryAvailability {
  const failed = [...steps].sort((a, b) => b.rank - a.rank || b.attempt - a.attempt).find((s) => s.status === 'FAILED');
  if (failed?.failureCategory === 'OUTCOME_UNKNOWN') {
    return { state: 'unavailable', why: 'unknown-outcome', reason: 'The outcome of an external action is unknown. It is never replayed, automatically or by hand, and there is no reconciliation mechanism yet.' };
  }
  if (run.maxParallelWorkers > 1 && run.status !== 'SUCCEEDED') {
    return { state: 'unavailable', why: 'parallel', reason: 'Manual recovery is not available for workflows with parallel workers. Eligible failures are retried automatically within the workflow limits.' };
  }
  if (run.status !== 'WAITING_RECOVERY') {
    return { state: 'unavailable', why: 'not-waiting', reason: isTerminalRun(run.status) ? 'The workflow has ended: a finished workflow cannot be restarted.' : 'Only a workflow waiting for recovery can retry a step.' };
  }
  if (!failed) {
    return { state: 'unavailable', why: 'no-step', reason: 'No failed step to retry.' };
  }
  if (!can('workflow.execute') || !can('agent.execute')) {
    return { state: 'unavailable', why: 'permission', reason: 'Retrying needs the permissions to execute workflows and agents.' };
  }
  if (failed.retrySafe !== true || (failed.failureCategory !== 'TRANSIENT_PROVIDER_FAILURE' && failed.failureCategory !== 'PERMANENT_PROVIDER_FAILURE')) {
    return { state: 'unavailable', why: 'not-safe', reason: 'This failure cannot be retried safely.' };
  }
  if (summary && summary.manualRetries >= 3) {
    return { state: 'unavailable', why: 'budget', reason: 'The manual retry budget of this run (3) is used up.' };
  }
  if (run.tokensUsed >= run.tokenBudget) {
    return { state: 'unavailable', why: 'budget', reason: 'The token budget of the workflow is used up.' };
  }
  return { state: 'available', rank: failed.rank, reason: 'The failure is known and no external action started: a new attempt can be requested. The Core API revalidates everything.' };
}

export interface CancelImpact {
  readonly queued: number;
  readonly running: number;
  readonly waitingApproval: number;
  readonly lines: readonly string[];
}

/** What a cancellation will do, stated from the current attempts. External effects already produced are never claimed to be undone. */
export function cancelImpact(steps: readonly WorkflowStepView[]): CancelImpact {
  const latest = layoutRun(steps).flatMap((c) => c.workers.map((w) => w.latest));
  const queued = latest.filter((s) => s.status === 'QUEUED').length;
  const running = latest.filter((s) => s.status === 'RUNNING').length;
  const waiting = latest.filter((s) => s.status === 'WAITING_APPROVAL').length;
  const lines: string[] = [];
  if (queued > 0) lines.push(`${queued} queued worker(s) will be cancelled before they start.`);
  if (running > 0) {
    lines.push(`${running} active worker(s) will be stopped. A worker with a tool call in progress gets a 30 second grace period; if the call's outcome is still unknown it is reported as UNKNOWN, never as cancelled or undone.`);
  }
  if (waiting > 0) lines.push(`${waiting} worker(s) waiting for approval will be stopped and their pending approvals closed.`);
  lines.push('Actions that already took effect are not rolled back.');
  return { queued, running, waitingApproval: waiting, lines };
}

// ---------------------------------------------------------------------------------------------------- analytics

/** A measured value, or the explicit absence of one: never a placeholder number. */
export interface Metric {
  readonly value: number | null;
  /** How many records the value is based on. */
  readonly basedOn: number;
}

const metric = (value: number | null, basedOn: number): Metric => ({ value, basedOn });

export interface OverviewAnalytics {
  readonly total: number;
  readonly active: number;
  readonly succeeded: number;
  readonly failed: number;
  readonly cancelled: number;
  readonly waitingApproval: number;
  readonly waitingRecovery: number;
  readonly parallel: number;
  /** succeeded / (succeeded + failed + timeout): cancelled runs are neither successes nor failures. */
  readonly successRate: Metric;
  readonly averageDurationMs: Metric;
  readonly tokensUsed: number;
  readonly budgetUtilization: Metric;
}

/** Overview numbers from the runs that were loaded (a page): they describe those runs only and say how many that is. */
export function overviewAnalytics(runs: readonly WorkflowRun[]): OverviewAnalytics {
  const count = (pred: (r: WorkflowRun) => boolean) => runs.filter(pred).length;
  const succeeded = count((r) => r.status === 'SUCCEEDED');
  const failed = count((r) => r.status === 'FAILED' || r.status === 'TIMEOUT');
  const judged = succeeded + failed;
  const durations = runs
    .filter((r) => isTerminalRun(r.status) && r.startedAt && r.completedAt)
    .map((r) => Date.parse(r.completedAt as string) - Date.parse(r.startedAt as string))
    .filter((d) => Number.isFinite(d) && d >= 0);
  const budgeted = runs.filter((r) => r.tokenBudget > 0);
  const budget = budgeted.reduce((sum, r) => sum + r.tokenBudget, 0);
  return {
    total: runs.length,
    active: count((r) => !isTerminalRun(r.status)),
    succeeded,
    failed,
    cancelled: count((r) => r.status === 'CANCELLED'),
    waitingApproval: count((r) => r.status === 'WAITING_APPROVAL'),
    waitingRecovery: count((r) => r.status === 'WAITING_RECOVERY'),
    parallel: count((r) => r.maxParallelWorkers > 1),
    successRate: metric(judged > 0 ? succeeded / judged : null, judged),
    averageDurationMs: metric(durations.length ? durations.reduce((a, b) => a + b, 0) / durations.length : null, durations.length),
    tokensUsed: runs.reduce((sum, r) => sum + r.tokensUsed, 0),
    budgetUtilization: metric(budget > 0 ? runs.reduce((sum, r) => sum + r.tokensUsed, 0) / budget : null, budgeted.length),
  };
}

export interface RunAnalytics {
  readonly attempts: number;
  readonly retries: number;
  readonly failedAttempts: number;
  readonly peakConcurrency: number;
  readonly averageQueueMs: Metric;
  /** Wall time of the parallel groups (first dispatch to the last end), only for groups that are finished. */
  readonly parallelGroupMs: Metric;
  readonly approvalWaitMs: Metric;
  readonly agentDistribution: readonly { readonly agent: string; readonly attempts: number }[];
}

/** Analytics of ONE run from its summary, attempts and (optionally) its decided approvals. Missing history yields `null`, never an estimate. */
export function runAnalytics(summary: RunSummary, steps: readonly WorkflowStepView[], approvals: readonly TimelineApproval[] = []): RunAnalytics {
  const queued = summary.attemptList.filter((a: AttemptUsage) => a.queueWaitMs !== null && a.groupNo !== null).map((a) => a.queueWaitMs as number);
  const finished = summary.groups.filter((g) => g.workers > 1 && g.firstDispatchedAt && g.finishedAt);
  const groupDurations = finished.map((g) => Date.parse(g.finishedAt as string) - Date.parse(g.firstDispatchedAt as string)).filter((d) => d >= 0);
  const waits = approvals.filter((a) => a.decidedAt).map((a) => Date.parse(a.decidedAt as string) - Date.parse(a.requestedAt)).filter((d) => d >= 0);
  const perAgent = new Map<string, number>();
  for (const s of steps) {
    const name = s.agentName ?? 'Unknown agent';
    perAgent.set(name, (perAgent.get(name) ?? 0) + 1);
  }
  const mean = (values: number[]) => (values.length ? values.reduce((a, b) => a + b, 0) / values.length : null);
  return {
    attempts: summary.attempts,
    retries: summary.automaticRetries + summary.manualRetries,
    failedAttempts: summary.failedAttempts,
    peakConcurrency: summary.peakConcurrency,
    averageQueueMs: metric(mean(queued), queued.length),
    parallelGroupMs: metric(mean(groupDurations), groupDurations.length),
    approvalWaitMs: metric(mean(waits), waits.length),
    agentDistribution: [...perAgent.entries()].map(([agent, attempts]) => ({ agent, attempts })).sort((a, b) => b.attempts - a.attempts || a.agent.localeCompare(b.agent)),
  };
}

// ---------------------------------------------------------------------------------------------------- formatting

export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms) || ms < 0) return '—';
  if (ms < 1000) return `${Math.round(ms)} ms`;
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} min ${s % 60} s`;
  return `${Math.floor(m / 60)} h ${m % 60} min`;
}

export function formatTokens(n: number): string {
  return new Intl.NumberFormat('en-US').format(n);
}

export function stepDurationMs(step: WorkflowStepView, now: number = Date.now()): number | null {
  if (!step.startedAt) return null;
  const end = step.completedAt ? Date.parse(step.completedAt) : isFinalStep(step.status) ? null : now;
  return end === null ? null : Math.max(0, end - Date.parse(step.startedAt));
}

export function isLive(status: StepStatus): boolean {
  return status === 'RUNNING' || status === 'WAITING_APPROVAL';
}
