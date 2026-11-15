import { ApplicationRef, Component, provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { FakeDesktop } from '../../../testing/fake-desktop';
import { REAL_API } from '../../../testing/real-api-c2';
import { REAL_API_C3 } from '../../../testing/real-api-c3';
import { AUTH_REQUIRED } from '../../core/auth/session.service';
import type { ApiRequest, ApiResponse } from '../../core/desktop/desktop-api';
import { AccessStore } from '../../core/identity/access.store';
import { PermissionCode } from '../../core/identity/identity.models';
import { ConfirmService } from '../../core/services/confirm.service';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { RUN_POLL_MAX_MS, RUN_POLL_MS } from '../../core/workflows/run-monitor';
import { RunSummary, WorkflowAction, WorkflowDefinition, WorkflowMessage, WorkflowRun, WorkflowStepView } from '../../core/workflows/workflows.models';
import { WorkflowDetail } from './workflow-detail';
import { WorkflowEditor } from './workflow-editor';
import { WorkflowRunPage } from './run-page';
import { WorkflowsPage } from './workflows-page';

const ORG = '0195f0a4-1c2d-7e3f-8a9b-00000000000a';
const NOW = '2026-01-01T00:00:00Z';
const real = <T>(name: string): T => REAL_API_C3[name] as T;
const page = <T>(content: readonly T[]) => ({ content, page: 0, size: 50, totalElements: content.length, totalPages: 1 });
const ok = (body: unknown): ApiResponse => ({ status: 200, body });

const READ: PermissionCode[] = ['organization.read', 'workflow.read', 'agent.read', 'agent.execution.read', 'approval.read'];
const ALL: PermissionCode[] = [...READ, 'workflow.manage', 'workflow.execute', 'agent.execute', 'approval.decide'];

interface Scenario {
  readonly run: WorkflowRun;
  readonly steps: readonly WorkflowStepView[];
  readonly summary: RunSummary;
  readonly messages?: readonly WorkflowMessage[];
  readonly actions?: readonly WorkflowAction[];
}

const SCENARIOS: Record<string, Scenario> = {
  running: { run: real<WorkflowRun>('runStarted'), steps: real<{ content: WorkflowStepView[] }>('stepsQueuedAndRunning').content, summary: real<RunSummary>('summaryParallelRunning') },
  queued: { run: real<WorkflowRun>('runStarted'), steps: real<{ content: WorkflowStepView[] }>('stepsQueuedAndRunning').content, summary: real<RunSummary>('summaryQueued') },
  succeeded: {
    run: real<WorkflowRun>('runSucceeded'), steps: real<{ content: WorkflowStepView[] }>('stepsWithRetry').content, summary: real<RunSummary>('summarySucceeded'),
    messages: real<{ content: WorkflowMessage[] }>('messages').content,
  },
  unknown: { run: real<WorkflowRun>('runPartialFailure'), steps: real<{ content: WorkflowStepView[] }>('stepsOutcomeUnknown').content, summary: real<RunSummary>('summaryOutcomeUnknown') },
  recovery: { run: real<WorkflowRun>('runWaitingRecovery'), steps: real<{ content: WorkflowStepView[] }>('stepsWaitingRecovery').content, summary: real<RunSummary>('summaryWaitingRecovery') },
  coordinated: {
    run: real<WorkflowRun>('runCoordinated'), steps: real<{ content: WorkflowStepView[] }>('stepsCoordinated').content, summary: real<RunSummary>('summaryCoordinated'),
    actions: real<{ content: WorkflowAction[] }>('actionsPage').content,
  },
};

const RUNS_LIST: readonly WorkflowRun[] = [
  real<WorkflowRun>('runSucceeded'),
  real<WorkflowRun>('runPartialFailure'),
  real<WorkflowRun>('runWaitingRecovery'),
  real<WorkflowRun>('runCoordinated'),
  { ...real<WorkflowRun>('runStarted'), id: '0195f0a4-1c2d-7e3f-8a9b-0000000000c1', status: 'WAITING_APPROVAL', objective: 'Run that needs approval' },
];
const DEFINITIONS = [real<WorkflowDefinition>('definitionPipeline'), real<WorkflowDefinition>('definitionCoordinated')];
const AGENTS = ['Market Researcher', 'Pricing Analyst', 'Customer Analyst'].map((name, i) => ({
  id: `0195f0a4-1c2d-7e3f-8a9b-00000000010${i}`, handle: name.toLowerCase().replace(/ /g, '-'), name, avatarTone: 1, title: 'Analyst', description: '', department: null,
  responsibilities: [], status: 'ACTIVE', configVersion: 1, skillCount: 0, createdAt: NOW, updatedAt: NOW, version: 0,
}));

class Core {
  scenario = 'running';
  liveStatus: Record<string, string> = {};
  approvals: unknown[] = [];
  fail: Error | null = null;
  inFlight = 0;
  maxInFlight = 0;
  delayMs = 0;
  runReads = 0;
  created: Record<string, unknown> | null = null;

  handle = async (request: ApiRequest): Promise<ApiResponse> => {
    const path = request.path;
    this.inFlight++;
    this.maxInFlight = Math.max(this.maxInFlight, this.inFlight);
    try {
      if (this.delayMs) await new Promise((r) => setTimeout(r, this.delayMs));
      if (this.fail && path.includes('/workflow-runs/')) return { status: 503, error: 'network' } as ApiResponse;
      const s = SCENARIOS[this.scenario];
      if (request.method === 'POST' && path.endsWith('/workflows')) {
        this.created = { ...DEFINITIONS[0], id: '0195f0a4-1c2d-7e3f-8a9b-0000000000d1', participants: [], steps: [], coordinatorAgentId: null };
        return { status: 201, body: this.created };
      }
      if (request.method === 'POST' && path.endsWith('/runs')) return { status: 202, body: { ...s.run, id: '0195f0a4-1c2d-7e3f-8a9b-0000000000e1' } };
      if (request.method === 'POST' && path.endsWith('/cancel')) return ok({ ...s.run, status: 'CANCEL_REQUESTED' });
      if (request.method === 'POST' && /\/retry$/.test(path)) return ok({ ...s.run, status: 'RUNNING' });
      if (request.method === 'POST' && path.endsWith('/archive')) return ok({ ...DEFINITIONS[0], status: 'ARCHIVED' });
      if ((request.method === 'PUT' || request.method === 'PATCH') && this.created && path.includes('/0195f0a4-1c2d-7e3f-8a9b-0000000000d1')) {
        const body = (request.body ?? {}) as { agentIds?: string[]; coordinatorAgentId?: string };
        if (path.endsWith('/participants') && body.agentIds) {
          this.created = {
            ...this.created, coordinatorAgentId: body.coordinatorAgentId ?? null,
            participants: body.agentIds.map((agentId) => ({ agentId, agentName: agentId, agentStatus: 'ACTIVE', coordinator: agentId === body.coordinatorAgentId, addedBy: agentId, addedAt: NOW })),
          };
        }
        return ok(this.created);
      }
      if (request.method === 'PUT' || request.method === 'PATCH') return ok(DEFINITIONS[0]);
      if (path.endsWith('/workflow-runs')) return ok(page(RUNS_LIST));
      if (path.endsWith('/workflows')) return ok(page(DEFINITIONS));
      if (/\/workflows\/[^/]+$/.test(path)) return ok(DEFINITIONS.find((d) => path.endsWith(d.id)) ?? DEFINITIONS[0]);
      if (/\/workflow-runs\/[^/]+$/.test(path)) { this.runReads++; return ok(s.run); }
      if (path.endsWith('/steps')) return ok(page(s.steps));
      if (path.endsWith('/messages')) return ok(page(s.messages ?? []));
      if (path.endsWith('/actions')) return ok(page(s.actions ?? []));
      if (path.endsWith('/summary')) return ok(s.summary);
      if (/\/executions\/[^/]+$/.test(path)) return ok({ status: this.liveStatus[path.split('/').pop() as string] ?? 'RUNNING' });
      if (path.endsWith('/approvals')) return ok(page(this.approvals));
      if (path.endsWith('/agents')) return ok(page(AGENTS));
      if (path.includes('/providers/') || path.endsWith('/skills') || path.endsWith('/tools')) return ok(page([]));
      return ok({});
    } finally {
      this.inFlight--;
    }
  };
}

@Component({ selector: 'app-stub', template: '' })
class Stub {}

async function settle(fixture?: ComponentFixture<unknown>, rounds = 14): Promise<void> {
  const appRef = TestBed.inject(ApplicationRef);
  for (let i = 0; i < rounds; i++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    TestBed.tick();
    fixture?.detectChanges();
    await appRef.whenStable();
  }
}

function setup(permissions: PermissionCode[], options: { authRequired?: boolean; organization?: string | null } = {}) {
  const desktop = new FakeDesktop();
  const core = new Core();
  desktop.handler = core.handle;
  desktop.install();
  const organizationId = signal<string | null>(options.organization === undefined ? ORG : options.organization);
  const granted = signal<ReadonlySet<PermissionCode>>(new Set(permissions));
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([
        { path: 'workflows/runs/:runId', component: Stub },
        { path: 'workflows/:id', component: Stub },
        { path: 'workflows', component: Stub },
      ]),
      { provide: AUTH_REQUIRED, useValue: options.authRequired ?? true },
      { provide: WorkspaceStore, useValue: { currentOrganizationId: organizationId } },
      { provide: AccessStore, useValue: { can: (p: PermissionCode) => granted().has(p), organization: signal({ name: 'Acme' }), reload: () => undefined } },
    ],
  });
  return { desktop, core, organizationId, granted };
}

