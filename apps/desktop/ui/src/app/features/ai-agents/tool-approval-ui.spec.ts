import { ApplicationRef, provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { FakeDesktop } from '../../../testing/fake-desktop';
import { AgentsApi } from '../../core/agents/agents.api';
import { AgentDetail, AgentExecution, ApprovalStatus, ToolApproval, ToolInvocation } from '../../core/agents/agents.models';
import { AUTH_REQUIRED } from '../../core/auth/session.service';
import type { ApiRequest, ApiResponse } from '../../core/desktop/desktop-api';
import { AccessStore } from '../../core/identity/access.store';
import { PermissionCode } from '../../core/identity/identity.models';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { AgentExecutions, EXECUTION_POLL_MS } from './agent-executions';
import { ApprovalsTab } from './approvals-tab';
import { ExecutionActivity } from './execution-activity';

const ORG_A = '0195f0a4-1c2d-7e3f-8a9b-00000000000a';
const ORG_B = '0195f0a4-1c2d-7e3f-8a9b-00000000000b';
const id = (n: number) => `0195f0a4-1c2d-7e3f-8a9b-${String(n).padStart(12, '0')}`;
const NOW = '2026-01-01T00:00:00Z';
const FAR = '2999-01-01T00:00:00Z';
const HASH = 'ab12'.repeat(16);

const ALL: PermissionCode[] = [
  'organization.read', 'agent.read', 'agent.execute', 'agent.execution.read', 'skill.read', 'provider.read', 'approval.read', 'approval.decide',
];
const REVIEWER_ONLY: PermissionCode[] = ['organization.read', 'agent.read', 'agent.execution.read', 'skill.read', 'approval.read'];

const run = (n: number, status: AgentExecution['status'], extra: Partial<AgentExecution> = {}): AgentExecution => ({
  id: id(n), organizationId: ORG_A, agentId: id(1), requestedBy: id(99), task: `task ${n}`, status, providerType: 'ANTHROPIC', modelId: 'model-x',
  result: null, inputTokens: null, outputTokens: null, errorCode: null, errorMessage: null, createdAt: NOW, startedAt: NOW, completedAt: null, version: 0, ...extra,
});
const call = (n: number, executionN: number, extra: Partial<ToolInvocation> = {}): ToolInvocation => ({
  id: id(200 + n), executionId: id(executionN), agentId: id(1), toolCode: 'diagnostic.echo', toolName: 'Echo text', toolCallId: `toolu_${n}`, sequence: n,
  status: 'SUCCEEDED', policy: 'READ_ONLY', argumentsRedacted: '{"text":"hello"}', argumentsHash: HASH, reason: null, result: 'hello', resultTruncated: false,
  errorCode: null, errorMessage: null, requestedAt: '2026-01-01T00:00:02Z', startedAt: '2026-01-01T00:00:03Z', completedAt: '2026-01-01T00:00:04Z', approval: null, ...extra,
});
const approval = (n: number, executionN: number, status: ApprovalStatus = 'PENDING', extra: Partial<ToolApproval> = {}): ToolApproval => ({
  id: id(300 + n), executionId: id(executionN), executionStatus: 'WAITING_APPROVAL', agentId: id(1), agentName: 'Atlas', invocationId: id(200 + n),
  toolCode: 'diagnostic.record_note', toolName: 'Record a note', mcpServer: 'diagnostic', toolCallId: `toolu_${n}`, risk: 'MEDIUM',
  description: 'Records a short note and returns a receipt', reason: 'The task needs a note', argumentsRedacted: '{"text":"Remember the demo","password":"[REDACTED]"}',
  argumentsHash: HASH, policyVersion: 1, status, requestedAt: '2026-01-01T00:00:02Z', expiresAt: FAR, decidedBy: null, decidedAt: null, rejectionReason: null,
  invocationStatus: 'AWAITING_APPROVAL', ...extra,
});

const agentSummary = {
  id: id(1), handle: 'atlas', name: 'Atlas', avatarTone: 1, title: '', description: '', department: null, responsibilities: [], status: 'ACTIVE', configVersion: 1,
  skillCount: 0, createdAt: NOW, updatedAt: NOW, version: 0,
};
const detail = {
  agent: agentSummary, systemInstructions: null,
  providers: [{ id: id(70), connection: { id: id(71), providerType: 'ANTHROPIC', displayName: 'Key', status: 'VALID' }, modelId: 'model-x', role: 'PRIMARY', priority: 0, config: '{}' }],
  skills: [], tools: [], memory: { enabled: false, scope: 'PROJECT', accessMode: 'AGENT_ONLY', retentionDays: null, updatedAt: null }, teams: [],
} as unknown as AgentDetail;
const CATALOG = [{ code: 'diagnostic.record_note', name: 'Record a note', description: 'Records a short note', category: 'Diagnostics', risk: 'MEDIUM', maxLevel: 'WRITE', mcpServer: 'diagnostic', mcpTool: 'record_note', enabled: true }];

const ok = (body: unknown): ApiResponse => ({ status: 200, body });
const problem = (status: number, detail: string): ApiResponse => ({ status, body: { title: 'Problem', status, detail } });
const page = (content: unknown[], extra: object = {}) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1, ...extra });

