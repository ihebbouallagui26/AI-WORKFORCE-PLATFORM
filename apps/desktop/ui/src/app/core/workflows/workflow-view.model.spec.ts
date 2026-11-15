import { REAL_API_C3 } from '../../../testing/real-api-c3';
import {
  budgetOf,
  buildTimeline,
  cancelImpact,
  formatDuration,
  groupStatus,
  layoutRun,
  overviewAnalytics,
  presentStep,
  retryAvailability,
  runAnalytics,
} from './workflow-view.model';
import { Page, RunSummary, WorkflowAction, WorkflowMessage, WorkflowRun, WorkflowStepView } from './workflows.models';

const real = <T>(name: string): T => REAL_API_C3[name] as T;
const stepsOf = (name: string) => real<Page<WorkflowStepView>>(name).content;
const step = (extra: Partial<WorkflowStepView>): WorkflowStepView => ({ ...stepsOf('stepsQueuedAndRunning')[0], ...extra });
const can = () => true;

describe('presentStep: what a status means', () => {
  it('never shows a queued worker as running and says no execution exists yet', () => {
    const queued = stepsOf('stepsQueuedAndRunning').find((s) => s.status === 'QUEUED')!;
    const p = presentStep(queued, null);
    expect(p.phase).toBe('queued');
    expect(p.label).toBe('Waiting for capacity');
    expect(p.explanation).toContain('No execution exists yet');
    expect(queued.executionId).toBeNull();
  });

  it('distinguishes dispatched (not confirmed or still starting) from running', () => {
    const running = stepsOf('stepsQueuedAndRunning').find((s) => s.status === 'RUNNING')!;
    expect(presentStep(running, 'RUNNING').phase).toBe('running');
    expect(presentStep(running, 'PENDING').phase).toBe('dispatched');
    const unconfirmed = presentStep(running, null);
    expect(unconfirmed.phase).toBe('dispatched');          // a dispatch is a hand-over, not proof of work
    expect(unconfirmed.explanation).toContain('not confirmed');
  });

  it('shows an attempt that waits for a person, a completed one, a cancelled one', () => {
    expect(presentStep(step({ status: 'WAITING_APPROVAL' })).phase).toBe('waiting-approval');
    expect(presentStep(step({ status: 'SUCCEEDED' })).phase).toBe('completed');
    expect(presentStep(step({ status: 'CANCELLED' })).phase).toBe('cancelled');
  });

  it('shows a failed attempt as a failure, with its scheduled retry, and an unknown outcome as such (never a plain failure)', () => {
    const scheduled = stepsOf('stepsRetryScheduled').find((s) => s.status === 'FAILED')!;
    expect(scheduled.nextAttemptAt).not.toBeNull();
    expect(presentStep(scheduled).label).toBe('Failed, retry scheduled');
    const unknown = stepsOf('stepsOutcomeUnknown').find((s) => s.failureCategory === 'OUTCOME_UNKNOWN')!;
    const p = presentStep(unknown);
    expect(p.phase).toBe('unknown-outcome');
    expect(p.explanation).toContain('never replayed');
    expect(presentStep(step({ status: 'FAILED', failureCategory: 'PERMANENT_PROVIDER_FAILURE', nextAttemptAt: null })).phase).toBe('failed');
  });
});

describe('layoutRun: fan-out and fan-in from the real API data', () => {
  it('puts the three workers of one group in ONE parallel column, queued included', () => {
    const columns = layoutRun(stepsOf('stepsQueuedAndRunning'));
    expect(columns).toHaveLength(1);
    expect(columns[0].parallel).toBe(true);
    expect(columns[0].workers.map((w) => w.latest.status)).toEqual(['RUNNING', 'RUNNING', 'QUEUED']);
    expect(columns[0].status).toBe('RUNNING');
  });

  it('keeps every attempt of a retried worker and shows the LAST one as current', () => {
    const columns = layoutRun(stepsOf('stepsWithRetry'));
    expect(columns.map((c) => c.workers.length)).toEqual([3, 1]);            // the group of three, then the synthesis step
    const retried = columns[0].workers.find((w) => w.rank === 2)!;
    expect(retried.attempts.map((a) => a.attempt)).toEqual([1, 2]);
    expect(retried.attempts[0].status).toBe('FAILED');                       // the failed attempt is kept, never overwritten
    expect(retried.latest.attempt).toBe(2);
    expect(retried.latest.launchReason).toBe('AUTO_RETRY');
    expect(columns[1].parallel).toBe(false);
    expect(columns[0].status).toBe('COMPLETED');
  });

  it('lays a coordinated run out as coordinator turn then parallel workers', () => {
    const columns = layoutRun(stepsOf('stepsCoordinated'));
    expect(columns[0].kind).toBe('coordinator');
    expect(columns[1].kind).toBe('stage');
    expect(columns[1].workers.length).toBeGreaterThan(0);
  });

  it('derives a group status from its latest attempts', () => {
    const cards = (statuses: WorkflowStepView['status'][]) =>
      statuses.map((status, i) => ({ rank: i + 1, latest: step({ status, rank: i + 1 }), attempts: [step({ status, rank: i + 1 })] }));
    expect(groupStatus(cards(['SUCCEEDED', 'SUCCEEDED']))).toBe('COMPLETED');
    expect(groupStatus(cards(['SUCCEEDED', 'FAILED']))).toBe('PARTIALLY_FAILED');
    expect(groupStatus(cards(['FAILED', 'CANCELLED']))).toBe('FAILED');
    expect(groupStatus(cards(['CANCELLED']))).toBe('CANCELLED');
    expect(groupStatus(cards(['RUNNING', 'QUEUED']))).toBe('RUNNING');
    expect(groupStatus(cards(['WAITING_APPROVAL', 'SUCCEEDED']))).toBe('WAITING_FOR_APPROVAL');
    expect(groupStatus(cards(['WAITING_APPROVAL', 'QUEUED']))).toBe('QUEUED');
  });
});

