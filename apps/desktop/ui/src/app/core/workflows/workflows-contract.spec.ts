import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { FakeDesktop } from '../../../testing/fake-desktop';
import { REAL_API_C3 } from '../../../testing/real-api-c3';
import { WorkflowsApi } from './workflows.api';
import {
  AttemptUsage,
  FailureCategory,
  GroupSummary,
  Page,
  RunStatus,
  RunSummary,
  StepDefinition,
  StepStatus,
  WorkflowAction,
  WorkflowDefinition,
  WorkflowMessage,
  WorkflowParticipant,
  WorkflowRun,
  WorkflowStepView,
} from './workflows.models';

/**
 * The reference here is NOT a hand-written fixture: `REAL_API_C3` holds responses captured from the running Core API by its own integration
 * test (real controllers and PostgreSQL). If a backend DTO gains, loses or renames a field, or an enum gains a value, these tests fail and
 * the Angular model must be reviewed. The `satisfies` clauses make the key lists complete at compile time.
 */
const DEFINITION_KEYS = {
  id: 1, organizationId: 1, name: 1, description: 1, status: 1, mode: 1, coordinatorAgentId: 1, version: 1, participants: 1, steps: 1, maxSteps: 1,
  maxRetriesPerStep: 1, tokenBudget: 1, deadlineSeconds: 1, failurePolicy: 1, recoveryMode: 1, stepTimeoutSeconds: 1, maxParallelWorkers: 1,
  createdBy: 1, createdAt: 1, updatedAt: 1, archivedAt: 1,
} satisfies Record<keyof WorkflowDefinition, 1>;
const PARTICIPANT_KEYS = { agentId: 1, agentName: 1, agentStatus: 1, coordinator: 1, addedBy: 1, addedAt: 1 } satisfies Record<keyof WorkflowParticipant, 1>;
const STEP_DEFINITION_KEYS = { rank: 1, name: 1, agentId: 1, instruction: 1, inputsFrom: 1, stage: 1 } satisfies Record<keyof StepDefinition, 1>;
const RUN_KEYS = {
  id: 1, organizationId: 1, definitionId: 1, definitionVersion: 1, mode: 1, maxParallelWorkers: 1, status: 1, requestedBy: 1, objective: 1, totalSteps: 1,
  nextRank: 1, tokensUsed: 1, tokenBudget: 1, deadlineAt: 1, result: 1, errorCode: 1, errorMessage: 1, cancelRequestedAt: 1, createdAt: 1, startedAt: 1, completedAt: 1,
} satisfies Record<keyof WorkflowRun, 1>;
const STEP_KEYS = {
  id: 1, runId: 1, rank: 1, attempt: 1, name: 1, agentId: 1, agentName: 1, role: 1, executionId: 1, status: 1, inputsFrom: 1, resultSummary: 1, errorCode: 1,
  errorMessage: 1, createdAt: 1, startedAt: 1, completedAt: 1, launchReason: 1, failureCategory: 1, retrySafe: 1, nextAttemptAt: 1, deadlineAt: 1, groupNo: 1,
} satisfies Record<keyof WorkflowStepView, 1>;
const MESSAGE_KEYS = { id: 1, sequence: 1, stepRank: 1, kind: 1, body: 1, createdAt: 1 } satisfies Record<keyof WorkflowMessage, 1>;
const ACTION_KEYS = {
  id: 1, stepId: 1, executionId: 1, toolCallId: 1, action: 1, status: 1, code: 1, message: 1, participantRef: 1, task: 1, inputRanks: 1, resultStepId: 1, createdAt: 1,
} satisfies Record<keyof WorkflowAction, 1>;
const SUMMARY_KEYS = {
  runId: 1, mode: 1, status: 1, failurePolicy: 1, createdAt: 1, startedAt: 1, completedAt: 1, deadlineAt: 1, durationMs: 1, attempts: 1, automaticRetries: 1,
  manualRetries: 1, failedAttempts: 1, failuresByCategory: 1, inputTokens: 1, outputTokens: 1, coordinatorTokens: 1, workerTokens: 1, tokenBudget: 1, tokensRemaining: 1,
  nextRetryAt: 1, waitingRecovery: 1, errorCode: 1, attemptList: 1, maxParallelWorkers: 1, peakConcurrency: 1, reservedTokens: 1, groups: 1,
} satisfies Record<keyof RunSummary, 1>;
const ATTEMPT_KEYS = {
  rank: 1, attempt: 1, role: 1, launchReason: 1, status: 1, errorCode: 1, failureCategory: 1, retrySafe: 1, executionId: 1, durationMs: 1, inputTokens: 1, outputTokens: 1,
  groupNo: 1, queueWaitMs: 1,
} satisfies Record<keyof AttemptUsage, 1>;
const GROUP_KEYS = {
  groupNo: 1, status: 1, workers: 1, queued: 1, running: 1, waitingApproval: 1, succeeded: 1, failed: 1, cancelled: 1, firstDispatchedAt: 1, finishedAt: 1, peakConcurrency: 1,
} satisfies Record<keyof GroupSummary, 1>;