interface OrgData {
  runs: AgentExecution[];
  calls: ToolInvocation[];
  approvals: ToolApproval[];
}

/** A strict fake of the Core API: every route the UI may use has the REAL response shape; anything else is an explicit 404. */
function setup(permissions: PermissionCode[], initial: Partial<Record<string, OrgData>> = {}) {
  const desktop = new FakeDesktop();
  const data: Record<string, OrgData> = { [ORG_A]: { runs: [], calls: [], approvals: [] }, [ORG_B]: { runs: [], calls: [], approvals: [] } };
  for (const [org, value] of Object.entries(initial)) {
    data[org] = value as OrgData;
  }
  const unexpected: string[] = [];
  const hooks = {
    decide: (_org: string, _approvalId: string, _kind: 'approve' | 'reject', _body: unknown): ApiResponse | Promise<ApiResponse> => problem(500, 'not configured'),
    toolCalls: undefined as undefined | ((org: string, executionId: string) => ApiResponse | Promise<ApiResponse>),
    runRead: undefined as undefined | ((org: string, executionId: string) => AgentExecution | undefined),
  };
  desktop.handler = (request: ApiRequest) => {
    const match = /^\/api\/v1\/organizations\/([^/]+)(\/.*)$/.exec(request.path);
    const org = match?.[1] ?? '';
    const rest = match?.[2] ?? '';
    const world = data[org];
    const query = (request.query ?? {}) as Record<string, string | number>;
    if (world) {
      if (request.method === 'GET' && rest === '/agents') return ok(page([agentSummary]));
      if (request.method === 'GET' && rest === '/providers/connections') return ok(page([]));
      if (request.method === 'GET' && rest === '/skills') return ok(page([]));
      if (request.method === 'GET' && rest === '/tools') return ok(CATALOG);
      if (request.method === 'GET' && /^\/agents\/[^/]+\/executions$/.test(rest)) return ok(page(world.runs));
      const toolCalls = /^\/executions\/([^/]+)\/tool-calls$/.exec(rest);
      if (request.method === 'GET' && toolCalls) {
        return hooks.toolCalls ? hooks.toolCalls(org, toolCalls[1]!) : ok(page(world.calls.filter((c) => c.executionId === toolCalls[1])));
      }
      const exec = /^\/executions\/([^/]+)$/.exec(rest);
      if (request.method === 'GET' && exec) {
        const found = hooks.runRead ? hooks.runRead(org, exec[1]!) : world.runs.find((r) => r.id === exec[1]);
        return found ? ok(found) : problem(404, 'Execution not found');
      }
      if (request.method === 'GET' && rest === '/approvals') {
        let list = world.approvals;
        if (query['status']) list = list.filter((a) => a.status === query['status']);
        if (query['executionId']) list = list.filter((a) => a.executionId === query['executionId']);
        const size = Number(query['size'] ?? 20);
        const from = Number(query['page'] ?? 0) * size;
        return ok(page(list.slice(from, from + size), { page: Number(query['page'] ?? 0), size, totalPages: Math.max(1, Math.ceil(list.length / size)), totalElements: list.length }));
      }
      const one = /^\/approvals\/([^/]+)$/.exec(rest);
      if (request.method === 'GET' && one) {
        const found = world.approvals.find((a) => a.id === one[1]);
        return found ? ok(found) : problem(404, 'Approval not found');
      }
      const decision = /^\/approvals\/([^/]+)\/(approve|reject)$/.exec(rest);
      if (request.method === 'POST' && decision) return hooks.decide(org, decision[1]!, decision[2] as 'approve' | 'reject', request.body);
    }
    unexpected.push(`${request.method} ${request.path}`);
    return problem(404, `Unexpected route ${request.method} ${request.path}`);
  };
  desktop.install();
  const organizationId = signal<string | null>(ORG_A);
  const granted = signal<ReadonlySet<PermissionCode>>(new Set(permissions));
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      { provide: EXECUTION_POLL_MS, useValue: 15 },
      { provide: AUTH_REQUIRED, useValue: true },
      { provide: WorkspaceStore, useValue: { currentOrganizationId: organizationId } },
      { provide: AccessStore, useValue: { can: (p: PermissionCode) => granted().has(p), organization: signal({ name: 'Acme' }), reload: () => undefined } },
    ],
  });
  return { desktop, data, organizationId, granted, hooks, unexpected };
}

async function settle(fixture?: ComponentFixture<unknown>, rounds = 12): Promise<void> {
  const appRef = TestBed.inject(ApplicationRef);
  for (let i = 0; i < rounds; i++) {
    await new Promise((resolve) => setTimeout(resolve, 5));
    TestBed.tick();
    fixture?.detectChanges();
    await appRef.whenStable();
  }
}

/** Lets timers and effects run WITHOUT waiting for the app to be stable (a gated resource request keeps it unstable on purpose). */
async function spin(fixture: ComponentFixture<unknown>, rounds = 6): Promise<void> {
  for (let i = 0; i < rounds; i++) {
    await new Promise((resolve) => setTimeout(resolve, 5));
    TestBed.tick();
    fixture.detectChanges();
  }
}