describe('buildTimeline: reconstructed from the persisted records', () => {
  const run = real<WorkflowRun>('runSucceeded');
  const events = buildTimeline(run, stepsOf('stepsWithRetry'), [], real<Page<WorkflowMessage>>('messages').content);

  it('is chronological and covers launch, queueing, dispatch, retry and completion', () => {
    const times = events.map((e) => e.at);
    expect([...times].sort()).toEqual(times);
    const kinds = new Set(events.map((e) => e.kind));
    for (const kind of ['run', 'queued', 'dispatched', 'retry', 'completion', 'failure']) expect(kinds).toContain(kind);
    expect(events[0].title).toBe('Workflow launched');
    expect(events.at(-1)!.kind).toBe('run');
  });

  it('presents a retry as a new attempt, automatic or manual, and a scheduled retry', () => {
    expect(events.some((e) => e.title.startsWith('Retry executed (attempt 2)') && e.detail === 'Automatic retry')).toBe(true);
    expect(events.some((e) => e.title.startsWith('Retry scheduled'))).toBe(true);
    expect(events.some((e) => e.kind === 'failure' && e.title.startsWith('Failed'))).toBe(true);
  });

  it('shows coordinator delegations, accepted and refused, with the reason', () => {
    const actions = real<Page<WorkflowAction>>('actionsPage').content;
    const timeline = buildTimeline(real<WorkflowRun>('runCoordinated'), stepsOf('stepsCoordinated'), actions, []);
    expect(timeline.filter((e) => e.kind === 'delegation' && e.tone === 'info').length).toBe(2);
    const refused = timeline.find((e) => e.kind === 'delegation' && e.tone === 'danger')!;
    expect(refused.title).toContain('was refused');
    expect(refused.detail).toContain('UNKNOWN_PARTICIPANT');
  });

  it('includes approvals when they are provided and tells apart a decision from a request', () => {
    const t = buildTimeline(run, [], [], [], [
      { id: 'a1', executionId: 'e1', toolName: 'Echo', status: 'APPROVED', requestedAt: '2026-11-15T02:00:00Z', decidedAt: '2026-11-15T02:01:00Z', expiresAt: null },
      { id: 'a2', executionId: 'e2', toolName: null, status: 'REJECTED', requestedAt: '2026-11-15T02:02:00Z', decidedAt: '2026-11-15T02:03:00Z', expiresAt: '2026-11-15T03:00:00Z' },
    ]);
    expect(t.filter((e) => e.kind === 'approval').map((e) => e.title)).toEqual(
      expect.arrayContaining(['Approval requested: Echo', 'Approval granted: Echo', 'Approval rejected: a tool']),
    );
  });
});