const RUN_STATUS: readonly RunStatus[] = ['PENDING', 'RUNNING', 'WAITING_APPROVAL', 'WAITING_RECOVERY', 'CANCEL_REQUESTED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMEOUT'];
const STEP_STATUS: readonly StepStatus[] = ['QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'CANCELLED'];
const FAILURE: readonly FailureCategory[] = [
  'TRANSIENT_PROVIDER_FAILURE', 'PERMANENT_PROVIDER_FAILURE', 'TOOL_FAILURE', 'OUTCOME_UNKNOWN', 'AUTHORIZATION_FAILURE', 'WORKFLOW_LIMIT_EXCEEDED', 'UNCLASSIFIED',
];

const real = <T>(name: string): T => REAL_API_C3[name] as T;
const keys = (value: object) => Object.keys(value).sort();
const expected = (map: object) => Object.keys(map).sort();
const pageOf = <T>(name: string) => real<Page<T>>(name).content;

describe('Angular workflow models match the real Core API', () => {
  it.each(['definitionPipeline', 'definitionCoordinated'])('definition %s has exactly the modelled fields', (name) => {
    const d = real<WorkflowDefinition>(name);
    expect(keys(d)).toEqual(expected(DEFINITION_KEYS));
    expect(['PIPELINE', 'COORDINATED']).toContain(d.mode);
    expect(['FAIL_FAST', 'CONTINUE', 'RETRY']).toContain(d.failurePolicy);
    expect(['NONE', 'MANUAL']).toContain(d.recoveryMode);
    d.participants.forEach((p) => expect(keys(p)).toEqual(expected(PARTICIPANT_KEYS)));
    d.steps.forEach((s) => expect(keys(s)).toEqual(expected(STEP_DEFINITION_KEYS)));
  });

  it('a coordinated definition names its coordinator among the participants, a pipeline has none', () => {
    const c = real<WorkflowDefinition>('definitionCoordinated');
    expect(c.coordinatorAgentId).not.toBeNull();
    expect(c.participants.filter((p) => p.coordinator).map((p) => p.agentId)).toEqual([c.coordinatorAgentId]);
    expect(real<WorkflowDefinition>('definitionPipeline').coordinatorAgentId).toBeNull();
  });

  it.each(['runStarted', 'runSucceeded', 'runPartialFailure', 'runWaitingRecovery', 'runCoordinated'])('run %s has exactly the modelled fields', (name) => {
    const r = real<WorkflowRun>(name);
    expect(keys(r)).toEqual(expected(RUN_KEYS));
    expect(RUN_STATUS).toContain(r.status);
  });

  it.each(['stepsQueuedAndRunning', 'stepsRetryScheduled', 'stepsWithRetry', 'stepsOutcomeUnknown', 'stepsWaitingRecovery', 'stepsCoordinated'])(
    'every attempt in %s has exactly the modelled fields and known enum values',
    (name) => {
      for (const s of pageOf<WorkflowStepView>(name)) {
        expect(keys(s)).toEqual(expected(STEP_KEYS));
        expect(STEP_STATUS).toContain(s.status);
        expect(['WORKER', 'COORDINATOR']).toContain(s.role);
        expect(['INITIAL', 'AUTO_RETRY', 'MANUAL_RETRY']).toContain(s.launchReason);
        if (s.failureCategory) expect(FAILURE).toContain(s.failureCategory);
      }
    },
  );

  it('queued work has no execution yet; running work has one', () => {
    const steps = pageOf<WorkflowStepView>('stepsQueuedAndRunning');
    expect(steps.filter((s) => s.status === 'QUEUED').every((s) => s.executionId === null && s.startedAt === null)).toBe(true);
    expect(steps.filter((s) => s.status === 'RUNNING').every((s) => s.executionId !== null && s.startedAt !== null)).toBe(true);
  });

  it('journal messages and coordinator actions have exactly the modelled fields', () => {
    for (const m of pageOf<WorkflowMessage>('messages')) {
      expect(keys(m)).toEqual(expected(MESSAGE_KEYS));
      expect(['OBJECTIVE', 'ASSIGNMENT', 'RESULT', 'STATUS']).toContain(m.kind);
    }
    const actions = pageOf<WorkflowAction>('actionsPage');
    for (const a of actions) {
      expect(keys(a)).toEqual(expected(ACTION_KEYS));
      expect(['workflow.delegate', 'workflow.finish']).toContain(a.action);
      expect(['ACCEPTED', 'REJECTED']).toContain(a.status);
    }
    expect(actions.some((a) => a.status === 'REJECTED' && a.code === 'UNKNOWN_PARTICIPANT')).toBe(true);
  });

  it.each(['summaryQueued', 'summaryParallelRunning', 'summaryRetryScheduled', 'summarySucceeded', 'summaryOutcomeUnknown', 'summaryWaitingRecovery', 'summaryCoordinated'])(
    'summary %s has exactly the modelled fields, groups and attempts',
    (name) => {
      const s = real<RunSummary>(name);
      expect(keys(s)).toEqual(expected(SUMMARY_KEYS));
      expect(RUN_STATUS).toContain(s.status);
      s.attemptList.forEach((a) => expect(keys(a)).toEqual(expected(ATTEMPT_KEYS)));
      s.groups.forEach((g) => {
        expect(keys(g)).toEqual(expected(GROUP_KEYS));
        expect(['QUEUED', 'RUNNING', 'WAITING_FOR_APPROVAL', 'COMPLETED', 'PARTIALLY_FAILED', 'FAILED', 'CANCELLED']).toContain(g.status);
      });
    },
  );

  it('the summary tells the truth the UI relies on: queued work, retries, tokens of failed attempts, unknown outcome, recovery', () => {
    const queued = real<RunSummary>('summaryQueued');
    expect(queued.groups[0].queued).toBe(1);
    expect(queued.reservedTokens).toBeGreaterThan(0);
    const done = real<RunSummary>('summarySucceeded');
    expect(done.automaticRetries).toBe(1);
    expect(done.failuresByCategory['TRANSIENT_PROVIDER_FAILURE']).toBe(1);
    expect(done.reservedTokens).toBe(0);
    expect(done.peakConcurrency).toBeGreaterThanOrEqual(2);
    expect(done.attemptList.find((a) => a.status === 'FAILED')!.inputTokens).toBe(40);        // the failed attempt's usage is counted (C3.4 fix)
    expect(real<RunSummary>('summaryOutcomeUnknown').failuresByCategory['OUTCOME_UNKNOWN']).toBe(1);
    expect(real<RunSummary>('summaryWaitingRecovery').waitingRecovery).toBe(true);
    expect(real<WorkflowRun>('runPartialFailure').errorCode).toBe('PARTIAL_FAILURE');
  });

  it('list endpoints answer with a page, never a bare array', () => {
    for (const name of ['definitionsPage', 'runsPage', 'stepsWithRetry', 'messages', 'actionsPage']) {
      const page = real<Page<unknown>>(name);
      expect(Array.isArray(page)).toBe(false);
      expect(Object.keys(page).sort()).toEqual(['content', 'page', 'size', 'totalElements', 'totalPages']);
    }
  });
});

describe('WorkflowsApi', () => {
  let desktop: FakeDesktop;
  const ORG = '0195f0a4-1c2d-7e3f-8a9b-00000000000a';
  const RUN = '0195f0a4-1c2d-7e3f-8a9b-00000000000b';

  beforeEach(() => {
    desktop = new FakeDesktop().install();
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  });
  afterEach(() => FakeDesktop.uninstall());

  it('calls exactly the endpoints the Electron allowlist knows, with validated identifiers', async () => {
    const api = TestBed.inject(WorkflowsApi);
    await api.listDefinitions(ORG, { status: 'ACTIVE' });
    await api.listRuns(ORG, { status: 'WAITING_RECOVERY', definitionId: RUN, page: 1 });
    await api.getRun(ORG, RUN);
    await api.runSteps(ORG, RUN);
    await api.runMessages(ORG, RUN);
    await api.runActions(ORG, RUN);
    await api.runSummary(ORG, RUN);
    await api.cancelRun(ORG, RUN);
    await api.retryStep(ORG, RUN, 3);
    await api.startRun(ORG, RUN, 'Analyze', 'req-1');
    await api.setParticipants(ORG, RUN, [ORG], ORG);
    await api.setSteps(ORG, RUN, [{ name: 'a', agentId: ORG, instruction: 'x', stage: 1 }]);
    const seen = desktop.requests.map((r) => `${r.method} ${r.path.replace(ORG, ':org').replace(new RegExp(RUN, 'g'), ':id')}`);
    expect(seen).toEqual([
      'GET /api/v1/organizations/:org/workflows',
      'GET /api/v1/organizations/:org/workflow-runs',
      'GET /api/v1/organizations/:org/workflow-runs/:id',
      'GET /api/v1/organizations/:org/workflow-runs/:id/steps',
      'GET /api/v1/organizations/:org/workflow-runs/:id/messages',
      'GET /api/v1/organizations/:org/workflow-runs/:id/actions',
      'GET /api/v1/organizations/:org/workflow-runs/:id/summary',
      'POST /api/v1/organizations/:org/workflow-runs/:id/cancel',
      'POST /api/v1/organizations/:org/workflow-runs/:id/steps/3/retry',
      'POST /api/v1/organizations/:org/workflows/:id/runs',
      'PUT /api/v1/organizations/:org/workflows/:id/participants',
      'PUT /api/v1/organizations/:org/workflows/:id/steps',
    ]);
    expect(desktop.requests[1].query).toEqual({ size: 50, status: 'WAITING_RECOVERY', definitionId: RUN, page: 1 });
    expect(desktop.requests[9].body).toEqual({ objective: 'Analyze', requestId: 'req-1' });
    expect(desktop.requests.every((r) => !r.path.includes('/internal/'))).toBe(true);
  });

  it('refuses a malformed identifier or step number before any request leaves the renderer', () => {
    const api = TestBed.inject(WorkflowsApi);
    expect(() => api.getRun(ORG, 'not-a-uuid')).toThrow('Invalid identifier');
    expect(() => api.getRun(`${ORG}/../x`, RUN)).toThrow('Invalid identifier');
    expect(() => api.listRuns(ORG, { definitionId: `${RUN}' OR 1=1` })).toThrow('Invalid identifier');
    expect(() => api.retryStep(ORG, RUN, 0)).toThrow('Invalid step number');
    expect(() => api.retryStep(ORG, RUN, 1.5)).toThrow('Invalid step number');
    expect(() => api.retryStep(ORG, RUN, 100)).toThrow('Invalid step number');
    expect(desktop.requests).toHaveLength(0);
  });
});