async function until(fixture: ComponentFixture<unknown>, condition: () => boolean, rounds = 40): Promise<void> {
  for (let i = 0; i < rounds && !condition(); i++) {
    await settle(fixture, 2);
  }
}

function activity(current: AgentExecution) {
  const fixture = TestBed.createComponent(ExecutionActivity);
  fixture.componentRef.setInput('run', current);
  const changed = vi.fn();
  fixture.componentInstance.changed.subscribe(changed);
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement, changed };
}

const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ');
const q = (el: HTMLElement, testId: string) => el.querySelector(`[data-testid="${testId}"]`) as HTMLElement | null;
const click = (el: HTMLElement | null) => (el as HTMLButtonElement).click();
const titles = (el: HTMLElement) => Array.from(el.querySelectorAll('[data-testid=timeline-entry] .title')).map((e) => e.textContent?.trim());

afterEach(() => FakeDesktop.uninstall());

describe('AgentsApi tool and approval endpoints', () => {
  it('builds organization-scoped requests and refuses a forged identifier', async () => {
    const { desktop } = setup(ALL);
    desktop.handler = () => ok(page([]));
    const api = TestBed.inject(AgentsApi);
    await api.executionToolCalls(ORG_A, id(5));
    await api.listApprovals(ORG_A, { status: 'PENDING', executionId: id(5), page: 1 });
    await api.approve(ORG_A, id(300), HASH);
    await api.reject(ORG_A, id(300), '  not now  ');
    await api.reject(ORG_A, id(300));
    expect(desktop.requests).toEqual([
      { method: 'GET', path: `/api/v1/organizations/${ORG_A}/executions/${id(5)}/tool-calls`, query: { page: 0, size: 50 } },
      { method: 'GET', path: `/api/v1/organizations/${ORG_A}/approvals`, query: { size: 20, status: 'PENDING', executionId: id(5), page: 1 } },
      { method: 'POST', path: `/api/v1/organizations/${ORG_A}/approvals/${id(300)}/approve`, body: { argumentsHash: HASH } },
      { method: 'POST', path: `/api/v1/organizations/${ORG_A}/approvals/${id(300)}/reject`, body: { reason: 'not now' } },
      { method: 'POST', path: `/api/v1/organizations/${ORG_A}/approvals/${id(300)}/reject`, body: {} },
    ]);
    expect(() => api.approve(ORG_A, '../internal', HASH)).toThrow();
    expect(() => api.listApprovals(ORG_A, { executionId: 'x/../y' })).toThrow();
  });
});