function render<T>(component: new () => T, inputs: Record<string, unknown> = {}): { fixture: ComponentFixture<T>; el: HTMLElement } {
  const fixture = TestBed.createComponent(component);
  Object.entries(inputs).forEach(([name, value]) => fixture.componentRef.setInput(name, value));
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement };
}

const text = (el: HTMLElement) => (el.textContent ?? '').replace(/\s+/g, ' ');
const q = (el: HTMLElement, selector: string) => el.querySelector(selector) as HTMLElement | null;
const qa = (el: HTMLElement, selector: string) => Array.from(el.querySelectorAll(selector)) as HTMLElement[];
const byText = (el: HTMLElement, selector: string, label: string) => qa(el, selector).find((b) => (b.textContent ?? '').trim() === label);
const typeInto = (input: HTMLInputElement | HTMLTextAreaElement, value: string) => {
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
};

afterEach(() => {
  FakeDesktop.uninstall();
  TestBed.resetTestingModule();
});

// ---------------------------------------------------------------------------------------------------- overview

describe('Workflows overview', () => {
  it('explains that workflows need the Core API in the offline demo build, and offers nothing', async () => {
    setup(ALL, { authRequired: false });
    const { fixture, el } = render(WorkflowsPage);
    await settle(fixture);
    expect(q(el, '[data-testid="mock-mode"]')).not.toBeNull();
    expect(q(el, '[data-testid="new-workflow"]')).toBeNull();
  });

  it('shows no data to a person without workflow.read, and makes no workflow request', async () => {
    const { desktop } = setup(['organization.read']);
    const { fixture, el } = render(WorkflowsPage);
    await settle(fixture);
    expect(q(el, '[data-testid="no-permission"]')).not.toBeNull();
    expect(desktop.requests.filter((r) => r.path.includes('/workflow'))).toHaveLength(0);
  });

  it('computes the overview numbers from the loaded runs only, and says how many that is', async () => {
    const { desktop } = setup(READ);
    const { fixture, el } = render(WorkflowsPage);
    await settle(fixture);
    const stats = text(q(el, '[data-testid="overview"]')!);
    expect(stats).toMatch(/Workflow definitions\s*2/);
    expect(stats).toMatch(/Active runs\s*3/);            // waiting recovery, running (coordinated), waiting approval
    expect(stats).toMatch(/Completed\s*1/);
    expect(stats).toMatch(/Failed\s*1/);
    expect(stats).toMatch(/Waiting for approval\s*1/);
    expect(stats).toMatch(/Waiting for recovery\s*1/);
    expect(text(el)).toContain('most recent run(s) loaded');
    expect(text(q(el, '[data-testid="statistics"]')!)).toMatch(/Success rate\s*50% \(of 2\)/);   // 1 succeeded of 2 judged: cancelled/active are not counted
    expect(desktop.requests.some((r) => r.path.endsWith('/workflows') && r.method === 'GET')).toBe(true);
  });

  it('lists the runs that wait for a person, with a link to each', async () => {
    setup(READ);
    const { fixture, el } = render(WorkflowsPage);
    await settle(fixture);
    const attention = text(q(el, '[data-testid="attention"]')!);
    expect(attention).toContain('Run that needs approval');
    expect(attention).toContain('Needs approval');
    expect(attention).toContain('Waiting for recovery');
    expect(qa(q(el, '[data-testid="attention"]')!, 'a')).toHaveLength(2);
  });

  it('offers "New workflow" only to someone who may manage workflows', async () => {
    setup(READ);
    let view = render(WorkflowsPage);
    await settle(view.fixture);
    expect(q(view.el, '[data-testid="new-workflow"]')).toBeNull();
    TestBed.resetTestingModule();
    setup(ALL);
    view = render(WorkflowsPage);
    await settle(view.fixture);
    expect(q(view.el, '[data-testid="new-workflow"]')).not.toBeNull();
  });

  it('searches and filters the workflow list by mode, with distinct empty states', async () => {
    setup(READ);
    const { fixture, el } = render(WorkflowsPage);
    await settle(fixture);
    byText(el, '[role="tab"]', 'Workflows 2')!.click();
    await settle(fixture);
    expect(qa(el, '[data-testid^="definition-"]')).toHaveLength(2);
    byText(el, '[role="radio"]', 'Coordinated')!.click();
    await settle(fixture);
    const cards = qa(el, '[data-testid^="definition-"]');
    expect(cards).toHaveLength(1);
    expect(text(cards[0])).toContain('Coordinated');
    expect(text(cards[0])).toContain('Coordinator');
    byText(el, '[role="radio"]', 'All')!.click();
    typeInto(q(el, 'input[type="search"]') as HTMLInputElement, 'zzz-nothing');
    await settle(fixture);
    expect(q(el, '[data-testid="no-match"]')).not.toBeNull();
    typeInto(q(el, 'input[type="search"]') as HTMLInputElement, 'saas');
    await settle(fixture);
    expect(qa(el, '[data-testid^="definition-"]')).toHaveLength(1);
    const card = text(qa(el, '[data-testid^="definition-"]')[0]);
    expect(card).toContain('Parallel ×3');
    expect(card).toMatch(/Last run\s*Succeeded/);        // the last loaded run of THIS definition
  });

  it('filters the run history by state', async () => {
    setup(READ);
    const { fixture, el } = render(WorkflowsPage);
    await settle(fixture);
    byText(el, '[role="tab"]', 'Runs 5')!.click();
    await settle(fixture);
    expect(qa(el, '[data-testid="runs-table"] tbody tr')).toHaveLength(5);
    byText(el, 'button', 'Waiting for recovery')!.click();
    await settle(fixture);
    expect(qa(el, '[data-testid="runs-table"] tbody tr')).toHaveLength(1);
    byText(el, 'button', 'Failed')!.click();
    await settle(fixture);
    expect(text(q(el, '[data-testid="runs-table"]')!)).toContain('Failed');
    byText(el, 'button', 'Completed')!.click();
    await settle(fixture);
    expect(qa(el, '[data-testid="runs-table"] tbody tr')).toHaveLength(1);
  });

  it('shows the error of a failed load instead of an empty page', async () => {
    const { desktop } = setup(READ);
    desktop.handler = async () => ({ status: 503, error: 'network' } as ApiResponse);
    const { fixture, el } = render(WorkflowsPage);
    await settle(fixture);
    expect(q(el, '[data-testid="load-error"]')).not.toBeNull();
  });

  it('keeps organizations apart: switching organization reloads and never shows the previous one', async () => {
    const { organizationId, desktop } = setup(READ);
    const { fixture } = render(WorkflowsPage);
    await settle(fixture);
    const before = desktop.requests.filter((r) => r.path.includes(ORG)).length;
    organizationId.set('0195f0a4-1c2d-7e3f-8a9b-00000000000b');
    await settle(fixture);
    expect(desktop.requests.some((r) => r.path.includes('0195f0a4-1c2d-7e3f-8a9b-00000000000b/workflows'))).toBe(true);
    expect(before).toBeGreaterThan(0);
  });
});