describe('budget, retry eligibility and cancellation: what is offered and why', () => {
  it('computes the budget from actual usage and reports an overrun instead of hiding it', () => {
    const summary = real<RunSummary>('summarySucceeded');
    const run = real<WorkflowRun>('runSucceeded');
    const b = budgetOf(run, summary);
    expect(b.used).toBe(run.tokensUsed);
    expect(b.remaining).toBe(run.tokenBudget - run.tokensUsed);
    expect(b.overrun).toBe(0);
    const over = budgetOf({ ...run, tokensUsed: run.tokenBudget + 500 }, summary);
    expect(over.overrun).toBe(500);
    expect(over.remaining).toBe(0);
    expect(over.utilization).toBe(1);
  });

  it('offers a retry only for a sequential run waiting for recovery whose failure is known and safe', () => {
    const run = real<WorkflowRun>('runWaitingRecovery');
    const steps = stepsOf('stepsWaitingRecovery');
    const summary = real<RunSummary>('summaryWaitingRecovery');
    const a = retryAvailability(run, steps, summary, can);
    expect(a.state).toBe('available');
    if (a.state === 'available') expect(a.rank).toBe(1);
    // Without the permissions the button is not offered, and the reason is given.
    expect(retryAvailability(run, steps, summary, (p) => p !== 'agent.execute')).toMatchObject({ state: 'unavailable', why: 'permission' });
    // The manual retry budget is bounded.
    expect(retryAvailability(run, steps, { ...summary, manualRetries: 3 }, can)).toMatchObject({ state: 'unavailable', why: 'budget' });
    // A finished workflow cannot be restarted.
    expect(retryAvailability({ ...run, status: 'FAILED' }, steps, summary, can)).toMatchObject({ state: 'unavailable', why: 'not-waiting' });
  });

  it('explains that manual recovery does not exist for parallel workers and never offers a retry there', () => {
    const run = real<WorkflowRun>('runPartialFailure');
    expect(run.maxParallelWorkers).toBeGreaterThan(1);
    const a = retryAvailability({ ...run, status: 'WAITING_RECOVERY' }, stepsOf('stepsOutcomeUnknown'), null, can);
    expect(a.state).toBe('unavailable');
    expect(retryAvailability(run, stepsOf('stepsWithRetry'), null, can)).toMatchObject({ state: 'unavailable', why: 'parallel' });
  });

  it('says an unknown outcome is never retried, automatically or by hand', () => {
    const run = real<WorkflowRun>('runPartialFailure');
    const a = retryAvailability(run, stepsOf('stepsOutcomeUnknown'), real<RunSummary>('summaryOutcomeUnknown'), can);
    expect(a).toMatchObject({ state: 'unavailable', why: 'unknown-outcome' });
    expect(a.reason).toContain('never replayed');
  });

  it('describes what a cancellation does to queued, active and waiting workers, without claiming anything is undone', () => {
    const impact = cancelImpact(stepsOf('stepsQueuedAndRunning'));
    expect(impact).toMatchObject({ queued: 1, running: 2, waitingApproval: 0 });
    const text = impact.lines.join(' ');
    expect(text).toContain('queued worker');
    expect(text).toContain('UNKNOWN, never as cancelled');
    expect(text).toContain('not rolled back');
    const waiting = cancelImpact([step({ status: 'WAITING_APPROVAL', rank: 1 })]);
    expect(waiting.waitingApproval).toBe(1);
    expect(waiting.lines.join(' ')).toContain('pending approvals closed');
  });
});

describe('analytics: only what was measured', () => {
  it('computes the overview from the runs that were loaded and says what it is based on', () => {
    const runs = real<Page<WorkflowRun>>('runsPage').content;
    const a = overviewAnalytics(runs);
    expect(a.total).toBe(runs.length);
    expect(a.succeeded + a.failed + a.cancelled + a.active).toBe(runs.length);
    expect(a.parallel).toBeGreaterThan(0);
    expect(a.tokensUsed).toBe(runs.reduce((n, r) => n + r.tokensUsed, 0));
    if (a.successRate.value !== null) expect(a.successRate.basedOn).toBe(a.succeeded + a.failed);
  });

  it('reports no value (not zero, not an estimate) when there is nothing to base it on', () => {
    const empty = overviewAnalytics([]);
    expect(empty.successRate).toEqual({ value: null, basedOn: 0 });
    expect(empty.averageDurationMs.value).toBeNull();
    expect(empty.budgetUtilization.value).toBeNull();
    const running = overviewAnalytics([{ ...real<WorkflowRun>('runStarted'), status: 'RUNNING', completedAt: null }]);
    expect(running.successRate.value).toBeNull();            // an open run is neither a success nor a failure
    expect(running.averageDurationMs.value).toBeNull();
    expect(overviewAnalytics([{ ...real<WorkflowRun>('runSucceeded'), status: 'CANCELLED' }]).successRate.value).toBeNull();
  });

  it('measures one run from its recorded attempts: retries, queue time, parallel duration, agent distribution', () => {
    const summary = real<RunSummary>('summarySucceeded');
    const a = runAnalytics(summary, stepsOf('stepsWithRetry'));
    expect(a.attempts).toBe(summary.attempts);
    expect(a.retries).toBe(1);
    expect(a.failedAttempts).toBe(1);
    expect(a.peakConcurrency).toBe(summary.peakConcurrency);
    expect(a.averageQueueMs.basedOn).toBeGreaterThan(0);
    expect(a.parallelGroupMs.value).not.toBeNull();
    expect(a.approvalWaitMs).toEqual({ value: null, basedOn: 0 });         // no approval history was loaded: not invented
    expect(a.agentDistribution.reduce((n, d) => n + d.attempts, 0)).toBe(stepsOf('stepsWithRetry').length);
    const withApprovals = runAnalytics(summary, [], [{ id: 'a', executionId: 'e', toolName: 'x', status: 'APPROVED', requestedAt: '2026-01-01T00:00:00Z', decidedAt: '2026-01-01T00:00:30Z', expiresAt: null }]);
    expect(withApprovals.approvalWaitMs.value).toBe(30_000);
  });

  it('formats durations without inventing precision', () => {
    expect(formatDuration(null)).toBe('—');
    expect(formatDuration(-5)).toBe('—');
    expect(formatDuration(450)).toBe('450 ms');
    expect(formatDuration(12_000)).toBe('12 s');
    expect(formatDuration(125_000)).toBe('2 min 5 s');
    expect(formatDuration(3_900_000)).toBe('1 h 5 min');
  });
});