describe('Execution timeline and tool activity', () => {
  it('shows an empty timeline for a run without tool calls', async () => {
    const { unexpected } = setup(ALL);
    const { fixture, el } = activity(run(10, 'SUCCEEDED', { completedAt: '2026-01-01T00:00:09Z', result: 'done' }));
    await settle(fixture);
    expect(titles(el)).toEqual(['Execution created', 'Execution started', 'Execution completed']);
    expect(el.querySelector('app-tool-activity-card')).toBeNull();
    expect(unexpected).toEqual([]);
  });

  it('lists several events in chronological order with their tool activity', async () => {
    const { data, unexpected } = setup(ALL);
    data[ORG_A]!.calls = [
      call(2, 10, { requestedAt: '2026-01-01T00:00:06Z', startedAt: '2026-01-01T00:00:07Z', completedAt: '2026-01-01T00:00:08Z' }),
      call(1, 10),
    ];
    const { fixture, el } = activity(run(10, 'SUCCEEDED', { completedAt: '2026-01-01T00:00:09Z' }));
    await settle(fixture);
    expect(titles(el)).toEqual([
      'Execution created', 'Execution started', 'Tool requested: Echo text', 'Tool started: Echo text', 'Tool completed: Echo text',
      'Tool requested: Echo text', 'Tool started: Echo text', 'Tool completed: Echo text', 'Execution completed',
    ]);
    expect(el.querySelectorAll('app-tool-activity-card').length).toBe(2);
    expect(unexpected).toEqual([]);
  });

  it('shows a loading state, then the activity', async () => {
    const { hooks, data } = setup(ALL);
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    data[ORG_A]!.calls = [call(1, 10)];
    hooks.toolCalls = async (_org, executionId) => {
      await gate;
      return ok(page(data[ORG_A]!.calls.filter((c) => c.executionId === executionId)));
    };
    const { fixture, el } = activity(run(10, 'RUNNING'));
    await spin(fixture, 4);
    expect(el.querySelector('ui-skeleton')).not.toBeNull();
    expect(el.querySelector('app-tool-activity-card')).toBeNull();
    release();
    await until(fixture, () => el.querySelector('app-tool-activity-card') !== null);
    expect(el.querySelector('ui-skeleton')).toBeNull();
  });

  it('reports an API failure without breaking the rest of the panel', async () => {
    const { hooks } = setup(ALL);
    hooks.toolCalls = () => problem(500, 'Tool activity could not be read.');
    const { fixture, el } = activity(run(10, 'RUNNING'));
    await settle(fixture);
    expect(text(el)).toContain('Tool activity unavailable');
    expect(q(el, 'timeline-empty')).toBeNull();
  });

  it('hides tool activity from users who may not read executions', async () => {
    const { desktop } = setup(['organization.read', 'agent.read']);
    const { fixture, el } = activity(run(10, 'RUNNING'));
    await settle(fixture);
    expect(el.querySelector('app-execution-timeline')).toBeNull();
    expect(desktop.requests.some((r) => r.path.includes('/tool-calls'))).toBe(false);
  });

  it('shows successful and failed calls, expandable, as text', async () => {
    const { data } = setup(ALL);
    data[ORG_A]!.calls = [
      call(1, 10, { result: '<img src=x onerror=alert(1)> done' }),
      call(2, 10, { status: 'FAILED', result: null, errorCode: 'TOOL_TIMEOUT', errorMessage: 'The tool did not finish in time.' }),
    ];
    const { fixture, el } = activity(run(10, 'RUNNING'));
    await settle(fixture);
    const heads = Array.from(el.querySelectorAll('app-tool-activity-card button.head')) as HTMLButtonElement[];
    expect(heads[0]!.getAttribute('aria-expanded')).toBe('false');
    expect(el.querySelector('[data-testid=tool-output]')).toBeNull();
    heads.forEach((h) => h.click());
    await settle(fixture, 3);
    expect(heads[0]!.getAttribute('aria-expanded')).toBe('true');
    expect(text(el)).toContain('Succeeded');
    expect(text(el)).toContain('Failed');
    expect(text(q(el, 'tool-error'))).toContain('did not finish in time');
    const output = q(el, 'tool-output')!;
    expect(output.textContent).toContain('<img src=x onerror=alert(1)>');     // rendered as text...
    expect(el.querySelector('img')).toBeNull();                               // ...never as markup
  });

  it('shows the catalog details (server, risk) when the user can read the catalog', async () => {
    const { data } = setup(ALL);
    data[ORG_A]!.calls = [call(1, 10, { toolCode: 'diagnostic.record_note', toolName: 'Record a note' })];
    const { fixture, el } = activity(run(10, 'RUNNING'));
    await settle(fixture);
    click(el.querySelector('app-tool-activity-card button.head') as HTMLElement);
    await settle(fixture, 3);
    const details = text(el.querySelector('app-tool-activity-card dl'));
    expect(details).toContain('MCP server');
    expect(details).toContain('diagnostic');
    expect(details).toContain('MEDIUM');
    expect(details).toContain('Records a short note');
  });

  it('limits an oversized result and flags the platform truncation', async () => {
    const { data } = setup(ALL);
    data[ORG_A]!.calls = [call(1, 10, { result: 'R'.repeat(50_000), resultTruncated: true })];
    const { fixture, el } = activity(run(10, 'RUNNING'));
    await settle(fixture);
    click(el.querySelector('app-tool-activity-card button.head') as HTMLElement);
    await settle(fixture, 3);
    expect(q(el, 'tool-output')!.textContent!.length).toBeLessThanOrEqual(4_000);
    expect(q(el, 'tool-output-clipped')).not.toBeNull();
    expect(q(el, 'tool-output-truncated')).not.toBeNull();
  });

  it('masks anything that looks like a credential, even if it reached the response', async () => {
    const { data } = setup(ALL);
    data[ORG_A]!.calls = [call(1, 10, {
      argumentsRedacted: '{"note":"x","api_key":"sk-ant-api03-LEAKLEAKLEAK","auth":"Bearer abcdefghijklmnopqrstuv"}',
      result: 'token=super-secret-value and sk-ant-api03-LEAKLEAKLEAK', reason: 'password: hunter2-value',
    })];
    const { fixture, el } = activity(run(10, 'RUNNING'));
    await settle(fixture);
    click(el.querySelector('app-tool-activity-card button.head') as HTMLElement);
    await settle(fixture, 3);
    const shown = text(el);
    for (const leak of ['LEAKLEAK', 'abcdefghijklmnopqrstuv', 'super-secret-value', 'hunter2']) {
      expect(shown).not.toContain(leak);
    }
    expect(shown).toContain('REDACTED');
  });
});