// ---------------------------------------------------------------------------------------------------- run page

describe('Workflow run page', () => {
  const runId = (scenario: string) => SCENARIOS[scenario].run.id;

  async function open(scenario: string, permissions: PermissionCode[] = ALL, live: Record<string, string> = {}) {
    const ctx = setup(permissions);
    ctx.core.scenario = scenario;
    ctx.core.liveStatus = live;
    const view = render(WorkflowRunPage, { runId: runId(scenario) });
    await settle(view.fixture);
    return { ...ctx, ...view };
  }

  it('shows a parallel group as side-by-side workers between a fan-out and a fan-in, with queued work waiting for capacity', async () => {
    const execs = SCENARIOS['running'].steps.filter((s) => s.executionId).map((s) => s.executionId as string);
    const { el } = await open('running', ALL, { [execs[0]]: 'RUNNING', [execs[1]]: 'PENDING' });
    expect(q(el, '[data-testid="column-group-1"]')!.parentElement!.children).toHaveLength(1);   // nothing follows yet: no fan-in drawn
    const column = q(el, '[data-testid="column-group-1"]')!;
    expect(column.getAttribute('data-parallel')).toBe('true');
    expect(text(q(column, '[data-testid="parallel-label"]')!)).toBe('3 workers in parallel');
    const cards = qa(column, 'app-worker-card button');
    expect(cards).toHaveLength(3);
    // The three states are never confused: confirmed running, dispatched (starting), queued.
    expect(cards.map((c) => c.getAttribute('data-phase'))).toEqual(['running', 'dispatched', 'queued']);
    expect(text(cards[2])).toContain('Waiting for capacity');
    expect(text(cards[2])).not.toContain('Running');
    expect(text(cards[1])).toContain('Dispatched');
  });

  it('draws the fan-in: the stage after a parallel group is a separate column that starts when the group is resolved', async () => {
    const { el } = await open('succeeded');
    expect(qa(el, '[data-testid^="column-"]')).toHaveLength(2);
    expect(q(el, '[data-testid="column-group-1"]')!.getAttribute('data-parallel')).toBe('true');
    expect(q(el, '[data-testid="column-group-2"]')!.getAttribute('data-parallel')).toBe('false');
    expect(text(q(el, '[data-testid="column-group-1"]')!)).toContain('Stage 1');
    expect(text(q(el, '[data-testid="column-group-2"]')!)).toContain('Stage 2');
    expect(text(el)).toContain('After a parallel group, the next stage starts only when every worker of the group has a resolved outcome');
    expect(qa(el, '.link__text').map((n) => text(n))).toEqual([]);          // the single synthesis column is not a fan-out
  });

  it('says why the run is waiting, from the recorded states only', async () => {
    const execs = SCENARIOS['queued'].steps.filter((s) => s.executionId).map((s) => s.executionId as string);
    const { el } = await open('queued', ALL, { [execs[0]]: 'RUNNING', [execs[1]]: 'PENDING' });
    const why = text(q(el, '[data-testid="why-waiting"]')!);
    expect(why).toContain('1 worker(s) running');
    expect(why).toContain('1 worker(s) dispatched, not confirmed running yet');
    expect(why).toContain('1 worker(s) waiting for capacity');
  });

  it('shows the token budget with its reservation, and never converts tokens to money', async () => {
    const { el } = await open('queued');
    const budget = text(q(el, '[data-testid="budget"]')!);
    expect(budget).toContain('Reserved by running workers');
    expect(budget).toContain('Remaining');
    expect(text(el)).not.toMatch(/[€$£]/);
  });

  it('keeps every attempt of a retried worker, with the failed one never shown as a result', async () => {
    const { fixture, el } = await open('succeeded');
    q(el, '[data-testid="worker-2"]')!.click();
    await settle(fixture);
    const rows = qa(el, '[data-testid="attempts-table"] tbody tr');
    expect(rows).toHaveLength(2);
    expect(text(rows[0])).toContain('Failed');
    expect(text(rows[0])).toContain('Temporary provider failure');
    expect(text(rows[1])).toContain('Automatic retry');
    expect(text(rows[1])).toContain('Succeeded');
    const detail = text(q(el, 'app-worker-detail')!);
    expect(detail).toContain('PRICING: freemium wins in SMB.');
    expect(detail).toContain('untrusted');
    // Worker 2 failed once but its tokens still count: the table shows usage for the failed attempt as well.
    expect(text(rows[0])).toMatch(/40/);
  });

  it('refuses to present an unknown external outcome as a plain failure, a cancellation or something retryable', async () => {
    const { fixture, el } = await open('unknown');
    expect(q(el, '[data-testid="unknown-outcome"]')).not.toBeNull();
    expect(text(q(el, '[data-testid="unknown-outcome"]')!)).toContain('not retried, replayed or reported as cancelled');
    const unknown = qa(el, 'app-worker-card button').find((c) => c.getAttribute('data-phase') === 'unknown-outcome')!;
    expect(unknown).toBeDefined();
    unknown.click();
    await settle(fixture);
    expect(q(el, '[data-testid="retry-button"]')).toBeNull();
    expect(text(q(el, '[data-testid="retry-unavailable"]')!)).toContain('never replayed');
    expect(text(q(el, '[data-testid="retry-safety"]')!)).toContain('never retried');
    // The other completed workers keep their own results, separate from the group.
    expect(text(q(el, '[data-testid="results"]')!)).toContain('R1');
  });

  it('explains that manual recovery does not exist for parallel workers instead of showing a misleading button', async () => {
    // A parallel run that failed on a known, retry-safe failure: a sequential run would offer a retry here, a parallel one never does.
    const failed = SCENARIOS['succeeded'].steps.map((s) => (s.rank === 2 && s.attempt === 2
      ? { ...s, status: 'FAILED' as const, failureCategory: 'PERMANENT_PROVIDER_FAILURE' as const, retrySafe: true, resultSummary: null, errorCode: 'PROVIDER_AUTHENTICATION' } : s));
    SCENARIOS['parallelFailed'] = { ...SCENARIOS['succeeded'], run: { ...SCENARIOS['succeeded'].run, status: 'FAILED' }, steps: failed };
    const { fixture, el } = await open('parallelFailed');
    q(el, '[data-testid="worker-2"]')!.click();
    await settle(fixture);
    expect(q(el, '[data-testid="retry-button"]')).toBeNull();
    expect(text(q(el, '[data-testid="retry-unavailable"]')!)).toContain('not available for workflows with parallel workers');
    expect(text(q(el, '[data-testid="recovery-text"]')!)).toContain('not available for workflows with parallel workers');
    expect(text(q(el, '[data-testid="failure-detail"]')!)).toContain('Provider configuration problem');
    expect(text(q(el, '[data-testid="retry-safety"]')!)).toContain('a repeat is safe');          // the actual eligibility is shown, honestly
    delete SCENARIOS['parallelFailed'];
  });

  it('offers the retry of an eligible sequential step only, confirms it, and asks the Core API (which decides)', async () => {
    const { fixture, el, desktop } = await open('recovery');
    expect(text(q(el, '[data-testid="why-waiting"]')!)).toContain('held for a person');
    q(el, '[data-testid="worker-1"]')!.click();
    await settle(fixture);
    const retry = q(el, '[data-testid="retry-button"]') as HTMLButtonElement;
    expect(retry).not.toBeNull();
    const confirm = TestBed.inject(ConfirmService);
    const ask = vi.spyOn(confirm, 'ask').mockResolvedValue({ confirmed: true, note: null });
    retry.click();
    await settle(fixture);
    expect(ask).toHaveBeenCalledOnce();
    expect(ask.mock.calls[0][0].message).toContain('The Core API checks again');
    const call = desktop.requests.find((r) => r.method === 'POST' && r.path.endsWith('/steps/1/retry'));
    expect(call).toBeDefined();
  });

  it('does not retry when the person declines the confirmation, and hides the button without the permissions', async () => {
    const first = await open('recovery');
    q(first.el, '[data-testid="worker-1"]')!.click();
    await settle(first.fixture);
    vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockResolvedValue({ confirmed: false, note: null });
    (q(first.el, '[data-testid="retry-button"]') as HTMLButtonElement).click();
    await settle(first.fixture);
    expect(first.desktop.requests.some((r) => r.path.endsWith('/retry'))).toBe(false);
    TestBed.resetTestingModule();
    const denied = await open('recovery', READ);
    q(denied.el, '[data-testid="worker-1"]')!.click();
    await settle(denied.fixture);
    expect(q(denied.el, '[data-testid="retry-button"]')).toBeNull();
    expect(text(q(denied.el, '[data-testid="retry-unavailable"]')!)).toContain('permissions to execute');
  });

  it('explains the consequences before cancelling, then only ASKS the Core API; the button needs workflow.execute', async () => {
    const { fixture, el, desktop } = await open('running');
    const ask = vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockResolvedValue({ confirmed: true, note: null });
    (q(el, '[data-testid="cancel-run"]') as HTMLButtonElement).click();
    await settle(fixture);
    const message = ask.mock.calls[0][0].message;
    expect(message).toContain('queued worker(s) will be cancelled before they start');
    expect(message).toContain('UNKNOWN, never as cancelled or undone');
    expect(message).toContain('not rolled back');
    expect(desktop.requests.some((r) => r.method === 'POST' && r.path.endsWith('/cancel'))).toBe(true);
    TestBed.resetTestingModule();
    const readOnly = await open('running', READ);
    expect(q(readOnly.el, '[data-testid="cancel-run"]')).toBeNull();
  });

  it('shows the workers that wait for approval with an independent approval card each, linked to its own execution', async () => {
    const steps = SCENARIOS['running'].steps.map((s, i) => (i < 2 ? { ...s, status: 'WAITING_APPROVAL' as const } : s));
    SCENARIOS['approval'] = { ...SCENARIOS['running'], steps };
    const approval = (execution: string, n: number) => ({ ...(REAL_API['approvalPending'] as object), id: `0195f0a4-1c2d-7e3f-8a9b-0000000002${n}`, executionId: execution });
    const ctx = setup(ALL);
    ctx.core.scenario = 'approval';
    ctx.core.liveStatus = {};
    ctx.core.approvals = [approval(steps[0].executionId as string, 1), approval(steps[1].executionId as string, 2), approval('0195f0a4-1c2d-7e3f-8a9b-0000000000ff', 3)];
    const { fixture, el } = render(WorkflowRunPage, { runId: SCENARIOS['approval'].run.id });
    await settle(fixture);
    const cards = qa(el, 'app-approval-card');
    expect(cards).toHaveLength(2);                          // the third approval belongs to another execution: not shown here
    expect(text(q(el, '[data-testid="why-waiting"]')!)).toContain('2 worker(s) wait for a person');
    expect(text(el)).toContain('authorizes nothing else');
    expect(q(el, '[data-testid="worker-1"]')!.getAttribute('data-phase')).toBe('waiting-approval');
    delete SCENARIOS['approval'];
  });

  it('says so, instead of hiding it, when approvals wait but the role cannot read them', async () => {
    const steps = SCENARIOS['running'].steps.map((s, i) => (i === 0 ? { ...s, status: 'WAITING_APPROVAL' as const } : s));
    SCENARIOS['approval2'] = { ...SCENARIOS['running'], steps };
    const ctx = setup(['workflow.read', 'organization.read']);
    ctx.core.scenario = 'approval2';
    const { fixture, el } = render(WorkflowRunPage, { runId: SCENARIOS['approval2'].run.id });
    await settle(fixture);
    expect(q(el, '[data-testid="approval-permission"]')).not.toBeNull();
    expect(qa(el, 'app-approval-card')).toHaveLength(0);
    delete SCENARIOS['approval2'];
  });

  it('lists the coordinator actions, accepted and refused, and the delegation in the timeline', async () => {
    const { el } = await open('coordinated');
    const actions = text(q(el, '[data-testid="actions"]')!);
    expect(actions).toContain('Accepted');
    expect(actions).toContain('Refused');
    expect(actions).toContain('UNKNOWN_PARTICIPANT');
    expect(text(el)).toContain('Coordinator turn 1');
    expect(text(el)).toContain('Coordinator delegated a task to');
  });

  it('shows the timeline as a reconstruction, never as a live stream, with retries and a final state', async () => {
    const { el } = await open('succeeded');
    expect(text(q(el, '[data-testid="timeline-note"]')!)).toContain('not a live stream');
    const timeline = text(q(el, 'app-run-timeline')!);
    expect(timeline).toContain('Workflow launched');
    expect(timeline).toContain('Retry scheduled');
    expect(timeline).toContain('Retry executed (attempt 2)');
    expect(timeline).toContain('Workflow succeeded');
  });

  it('shows measured run metrics, and "Not available" where nothing was measured', async () => {
    const { el } = await open('succeeded');
    const metrics = text(q(el, '[data-testid="run-metrics"]')!);
    expect(metrics).toMatch(/Retries\s*1 \(1 automatic, 0 manual\)/);
    expect(metrics).toMatch(/Approval waiting time\s*Not available/);
    expect(text(q(el, '[data-testid="failures"]')!)).toContain('Transient provider failure: 1');
  });

  it('shows the final result and each worker result separately, as plain text', async () => {
    const { el } = await open('succeeded');
    expect(text(q(el, '[data-testid="final-result"]')!)).toContain('REPORT: enter via SMB freemium.');
    const results = text(q(el, '[data-testid="results"]')!);
    expect(results).toContain('COMPETITORS: three relevant players.');
    expect(results).toContain('Business synthesis');
    expect(q(el, '[data-testid="final-result"]')!.tagName).toBe('PRE');
  });

  it('is operable by keyboard and labelled for assistive technology: native buttons, roles, live status, state not told by colour alone', async () => {
    const { fixture, el } = await open('running', ALL, {});
    const cards = qa(el, 'app-worker-card button');
    expect(cards.length).toBe(3);
    expect(cards.every((c) => c.tagName === 'BUTTON' && c.getAttribute('type') === 'button')).toBe(true);   // focusable and activated by Enter/Space natively
    expect(cards.every((c) => c.hasAttribute('aria-pressed'))).toBe(true);
    expect(q(el, 'ol[aria-label="Workflow execution stages"]')).not.toBeNull();
    expect(q(el, 'ol[aria-label="Workflow timeline"]')).not.toBeNull();
    expect(q(el, '[data-testid="live-state"]')!.getAttribute('role')).toBe('status');
    expect(q(el, '[data-testid="live-state"]')!.getAttribute('aria-live')).toBe('polite');
    // Every state is also written in words (the badge label), never only a colour.
    expect(cards.map((c) => text(c)).every((t) => /Running|Dispatched|Waiting for capacity|Completed|Failed|Needs approval|Cancelled/.test(t))).toBe(true);
    cards[0].focus();
    expect(document.activeElement).toBe(cards[0]);
    cards[0].click();
    await settle(fixture);
    expect(cards[0].getAttribute('aria-pressed')).toBe('true');
    expect(qa(el, 'button').every((b) => (b.textContent ?? '').trim() !== '' || b.hasAttribute('aria-label'))).toBe(true);   // no unnamed button
  });

  it('shows an error state when the run cannot be loaded', async () => {
    const ctx = setup(ALL);
    ctx.core.fail = new Error('down');
    const { fixture, el } = render(WorkflowRunPage, { runId: SCENARIOS['running'].run.id });
    await settle(fixture);
    expect(q(el, '[data-testid="run-error"]')).not.toBeNull();
  });
});