describe('Approval card', () => {
  function waiting(extra: Partial<ToolApproval> = {}, permissions: PermissionCode[] = ALL) {
    const world = setup(permissions);
    world.data[ORG_A]!.runs = [run(10, 'WAITING_APPROVAL')];
    world.data[ORG_A]!.approvals = [approval(1, 10, 'PENDING', extra)];
    world.data[ORG_A]!.calls = [call(1, 10, { status: 'AWAITING_APPROVAL', policy: 'APPROVAL_REQUIRED', startedAt: null, completedAt: null, result: null, approval: null })];
    return world;
  }

  it('shows what exactly would be authorized, with sensitive values hidden', async () => {
    const { unexpected } = waiting();
    const { fixture, el } = activity(run(10, 'WAITING_APPROVAL'));
    await settle(fixture);
    const card = q(el, `approval-${id(301)}`)!;
    expect(card).not.toBeNull();
    const shown = text(card);
    for (const expected of ['Atlas', 'Record a note', 'diagnostic', 'MEDIUM', id(10), 'Records a short note', 'Remember the demo', 'Pending decision', 'The task needs a note']) {
      expect(shown).toContain(expected);
    }
    expect(shown).toContain('[REDACTED]');
    expect(shown).not.toContain('hunter2');
    expect(text(q(el, 'approval-integrity'))).toContain('SHA-256 ab12ab12…12ab12');
    expect(card.querySelector('ui-avatar')).not.toBeNull();
    expect(unexpected).toEqual([]);
  });

  it('approves only after a deliberate confirmation, sends the reviewed hash, and shows nothing before the server confirms', async () => {
    const { hooks, data, desktop } = waiting();
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    hooks.decide = async () => {
      await gate;
      data[ORG_A]!.approvals = [approval(1, 10, 'APPROVED', { decidedBy: id(99), decidedAt: '2026-01-01T00:01:00Z', invocationStatus: 'APPROVED' })];
      return ok(data[ORG_A]!.approvals[0]);
    };
    const { fixture, el, changed } = activity(run(10, 'WAITING_APPROVAL'));
    await settle(fixture);
    click(q(el, 'approve-start'));
    await settle(fixture, 3);
    const confirm = q(el, 'approve-confirm') as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);                                   // not without the explicit review
    (q(el, 'approve-reviewed') as HTMLInputElement).click();
    await settle(fixture, 3);
    expect(confirm.disabled).toBe(false);
    confirm.click();
    await settle(fixture, 4);
    // Not optimistic: still pending and busy while the Core API has not answered.
    expect(text(q(el, `approval-${id(301)}`))).toContain('Pending decision');
    expect(confirm.textContent).toContain('Recording');
    expect(confirm.disabled).toBe(true);
    release();
    await until(fixture, () => text(q(el, 'approval-status')).includes('Approved'));
    expect(text(q(el, 'approval-outcome'))).toContain('Approved');
    expect(changed).toHaveBeenCalledTimes(1);
    const posts = desktop.requests.filter((r) => r.method === 'POST');
    expect(posts).toEqual([{ method: 'POST', path: `/api/v1/organizations/${ORG_A}/approvals/${id(301)}/approve`, body: { argumentsHash: HASH } }]);
    expect(q(el, 'approve-start')).toBeNull();                              // decided: no second decision offered
  });

  it('rejects with an optional reason and shows the rejected state', async () => {
    const { hooks, desktop } = waiting();
    hooks.decide = (_o, _a, _k, body) => ok(approval(1, 10, 'REJECTED', { decidedBy: id(99), decidedAt: '2026-01-01T00:01:00Z', rejectionReason: (body as { reason?: string }).reason ?? null, invocationStatus: 'REJECTED' }));
    const { fixture, el } = activity(run(10, 'WAITING_APPROVAL'));
    await settle(fixture);
    click(q(el, 'reject-start'));
    await settle(fixture, 3);
    const area = el.querySelector('textarea') as HTMLTextAreaElement;
    area.value = 'Not now';
    area.dispatchEvent(new Event('input', { bubbles: true }));
    click(q(el, 'reject-confirm'));
    await until(fixture, () => text(q(el, 'approval-status')).includes('Rejected'));
    expect(text(q(el, 'approval-outcome'))).toContain('Reason: Not now');
    expect(text(q(el, 'approval-outcome'))).toContain('not executed');
    expect(desktop.requests.filter((r) => r.method === 'POST')[0]!.body).toEqual({ reason: 'Not now' });
  });

  it('does not offer a decision on an approval whose deadline has passed', async () => {
    waiting({ expiresAt: '2020-01-01T00:00:00Z' });
    const past = activity(run(10, 'WAITING_APPROVAL'));
    await settle(past.fixture);
    expect(q(past.el, 'approve-start')).toBeNull();
    expect(text(q(past.el, 'approval-readonly'))).toContain('expired');
    past.fixture.destroy();
  });

  it('closes the card when the deadline passes while it is on screen', async () => {
    const world = waiting({ expiresAt: new Date(Date.now() + 1500).toISOString() });
    expect(world.unexpected).toEqual([]);
    const { fixture, el } = activity(run(10, 'WAITING_APPROVAL'));
    await settle(fixture);
    expect(q(el, 'approve-start')).not.toBeNull();
    await new Promise((resolve) => setTimeout(resolve, 1700));
    await settle(fixture, 3);
    expect(q(el, 'approve-start')).toBeNull();
    expect(text(q(el, 'approval-readonly'))).toContain('expired');
  });

  it('shows the real state when someone else decided first (duplicate / concurrent decision)', async () => {
    const { hooks, data } = waiting();
    hooks.decide = () => {
      data[ORG_A]!.approvals = [approval(1, 10, 'APPROVED', { decidedBy: id(98), decidedAt: '2026-01-01T00:00:30Z', invocationStatus: 'APPROVED' })];
      return problem(409, 'This approval request is already APPROVED.');
    };
    const { fixture, el, changed } = activity(run(10, 'WAITING_APPROVAL'));
    await settle(fixture);
    click(q(el, 'reject-start'));
    await settle(fixture, 3);
    click(q(el, 'reject-confirm'));
    await until(fixture, () => text(q(el, 'approval-status')).includes('Approved'));
    expect(text(q(el, 'approval-status'))).toContain('Approved');
    expect(q(el, 'approve-start')).toBeNull();
    expect(changed).toHaveBeenCalled();                                    // the owner refreshes the run too
  });

  it('shows a backend authorization failure and keeps the request pending', async () => {
    const { hooks, desktop } = waiting();
    hooks.decide = () => problem(403, 'Forbidden');
    const { fixture, el } = activity(run(10, 'WAITING_APPROVAL'));
    await settle(fixture);
    click(q(el, 'approve-start'));
    await settle(fixture, 3);
    (q(el, 'approve-reviewed') as HTMLInputElement).click();
    await settle(fixture, 2);
    click(q(el, 'approve-confirm'));
    await until(fixture, () => q(el, 'approval-error') !== null);
    expect(text(q(el, 'approval-error'))).toContain('not allowed to decide approvals');
    expect(text(q(el, 'approval-status'))).toContain('Pending decision');
    expect(desktop.requests.some((r) => r.method === 'GET' && r.path.endsWith(`/approvals/${id(301)}`))).toBe(false);
  });

  it('explains an approval that is no longer available', async () => {
    const { hooks, data } = waiting();
    hooks.decide = () => {
      data[ORG_A]!.approvals = [];
      return problem(404, 'approval not found');
    };
    const { fixture, el } = activity(run(10, 'WAITING_APPROVAL'));
    await settle(fixture);
    click(q(el, 'reject-start'));
    await settle(fixture, 3);
    click(q(el, 'reject-confirm'));
    await until(fixture, () => q(el, 'approval-error') !== null);
    expect(text(q(el, 'approval-error'))).toContain('no longer available');
  });

  it('is read-only for a reviewer who may not decide, and absent for someone who may not even read', async () => {
    waiting({}, REVIEWER_ONLY);
    const reviewer = activity(run(10, 'WAITING_APPROVAL'));
    await settle(reviewer.fixture);
    expect(q(reviewer.el, `approval-${id(301)}`)).not.toBeNull();
    expect(q(reviewer.el, 'approve-start')).toBeNull();
    expect(text(q(reviewer.el, 'approval-readonly'))).toContain('does not allow deciding');
    reviewer.fixture.destroy();
  });

  it('tells a user without approval rights that someone else must decide', async () => {
    const { desktop } = waiting({}, ['organization.read', 'agent.read', 'agent.execution.read']);
    const { fixture, el } = activity(run(10, 'WAITING_APPROVAL'));
    await settle(fixture);
    expect(text(q(el, 'decision-no-access'))).toContain('Ask a person');
    expect(el.querySelector('app-approval-card')).toBeNull();
    expect(desktop.requests.some((r) => r.path.endsWith('/approvals'))).toBe(false);
  });

  it('does not keep showing a decision once the run is terminal', async () => {
    const { data } = waiting();
    const { fixture, el } = activity(run(10, 'SUCCEEDED', { completedAt: '2026-01-01T00:02:00Z' }));
    data[ORG_A]!.approvals = [approval(1, 10, 'APPROVED')];
    await settle(fixture);
    expect(q(el, 'decision-needed')).toBeNull();
    expect(el.querySelector('app-approval-card')).toBeNull();
  });
});

describe('Recovery in the execution panel', () => {
  function panel() {
    const fixture = TestBed.createComponent(AgentExecutions);
    fixture.componentRef.setInput('detail', detail);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('finds a run that is waiting for an approval after the application was reloaded', async () => {
    const world = setup(ALL);
    world.data[ORG_A]!.runs = [run(10, 'WAITING_APPROVAL'), run(11, 'SUCCEEDED', { result: 'old' })];
    world.data[ORG_A]!.approvals = [approval(1, 10)];
    const { fixture, el } = panel();
    await until(fixture, () => q(el, 'exec-waiting') !== null && el.querySelector('app-approval-card') !== null);
    expect(text(q(el, 'exec-waiting'))).toContain('paused');
    expect(el.querySelector('app-approval-card')).not.toBeNull();
    expect(world.unexpected).toEqual([]);
  });

  it('follows the run to its end after an approval and then stops polling', async () => {
    const world = setup(ALL);
    let current = run(10, 'WAITING_APPROVAL');
    world.data[ORG_A]!.runs = [current];
    world.data[ORG_A]!.approvals = [approval(1, 10)];
    world.hooks.runRead = () => current;
    world.hooks.decide = () => {
      // The Core API records the decision, resumes the run and the tool executes.
      current = run(10, 'SUCCEEDED', { result: 'Note recorded.', completedAt: '2026-01-01T00:02:00Z', version: 3 });
      world.data[ORG_A]!.runs = [current];
      world.data[ORG_A]!.approvals = [approval(1, 10, 'APPROVED', { decidedBy: id(99), decidedAt: '2026-01-01T00:01:00Z', invocationStatus: 'SUCCEEDED' })];
      world.data[ORG_A]!.calls = [call(1, 10, { toolCode: 'diagnostic.record_note', toolName: 'Record a note', policy: 'APPROVAL_REQUIRED', result: 'receipt-1' })];
      return ok(world.data[ORG_A]!.approvals[0]);
    };
    const { fixture, el } = panel();
    await until(fixture, () => el.querySelector('[data-testid=approve-start]') !== null);
    click(q(el, 'approve-start'));
    await settle(fixture, 3);
    (q(el, 'approve-reviewed') as HTMLInputElement).click();
    await settle(fixture, 2);
    click(q(el, 'approve-confirm'));
    await until(fixture, () => q(el, 'exec-result') !== null);
    expect(text(q(el, 'exec-result'))).toContain('Note recorded.');
    expect(q(el, 'decision-needed')).toBeNull();                           // no longer displayed as pending
    expect(titles(el)).toContain('Tool completed: Record a note');
    const reads = () => world.desktop.requests.filter((r) => r.method === 'GET' && r.path.endsWith(`/executions/${id(10)}`)).length;
    await settle(fixture, 4);
    const settled = reads();
    await new Promise((resolve) => setTimeout(resolve, 300));
    expect(reads()).toBe(settled);                                         // polling stopped at the terminal state
  });

  it('shows the final state of a run decided while the interface was closed', async () => {
    const world = setup(ALL);
    world.data[ORG_A]!.runs = [run(10, 'SUCCEEDED', { result: 'Finished while you were away.', completedAt: '2026-01-01T00:02:00Z' })];
    world.data[ORG_A]!.approvals = [approval(1, 10, 'APPROVED', { decidedBy: id(99), decidedAt: '2026-01-01T00:01:00Z' })];
    const { fixture, el } = panel();
    await settle(fixture);
    expect(q(el, 'exec-current')).toBeNull();                              // nothing is waiting: nothing is auto-opened
    click(el.querySelector('button.link') as HTMLElement);
    await until(fixture, () => q(el, 'exec-result') !== null);
    expect(text(q(el, 'exec-result'))).toContain('Finished while you were away.');
    expect(q(el, 'decision-needed')).toBeNull();
  });

  it('shows a failed resume as a failure, not as something pending', async () => {
    const world = setup(ALL);
    world.data[ORG_A]!.runs = [run(10, 'FAILED', { errorCode: 'RESUME_FAILED', errorMessage: 'The decision could not be delivered to the orchestrator in time.', completedAt: '2026-01-01T00:20:00Z' })];
    world.data[ORG_A]!.approvals = [approval(1, 10, 'CANCELLED', { executionStatus: 'FAILED', invocationStatus: 'FAILED' })];
    const { fixture, el } = panel();
    await settle(fixture);
    click(el.querySelector('button.link') as HTMLElement);
    await until(fixture, () => q(el, 'exec-error') !== null);
    expect(text(q(el, 'exec-error'))).toContain('could not be delivered');
    expect(q(el, 'decision-needed')).toBeNull();
    expect(titles(el).at(-1)).toBe('Execution failed');
  });

  it('keeps the bounded polling of a run that is merely waiting, and stops it when the panel is destroyed', async () => {
    const world = setup(ALL);
    world.data[ORG_A]!.runs = [run(10, 'WAITING_APPROVAL')];
    world.data[ORG_A]!.approvals = [approval(1, 10)];
    const { fixture } = panel();
    await until(fixture, () => world.desktop.requests.filter((r) => r.path.endsWith(`/executions/${id(10)}`)).length >= 2);
    fixture.destroy();
    const after = world.desktop.requests.length;
    await new Promise((resolve) => setTimeout(resolve, 200));
    expect(world.desktop.requests.length).toBe(after);
  });
});

describe('Organization isolation', () => {
  it('never shows a late answer of the previous organization after a switch', async () => {
    const world = setup(ALL);
    world.data[ORG_A]!.calls = [call(1, 10, { toolName: 'Secret tool of A', toolCode: 'a.secret' })];
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    world.hooks.toolCalls = async (org, executionId) => {
      if (org === ORG_A) {
        await gate;
        return ok(page(world.data[ORG_A]!.calls.filter((c) => c.executionId === executionId)));
      }
      return ok(page([]));
    };
    const { fixture, el } = activity(run(10, 'RUNNING'));
    await spin(fixture, 3);
    world.organizationId.set(ORG_B);
    await spin(fixture, 4);
    release();
    await spin(fixture, 8);
    expect(text(el)).not.toContain('Secret tool of A');
    expect(el.querySelector('app-tool-activity-card')).toBeNull();
  });

  it('lists only the approvals of the current organization and reloads on a switch', async () => {
    const world = setup(ALL);
    world.data[ORG_A]!.approvals = [approval(1, 10, 'APPROVED', { toolName: 'Tool of A', decidedAt: NOW, decidedBy: id(99) })];
    world.data[ORG_B]!.approvals = [approval(2, 20, 'APPROVED', { toolName: 'Tool of B', decidedAt: NOW, decidedBy: id(98) })];
    const fixture = TestBed.createComponent(ApprovalsTab);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'All') as HTMLElement).click();
    await until(fixture, () => text(el).includes('Tool of A'));
    expect(text(el)).not.toContain('Tool of B');
    world.organizationId.set(ORG_B);
    await until(fixture, () => text(el).includes('Tool of B'));
    expect(text(el)).not.toContain('Tool of A');
  });

  it('names the member who decided (display name or a neutral fallback), never an identifier', async () => {
    const world = setup(ALL);
    world.data[ORG_A]!.approvals = [
      approval(1, 10, 'APPROVED', { toolName: 'Named tool', decidedAt: NOW, decidedBy: id(99), decidedByName: 'Ada Lovelace' }),
      approval(2, 11, 'APPROVED', { toolName: 'Fallback tool', decidedAt: NOW, decidedBy: id(98), decidedByName: 'Organization member' }),
      approval(3, 12, 'REJECTED', { toolName: 'Old server tool', decidedAt: NOW, decidedBy: id(97) }),   // a server that predates the field
    ];
    const fixture = TestBed.createComponent(ApprovalsTab);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'All') as HTMLElement).click();
    await until(fixture, () => text(el).includes('Named tool'));
    const shown = text(el);
    expect(shown).toContain('by Ada Lovelace');
    expect(shown.match(/by Organization member/g)?.length).toBe(2);        // the explicit fallback and the missing field
    expect(shown).not.toContain('by member');
    // the "by ..." segments carry a name only: no identifier, not even a shortened one (the execution id elsewhere on the card is a separate item)
    for (const segment of shown.match(/ by [^·]*/g) ?? []) expect(segment).not.toMatch(/[0-9a-f]{8}/);
  });

  it('refuses a decision the moment the organization changes under an open card', async () => {
    const world = setup(ALL);
    world.data[ORG_A]!.approvals = [approval(1, 10)];
    world.data[ORG_A]!.runs = [run(10, 'WAITING_APPROVAL')];
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    world.hooks.decide = async () => {
      await gate;
      return ok(approval(1, 10, 'APPROVED'));
    };
    const { fixture, el, changed } = activity(run(10, 'WAITING_APPROVAL'));
    await settle(fixture);
    click(q(el, 'approve-start'));
    await settle(fixture, 3);
    (q(el, 'approve-reviewed') as HTMLInputElement).click();
    await settle(fixture, 2);
    click(q(el, 'approve-confirm'));
    await settle(fixture, 3);
    world.organizationId.set(ORG_B);
    release();
    await settle(fixture, 6);
    expect(changed).not.toHaveBeenCalled();                                // the answer belonged to the other organization
  });
});