// ---------------------------------------------------------------------------------------------------- run page, cold start

/**
 * Regression of the "Loading… for ever" defect found in the real Electron window: after a reload or a direct URL the organization
 * details (hence the permissions) arrive ASYNCHRONOUSLY, and the page used to read `can('workflow.read')` untracked, so it never
 * started following the run. These tests use the REAL AccessStore and its real asynchronous resource (only the network is faked),
 * never a permission that is already there at construction time.
 */
describe('Workflow run page: cold start with asynchronous permissions', () => {
  const ORG_B = '0195f0a4-1c2d-7e3f-8a9b-00000000000b';
  const RUN = SCENARIOS['running'].run.id;
  const RUN_2 = '0195f0a4-1c2d-7e3f-8a9b-0000000000f2';
  const POLL = 40;
  const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

  interface Read { readonly org: string; readonly run: string; readonly at: number }

  function coldStart(options: { permissions?: Record<string, PermissionCode[]>; orgDelayMs?: number; organization?: string | null } = {}) {
    const desktop = new FakeDesktop();
    const core = new Core();
    core.scenario = 'running';
    const reads: Read[] = [];
    const orgRequests: string[] = [];
    const permissions = options.permissions ?? { [ORG]: ALL, [ORG_B]: ALL };
    desktop.handler = async (request) => {
      const orgMatch = /^\/api\/v1\/organizations\/([^/]+)$/.exec(request.path);
      if (orgMatch && request.method === 'GET') {
        orgRequests.push(orgMatch[1]);
        await sleep(options.orgDelayMs ?? 60);           // the organization details (and so the permissions) arrive LATER
        return ok({ id: orgMatch[1], name: 'Acme', myRoles: [], myPermissions: permissions[orgMatch[1]] ?? [] });
      }
      const runMatch = /^\/api\/v1\/organizations\/([^/]+)\/workflow-runs\/([^/]+)$/.exec(request.path);
      if (runMatch) reads.push({ org: runMatch[1], run: runMatch[2], at: Date.now() });
      return core.handle(request);
    };
    desktop.install();
    const organizationId = signal<string | null>(options.organization === undefined ? ORG : options.organization);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([{ path: 'workflows/runs/:runId', component: Stub }, { path: 'workflows', component: Stub }]),
        { provide: AUTH_REQUIRED, useValue: true },
        { provide: WorkspaceStore, useValue: { currentOrganizationId: organizationId } },
        { provide: RUN_POLL_MS, useValue: POLL },
        { provide: RUN_POLL_MAX_MS, useValue: 200 },
        // the REAL AccessStore: its organization details are a real asynchronous resource
      ],
    });
    return { desktop, reads, orgRequests, organizationId, access: TestBed.inject(AccessStore) };
  }

  async function until(condition: () => boolean, fixture?: ComponentFixture<unknown>, timeoutMs = 3000): Promise<void> {
    const end = Date.now() + timeoutMs;
    while (!condition() && Date.now() < end) {
      await sleep(5);
      TestBed.tick();
      fixture?.detectChanges();
    }
    expect(condition()).toBe(true);
  }

  const showsRun = (el: HTMLElement) => q(el, '[data-testid="refresh"]') !== null;

  it('starts following the run when the organization and its permissions load after the page was created (reload / direct URL)', async () => {
    const ctx = coldStart({ orgDelayMs: 120 });
    expect(ctx.access.can('workflow.read')).toBe(false);                 // the cold-start condition itself: permissions are empty
    const { fixture, el } = render(WorkflowRunPage, { runId: RUN });
    await sleep(40);
    TestBed.tick();
    fixture.detectChanges();
    expect(ctx.reads.length).toBe(0);                                    // nothing yet: not allowed to know, and nothing requested
    expect(showsRun(el)).toBe(false);
    await until(() => ctx.access.can('workflow.read'), fixture);         // the permission arrives...
    await until(() => ctx.reads.length > 0, fixture);                    // ...and the page reacts by following the run (the original defect: it did not)
    await until(() => showsRun(el), fixture);
    expect(q(el, '[data-testid="run-error"]')).toBeNull();
    expect(ctx.reads.every((r) => r.org === ORG && r.run === RUN)).toBe(true);
  });

  it('starts when the organization itself is only known later (no organization yet at construction)', async () => {
    const ctx = coldStart({ organization: null, orgDelayMs: 30 });
    const { fixture, el } = render(WorkflowRunPage, { runId: RUN });
    await sleep(60);
    expect(ctx.reads.length).toBe(0);
    ctx.organizationId.set(ORG);
    await until(() => showsRun(el), fixture);
    expect(ctx.reads.length).toBeGreaterThan(0);
  });

  it('starts at once for a normal internal navigation, when the permissions are already loaded', async () => {
    const ctx = coldStart({ orgDelayMs: 10 });
    await until(() => ctx.access.can('workflow.read'));                  // the application has been running: permissions are known
    const { fixture, el } = render(WorkflowRunPage, { runId: RUN });
    await until(() => showsRun(el), fixture, 1000);
    expect(ctx.orgRequests.length).toBe(1);                              // no new organization request was needed
  });

  it('works after a reload: a brand-new application (new injector) opens the same run URL and loads it', async () => {
    const first = coldStart({ orgDelayMs: 20 });
    await until(() => first.access.can('workflow.read'));
    const warm = render(WorkflowRunPage, { runId: RUN });
    await until(() => showsRun(warm.el), warm.fixture);
    warm.fixture.destroy();
    FakeDesktop.uninstall();
    TestBed.resetTestingModule();                                        // "reload": all in-memory state is gone
    const reloaded = coldStart({ orgDelayMs: 100 });
    const view = render(WorkflowRunPage, { runId: RUN });
    expect(reloaded.access.can('workflow.read')).toBe(false);
    await until(() => showsRun(view.el), view.fixture);
    expect(reloaded.reads.length).toBeGreaterThan(0);
  });

  it('never starts for a person without workflow.read, even after the permissions finished loading', async () => {
    const ctx = coldStart({ permissions: { [ORG]: ['organization.read', 'agent.read'] }, orgDelayMs: 40 });
    const { fixture, el } = render(WorkflowRunPage, { runId: RUN });
    await until(() => ctx.orgRequests.length > 0);
    await sleep(250);
    TestBed.tick();
    fixture.detectChanges();
    expect(ctx.access.permissions().size).toBeGreaterThan(0);            // the permissions DID load: they just do not include workflow.read
    expect(ctx.reads.length).toBe(0);
    expect(text(el)).toContain('Your role does not allow viewing workflows.');
    expect(showsRun(el)).toBe(false);
  });

  it('stops following the previous organization when the organization is switched, and shows nothing of its run', async () => {
    const ctx = coldStart({ orgDelayMs: 80 });
    const { fixture, el } = render(WorkflowRunPage, { runId: RUN });
    await until(() => showsRun(el), fixture);
    ctx.organizationId.set(ORG_B);                                       // the new organization's permissions are not loaded yet
    TestBed.tick();
    fixture.detectChanges();
    const index = ctx.reads.length;
    await sleep(30);
    expect(showsRun(el)).toBe(false);                                    // the previous organization's run is gone from the screen
    await until(() => ctx.reads.length > index + 1 && showsRun(el), fixture);   // then the run is followed in the new organization
    const later = ctx.reads.slice(index + 1);                            // (one request of the old organization may have been in flight)
    expect(later.length).toBeGreaterThan(0);
    expect(later.every((r) => r.org === ORG_B)).toBe(true);
  });

  it('follows the new run when the route parameter changes, and stops reading the previous one', async () => {
    const ctx = coldStart({ orgDelayMs: 10 });
    const { fixture, el } = render(WorkflowRunPage, { runId: RUN });
    await until(() => showsRun(el), fixture);
    fixture.componentRef.setInput('runId', RUN_2);
    TestBed.tick();
    fixture.detectChanges();
    const index = ctx.reads.length;
    await until(() => ctx.reads.length > index + 2, fixture);
    expect(ctx.reads.slice(index).every((r) => r.run === RUN_2)).toBe(true);
    expect(ctx.reads.slice(index).filter((r) => r.run === RUN).length).toBe(0);
  });

  it('keeps ONE polling loop when the permissions are updated several times', async () => {
    const ctx = coldStart({ orgDelayMs: 4 });
    const { fixture, el } = render(WorkflowRunPage, { runId: RUN });
    await until(() => showsRun(el), fixture);
    for (let i = 0; i < 6; i++) {
      ctx.access.reload();                                               // a new permission set every time: the effect runs again
      await sleep(40);
      TestBed.tick();
      fixture.detectChanges();
    }
    expect(ctx.orgRequests.length).toBeGreaterThanOrEqual(7);            // the updates really happened
    await sleep(300);
    const times = ctx.reads.map((r) => r.at);
    const gaps = times.slice(1).map((t, i) => t - times[i]);
    expect(Math.min(...gaps)).toBeGreaterThanOrEqual(POLL / 2);          // two loops would read twice in the same instant
    expect(showsRun(el)).toBe(true);
  });

  it('stops reading as soon as the page is destroyed', async () => {
    const ctx = coldStart({ orgDelayMs: 10 });
    const { fixture, el } = render(WorkflowRunPage, { runId: RUN });
    await until(() => showsRun(el), fixture);
    fixture.destroy();
    const count = ctx.reads.length;
    await sleep(250);
    expect(ctx.reads.length).toBeLessThanOrEqual(count + 1);             // at most the request already in flight
  });
});

// ---------------------------------------------------------------------------------------------------- definition editor

describe('Workflow editor', () => {
  const field = (el: HTMLElement, id: string) => q(el, `#${id}`) as HTMLInputElement;

  it('is not offered without workflow.manage, nor without agent.read', async () => {
    setup(READ.filter((p) => p !== 'agent.read'));
    let view = render(WorkflowEditor);
    await settle(view.fixture);
    expect(text(view.el)).toContain('Not available');
    TestBed.resetTestingModule();
    setup(['workflow.read', 'workflow.manage']);
    view = render(WorkflowEditor);
    await settle(view.fixture);
    expect(text(view.el)).toContain('needs permission to read agents');
  });

  it('shows what is wrong BEFORE saving and blocks the save: parallel stage without parallel workers, missing coordinator', async () => {
    setup(ALL);
    const { fixture, el } = render(WorkflowEditor);
    await settle(fixture);
    expect((q(el, '[data-testid="save"]') as HTMLButtonElement).disabled).toBe(true);     // no name, no participant, empty step
    typeInto(field(el, 'wf-name'), 'Market analysis');
    for (const box of qa(el, '[data-testid="participants"] input[type="checkbox"]').slice(0, 2)) box.click();
    await settle(fixture);
    // With parallel execution off the builder does not even offer a second step in a stage.
    expect((qa(el, '[data-testid="add-step"]')[0] as HTMLButtonElement).disabled).toBe(true);
    expect(text(qa(el, '[data-testid="add-step"]')[0])).toContain('Parallel limit reached');
    typeInto(field(el, 'wf-parallel'), '2');
    await settle(fixture);
    qa(el, '[data-testid="add-step"]')[0].click();
    await settle(fixture);
    expect(text(q(el, '[data-testid="stage-1"] [data-testid="stage-mode"]')!)).toContain('2 steps in parallel');
    // Lowering the bound afterwards makes the stage invalid: the mistake is shown before saving and the save is blocked.
    typeInto(field(el, 'wf-parallel'), '1');
    await settle(fixture);
    expect(text(q(el, '[data-testid="form-issues"]')!)).toContain('parallel execution is off');
    expect((q(el, '[data-testid="save"]') as HTMLButtonElement).disabled).toBe(true);
    typeInto(field(el, 'wf-parallel'), '2');
    await settle(fixture);
    expect(text(q(el, '[data-testid="form-issues"]')!)).not.toContain('parallel execution is off');
    byText(el, '[role="radio"]', 'Coordinated')!.click();
    await settle(fixture);
    expect(text(q(el, '[data-testid="form-issues"]')!)).toContain('Choose a coordinator among the participants');
    expect(text(q(el, '[data-testid="mode-help"]')!)).toContain('cannot add participants, change limits or bypass authorization and tool approval');
  });

  it('builds a parallel pipeline and saves it through the API in the order the Core API requires', async () => {
    const { desktop } = setup(ALL);
    const { fixture, el } = render(WorkflowEditor);
    await settle(fixture);
    const router = TestBed.inject(Router);
    typeInto(field(el, 'wf-name'), 'Market analysis');
    typeInto(field(el, 'wf-parallel'), '3');
    const boxes = qa(el, '[data-testid="participants"] input[type="checkbox"]');
    boxes.forEach((b) => b.click());
    await settle(fixture);
    // Stage 1 gets a second and third parallel step; stage 2 is the synthesis.
    qa(el, '[data-testid="add-step"]')[0].click();
    await settle(fixture);
    qa(el, '[data-testid="add-step"]')[0].click();
    await settle(fixture);
    q(el, '[data-testid="add-stage"]')!.click();
    await settle(fixture);
    const names = qa(el, 'input[id^="n-"]') as HTMLInputElement[];
    const agentSelects = qa(el, 'select[id^="a-"]') as HTMLSelectElement[];
    const tasks = qa(el, 'textarea[id^="i-"]') as HTMLTextAreaElement[];
    expect(names).toHaveLength(4);
    names.forEach((n, i) => typeInto(n, `Step ${i + 1}`));
    tasks.forEach((t, i) => typeInto(t, `Do part ${i + 1}`));
    agentSelects.forEach((s, i) => { s.value = AGENTS[i % 3].id; s.dispatchEvent(new Event('change', { bubbles: true })); });
    await settle(fixture);
    expect(text(q(el, '[data-testid="stage-1"] [data-testid="stage-mode"]')!)).toContain('3 steps in parallel');
    expect(text(q(el, '[data-testid="stage-2"] [data-testid="dependency"]')!)).toContain('Receives the results of stage 1');
    expect(q(el, '[data-testid="form-issues"]')).toBeNull();
    expect(text(q(el, '[data-testid="review"]')!)).toContain('2 stage(s), 4 step(s), with parallel stages');

    (q(el, '[data-testid="save"]') as HTMLButtonElement).click();
    await settle(fixture);
    const writes = desktop.requests.filter((r) => r.method !== 'GET' && r.path.includes('/workflows'));
    expect(writes.map((r) => `${r.method} ${r.path.split('/workflows')[1]}`)).toEqual([
      'POST ', 'PUT /0195f0a4-1c2d-7e3f-8a9b-0000000000d1/participants', 'PUT /0195f0a4-1c2d-7e3f-8a9b-0000000000d1/steps',
    ]);
    expect(writes[0].body).toMatchObject({ name: 'Market analysis', mode: 'PIPELINE', maxParallelWorkers: 3, failurePolicy: 'FAIL_FAST' });
    const steps = (writes[2].body as { steps: { stage?: number; name: string }[] }).steps;
    expect(steps.map((s) => s.stage)).toEqual([1, 1, 1, 2]);
    expect(JSON.stringify(writes)).not.toMatch(/credential|secret|password|bearer|apikey/i);
    expect(router.url).toBe('/workflows/0195f0a4-1c2d-7e3f-8a9b-0000000000d1');
  });

  it('configures a coordinated workflow: coordinator among the participants, explained, saved without steps', async () => {
    const { desktop } = setup(ALL);
    const { fixture, el } = render(WorkflowEditor);
    await settle(fixture);
    typeInto(field(el, 'wf-name'), 'Coordinated research');
    typeInto(field(el, 'wf-parallel'), '2');
    byText(el, '[role="radio"]', 'Coordinated')!.click();
    qa(el, '[data-testid="participants"] input[type="checkbox"]').forEach((b) => b.click());
    await settle(fixture);
    const select = field(el, 'wf-coordinator') as unknown as HTMLSelectElement;
    select.value = AGENTS[0].id;
    select.dispatchEvent(new Event('change', { bubbles: true }));
    await settle(fixture);
    const duties = text(q(el, '[data-testid="responsibilities"]')!);
    expect(duties).toMatch(/Market Researcher\s*— coordinator/);
    expect(duties).toMatch(/Pricing Analyst\s*— worker/);
    expect(text(el)).toContain('workers can run in parallel');
    (q(el, '[data-testid="save"]') as HTMLButtonElement).click();
    await settle(fixture);
    const writes = desktop.requests.filter((r) => r.method !== 'GET' && r.path.includes('/workflows'));
    expect(writes.map((r) => r.method)).toEqual(['POST', 'PUT']);                    // no steps for a coordinated workflow
    expect(writes[1].body).toMatchObject({ coordinatorAgentId: AGENTS[0].id });
    expect(JSON.stringify(writes)).not.toMatch(/credential|secret/i);
  });

  it('edits a saved definition as a NEW version, keeps the mode, and rebuilds the stages from the real definition', async () => {
    const def = DEFINITIONS[0];
    setup(ALL);
    const { fixture, el } = render(WorkflowEditor, { id: def.id });
    await settle(fixture);
    expect(text(q(el, '[data-testid="version-note"]')!)).toContain(`Saving creates version ${def.version + 1}`);
    expect((field(el, 'wf-name')).value).toBe(def.name);
    expect(text(el)).toContain('The mode of an existing workflow cannot be changed');
    expect(qa(el, '[data-testid^="stage-"]').length).toBeGreaterThanOrEqual(2);
    expect(text(q(el, '[data-testid="stage-1"] [data-testid="stage-mode"]')!)).toContain('3 steps in parallel');
    byText(el, '[role="radio"]', 'Coordinated')!.click();                           // ignored: the mode of a saved workflow is fixed
    await settle(fixture);
    expect(q(el, '[data-testid="responsibilities"]')).toBeNull();
  });

  it('shows the Core API refusal as it is and assumes nothing was saved', async () => {
    const { desktop } = setup(ALL);
    desktop.handler = async (request) => (request.method === 'POST' ? { status: 400, body: { detail: 'A stage may hold at most 1 parallel steps.' } } as ApiResponse : ok(page(AGENTS)));
    const { fixture, el } = render(WorkflowEditor);
    await settle(fixture);
    typeInto(field(el, 'wf-name'), 'X');
    qa(el, '[data-testid="participants"] input[type="checkbox"]')[0].click();
    await settle(fixture);
    (qa(el, 'input[id^="n-"]')[0] as HTMLInputElement).value = 'A';
    typeInto(qa(el, 'input[id^="n-"]')[0] as HTMLInputElement, 'A');
    typeInto(qa(el, 'textarea[id^="i-"]')[0] as HTMLTextAreaElement, 'x');
    const sel = qa(el, 'select[id^="a-"]')[0] as HTMLSelectElement;
    sel.value = AGENTS[0].id;
    sel.dispatchEvent(new Event('change', { bubbles: true }));
    await settle(fixture);
    (q(el, '[data-testid="save"]') as HTMLButtonElement).click();
    await settle(fixture);
    expect(q(el, '[data-testid="save-error"]')).not.toBeNull();
    expect(TestBed.inject(Router).url).not.toContain('/workflows/0195');
  });
});