describe('Approvals history', () => {
  it('filters by status, shows the decision and the outcome, and pages through the API page structure', async () => {
    const world = setup(ALL);
    const many = Array.from({ length: 25 }, (_, i) => approval(i + 1, 10 + i, 'APPROVED', { toolName: `Tool ${i + 1}`, decidedAt: NOW, decidedBy: id(99), invocationStatus: 'SUCCEEDED' }));
    world.data[ORG_A]!.approvals = [...many, approval(40, 50, 'REJECTED', { toolName: 'Rejected tool', rejectionReason: 'No', decidedAt: NOW, decidedBy: id(98) })];
    const fixture = TestBed.createComponent(ApprovalsTab);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    await settle(fixture);
    expect(q(el, 'approvals-empty')).not.toBeNull();                       // default filter is "Pending": none
    const choose = async (label: string) => {
      (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === label) as HTMLElement).click();
      await settle(fixture, 6);
    };
    await choose('Rejected');
    expect(text(el)).toContain('Rejected tool');
    expect(text(el)).toContain('Reason: No');
    await choose('Approved');
    expect(el.querySelectorAll('li[data-testid^=approval-row]').length).toBe(20);
    expect(text(el)).toContain('tool call succeeded');
    expect(text(el)).toContain('Page 1 of 2');
    (Array.from(el.querySelectorAll('nav button')).find((b) => b.textContent?.trim() === 'Next') as HTMLElement).click();
    await settle(fixture, 6);
    expect(el.querySelectorAll('li[data-testid^=approval-row]').length).toBe(5);
    expect(world.desktop.requests.some((r) => r.method === 'GET' && (r.query as { page?: number } | undefined)?.page === 1)).toBe(true);
    expect(world.unexpected).toEqual([]);
  });

  it('shows pending requests as cards that can be decided, and refreshes the list after a decision', async () => {
    const world = setup(ALL);
    world.data[ORG_A]!.approvals = [approval(1, 10)];
    world.hooks.decide = () => {
      world.data[ORG_A]!.approvals = [approval(1, 10, 'REJECTED', { decidedAt: NOW, decidedBy: id(99) })];
      return ok(world.data[ORG_A]!.approvals[0]);
    };
    const fixture = TestBed.createComponent(ApprovalsTab);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    await until(fixture, () => q(el, 'reject-start') !== null);
    click(q(el, 'reject-start'));
    await settle(fixture, 3);
    click(q(el, 'reject-confirm'));
    await until(fixture, () => q(el, 'approvals-empty') !== null);         // the pending filter no longer contains it
    expect(q(el, 'approve-start')).toBeNull();
  });

  it('shows an error state when the list cannot be read', async () => {
    const world = setup(ALL);
    const base = world.desktop.handler;
    world.desktop.handler = (request) => (request.path.endsWith('/approvals') ? problem(500, 'The approvals could not be read.') : base(request));
    const fixture = TestBed.createComponent(ApprovalsTab);
    fixture.detectChanges();
    await settle(fixture);
    expect(text(fixture.nativeElement)).toContain('Approvals unavailable');
  });
});