// ---------------------------------------------------------------------------------------------------- definition detail

describe('Workflow detail', () => {
  const def = DEFINITIONS[0];

  it('shows the pipeline as stages, the participants and the limits, without any identifier', async () => {
    setup(READ);
    const { fixture, el } = render(WorkflowDetail, { id: def.id });
    await settle(fixture);
    expect(text(q(el, '[data-testid="name"]')!)).toBe(def.name);
    expect(text(q(el, '[data-testid="stage-1"]')!)).toContain('3 steps in parallel');
    expect(text(q(el, '[data-testid="stage-2"]')!)).toContain('after stage 1');
    expect(text(q(el, '[data-testid="limits"]')!)).toContain('Retry safe failures, then stop');
    expect(text(el)).not.toContain(def.participants[0].agentId);
    expect(q(el, '[data-testid="edit"]')).toBeNull();                 // read-only role
  });

  it('launches a run with the objective and ONE idempotency key per intent, then opens the run', async () => {
    const { desktop } = setup(ALL);
    const { fixture, el } = render(WorkflowDetail, { id: def.id });
    await settle(fixture);
    expect((q(el, '[data-testid="launch"]') as HTMLButtonElement).disabled).toBe(true);        // no objective yet
    typeInto(q(el, '#wf-objective') as HTMLTextAreaElement, 'Analyze the European SaaS market');
    await settle(fixture);
    (q(el, '[data-testid="launch"]') as HTMLButtonElement).click();
    await settle(fixture);
    const launch = desktop.requests.find((r) => r.method === 'POST' && r.path.endsWith(`/workflows/${def.id}/runs`))!;
    expect(launch.body).toMatchObject({ objective: 'Analyze the European SaaS market' });
    expect((launch.body as { requestId: string }).requestId).toMatch(/^[0-9a-f-]{36}$/);
    expect(TestBed.inject(Router).url).toBe('/workflows/runs/0195f0a4-1c2d-7e3f-8a9b-0000000000e1');
  });

  it('does not offer a launch without the execute permissions, and not for an archived workflow', async () => {
    setup(READ);
    const { fixture, el } = render(WorkflowDetail, { id: def.id });
    await settle(fixture);
    expect(q(el, '[data-testid="launch-denied"]')).not.toBeNull();
    expect(q(el, '[data-testid="launch"]')).toBeNull();
    TestBed.resetTestingModule();
    const ctx = setup(ALL);
    ctx.desktop.handler = async (r) => (r.path.endsWith(def.id) ? ok({ ...def, status: 'ARCHIVED' }) : ok(page([])));
    const view = render(WorkflowDetail, { id: def.id });
    await settle(view.fixture);
    expect(q(view.el, '[data-testid="launch-archived"]')).not.toBeNull();
    expect(q(view.el, '[data-testid="edit"]')).toBeNull();
  });

  it('archives only after a confirmation', async () => {
    const { desktop } = setup(ALL);
    const { fixture, el } = render(WorkflowDetail, { id: def.id });
    await settle(fixture);
    const ask = vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockResolvedValueOnce({ confirmed: false, note: null });
    (q(el, '[data-testid="archive"]') as HTMLButtonElement).click();
    await settle(fixture);
    expect(desktop.requests.some((r) => r.path.endsWith('/archive'))).toBe(false);
    ask.mockResolvedValueOnce({ confirmed: true, note: null });
    (q(el, '[data-testid="archive"]') as HTMLButtonElement).click();
    await settle(fixture);
    expect(desktop.requests.some((r) => r.method === 'POST' && r.path.endsWith('/archive'))).toBe(true);
  });
});
