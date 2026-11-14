import { ApplicationRef, provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { FakeDesktop } from '../../../testing/fake-desktop';
import { AgentsApi } from '../../core/agents/agents.api';
import { AgentDetail, AgentExecution } from '../../core/agents/agents.models';
import { AUTH_REQUIRED } from '../../core/auth/session.service';
import type { ApiRequest, ApiResponse } from '../../core/desktop/desktop-api';
import { AccessStore } from '../../core/identity/access.store';
import { PermissionCode } from '../../core/identity/identity.models';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { AgentDetailPanel } from './agent-detail';
import { AgentExecutions, EXECUTION_POLL_MS } from './agent-executions';

const ORG_A = '0195f0a4-1c2d-7e3f-8a9b-00000000000a';
const ORG_B = '0195f0a4-1c2d-7e3f-8a9b-00000000000b';
const id = (n: number) => `0195f0a4-1c2d-7e3f-8a9b-${String(n).padStart(12, '0')}`;
const NOW = '2026-01-01T00:00:00Z';
const SECRET_LIKE = 'sk-ant-should-never-appear';

const RUN: PermissionCode[] = ['organization.read', 'agent.read', 'agent.manage', 'agent.execute', 'agent.execution.read', 'provider.read', 'skill.read', 'agent.tool.manage'];
const READ_ONLY: PermissionCode[] = ['organization.read', 'agent.read', 'skill.read'];

const summary = (n: number, status = 'ACTIVE') => ({
  id: id(n), handle: `agent-${n}`, name: `Agent ${n}`, avatarTone: 1, title: '', description: '', department: null, responsibilities: [],
  status, configVersion: 1, skillCount: 0, createdAt: NOW, updatedAt: NOW, version: 0,
});
const binding = (status = 'VALID', providerType = 'ANTHROPIC') => ({
  id: id(70), connection: { id: id(71), providerType, displayName: 'Key', status }, modelId: 'model-x', role: 'PRIMARY', priority: 0, config: '{}',
});
const detail = (n: number, status = 'ACTIVE', providers: unknown[] = [binding()]) => ({
  agent: summary(n, status), systemInstructions: null, providers, skills: [], tools: [],
  memory: { enabled: false, scope: 'PROJECT', accessMode: 'AGENT_ONLY', retentionDays: null, updatedAt: null }, teams: [],
}) as unknown as AgentDetail;
const execution = (n: number, status: string, extra: object = {}): AgentExecution => ({
  id: id(n), organizationId: ORG_A, agentId: id(1), requestedBy: id(99), task: `task ${n}`, status, providerType: 'ANTHROPIC', modelId: 'model-x',
  result: null, inputTokens: null, outputTokens: null, errorCode: null, errorMessage: null, createdAt: NOW, startedAt: null, completedAt: null, version: 0, ...extra,
}) as AgentExecution;

const ok = (body: unknown): ApiResponse => ({ status: 200, body });
const page = (content: unknown[]) => ({ content, page: 0, size: 10, totalElements: content.length, totalPages: 1 });

async function settle(fixture?: ComponentFixture<unknown>): Promise<void> {
  const appRef = TestBed.inject(ApplicationRef);
  for (let i = 0; i < 12; i++) {
    await new Promise((resolve) => setTimeout(resolve, 5));
    TestBed.tick();
    fixture?.detectChanges();
    await appRef.whenStable();
  }
}

function setup(permissions: PermissionCode[]) {
  const desktop = new FakeDesktop();
  const runs: AgentExecution[] = [];
  const polls: Array<() => AgentExecution> = [];
  desktop.handler = (request: ApiRequest) => {
    const path = request.path;
    if (request.method === 'POST' && path.endsWith('/executions')) {
      const started = execution(10, 'PENDING', { task: (request.body as { task: string }).task });
      runs.unshift(started);
      return { status: 202, body: started };
    }
    if (request.method === 'GET' && /\/executions\/[^/]+$/.test(path)) {
      const next = polls.shift();
      return ok(next ? next() : runs[0] ?? execution(10, 'SUCCEEDED', { result: 'done' }));
    }
    if (request.method === 'GET' && path.endsWith('/executions')) return ok(page(runs));
    return ok({});
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
  return { desktop, organizationId, granted, runs, polls };
}

function render(d: AgentDetail) {
  const fixture = TestBed.createComponent(AgentExecutions);
  fixture.componentRef.setInput('detail', d);
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement };
}

const text = (el: HTMLElement) => (el.textContent ?? '').replace(/\s+/g, ' ');
const runButton = (el: HTMLElement) => Array.from(el.querySelectorAll('button')).find((b) => /Run agent|Launching|Running/.test(b.textContent ?? ''))!;
const type = (el: HTMLElement, value: string) => {
  const area = el.querySelector('#exec-task') as HTMLTextAreaElement;
  area.value = value;
  area.dispatchEvent(new Event('input', { bubbles: true }));
};

afterEach(() => FakeDesktop.uninstall());

describe('AgentsApi executions', () => {
  it('builds organization-scoped requests and sends only the instruction', async () => {
    const { desktop } = setup(RUN);
    const api = TestBed.inject(AgentsApi);
    await api.startExecution(ORG_A, id(1), 'Write a haiku');
    await api.listExecutions(ORG_A, id(1), { status: 'FAILED', size: 5 });
    await api.getExecution(ORG_A, id(10));
    expect(desktop.requests).toEqual([
      { method: 'POST', path: `/api/v1/organizations/${ORG_A}/agents/${id(1)}/executions`, body: { task: 'Write a haiku' } },
      { method: 'GET', path: `/api/v1/organizations/${ORG_A}/agents/${id(1)}/executions`, query: { size: 5, status: 'FAILED' } },
      { method: 'GET', path: `/api/v1/organizations/${ORG_A}/executions/${id(10)}` },
    ]);
    expect(() => api.getExecution(ORG_A, '../internal')).toThrow();
  });
});

describe('Agent execution panel', () => {
  it('shows the launch form only to users allowed to execute, on an eligible agent', async () => {
    const { granted } = setup(RUN);
    const { fixture, el } = render(detail(1));
    await settle(fixture);
    expect(el.querySelector('#exec-task')).not.toBeNull();
    granted.set(new Set<PermissionCode>(READ_ONLY));
    await settle(fixture);
    expect(el.querySelector('#exec-task')).toBeNull();
    expect(text(el)).toContain('does not allow launching');
    expect(text(el)).not.toContain('Execution history');
  });

  it.each([
    ['a draft agent', detail(1, 'DRAFT'), 'Only an active agent'],
    ['an agent without a primary provider', detail(1, 'ACTIVE', []), 'no primary provider'],
    ['an unvalidated connection', detail(1, 'ACTIVE', [binding('PENDING')]), 'not validated'],
    ['a provider that is not enabled', detail(1, 'ACTIVE', [binding('VALID', 'OPENAI')]), 'Only Anthropic'],
  ])('disables the execution for %s', async (_label, agent, message) => {
    const { desktop } = setup(RUN);
    const { fixture, el } = render(agent);
    await settle(fixture);
    expect(el.querySelector('#exec-task')).toBeNull();
    expect(text(el)).toContain(message);
    expect(desktop.to('/executions', 'POST')).toHaveLength(0);
  });

  it('validates the instruction before launching', async () => {
    const { desktop } = setup(RUN);
    const { fixture, el } = render(detail(1));
    await settle(fixture);
    expect(runButton(el).disabled).toBe(true);
    type(el, '   ');
    await settle(fixture);
    expect(runButton(el).disabled).toBe(true);
    type(el, 'Summarize the roadmap');
    await settle(fixture);
    expect(runButton(el).disabled).toBe(false);
    expect((el.querySelector('#exec-task') as HTMLTextAreaElement).maxLength).toBe(8000);
    expect(desktop.to('/executions', 'POST')).toHaveLength(0);
  });

  it('launches explicitly, shows progress and then the result', async () => {
    const { desktop, polls } = setup(RUN);
    polls.push(() => execution(10, 'RUNNING'), () => execution(10, 'SUCCEEDED', { result: 'Autumn moonlight', inputTokens: 12, outputTokens: 7 }));
    const { fixture, el } = render(detail(1));
    await settle(fixture);
    type(el, 'Write a haiku');
    await settle(fixture);
    runButton(el).click();
    await settle(fixture);
    expect(desktop.to('/executions', 'POST')[0]!.body).toEqual({ task: 'Write a haiku' });
    expect((el.querySelector('#exec-task') as HTMLTextAreaElement).value).toBe('');
    for (let i = 0; i < 10 && !el.querySelector('[data-testid=exec-result]'); i++) {
      await settle(fixture);
    }
    expect(text(el.querySelector('[data-testid=exec-result]') as HTMLElement)).toContain('Autumn moonlight');
    expect(text(el)).toContain('12 input / 7 output');
    expect(text(el)).toContain('Succeeded');
  });

  it('shows a safe message for a failed execution', async () => {
    const { polls } = setup(RUN);
    polls.push(() => execution(10, 'FAILED', { errorCode: 'PROVIDER_RATE_LIMIT', errorMessage: 'The provider is rate limiting requests. Try again later.' }));
    const { fixture, el } = render(detail(1));
    await settle(fixture);
    type(el, 'Do it');
    await settle(fixture);
    runButton(el).click();
    for (let i = 0; i < 10 && !el.querySelector('[data-testid=exec-error]'); i++) {
      await settle(fixture);
    }
    expect(text(el.querySelector('[data-testid=exec-error]') as HTMLElement)).toContain('rate limiting');
    expect(text(el)).not.toContain('Succeeded');
  });

  it('shows the server refusal when the launch is rejected', async () => {
    const { desktop } = setup(RUN);
    const base = desktop.handler;
    desktop.handler = (request) =>
      request.method === 'POST' && request.path.endsWith('/executions')
        ? { status: 409, body: { detail: 'Too many executions are in progress. Try again when one has finished.' } }
        : base(request);
    const { fixture, el } = render(detail(1));
    await settle(fixture);
    type(el, 'Do it');
    await settle(fixture);
    runButton(el).click();
    await settle(fixture);
    expect(text(el)).toContain('Too many executions are in progress');
    expect(el.querySelector('[data-testid=exec-current]')).toBeNull();
  });

  it('lists the history, opens an earlier result and shows the empty state', async () => {
    const { runs } = setup(RUN);
    runs.push(execution(11, 'SUCCEEDED', { result: 'Earlier result' }), execution(12, 'FAILED', { errorMessage: 'Earlier failure' }));
    const { fixture, el } = render(detail(1));
    await settle(fixture);
    expect(text(el)).toContain('task 11');
    (Array.from(el.querySelectorAll('button.link'))[0] as HTMLElement).click();
    await settle(fixture);
    expect(text(el.querySelector('[data-testid=exec-result]') as HTMLElement)).toContain('Earlier result');
  });

  it('shows the empty history', async () => {
    setup(RUN);
    const { fixture, el } = render(detail(1));
    await settle(fixture);
    expect(el.querySelector('[data-testid=exec-empty]')).not.toBeNull();
  });

  it('drops the draft and any shown run when the organization changes', async () => {
    const { organizationId } = setup(RUN);
    const { fixture, el } = render(detail(1));
    await settle(fixture);
    type(el, 'Draft instruction');
    await settle(fixture);
    expect((el.querySelector('#exec-task') as HTMLTextAreaElement).value).toBe('Draft instruction');
    organizationId.set(ORG_B);
    await settle(fixture);
    expect((el.querySelector('#exec-task') as HTMLTextAreaElement | null)?.value ?? '').toBe('');
    expect(el.querySelector('[data-testid=exec-current]')).toBeNull();
  });

  it('never displays or asks for a provider credential and keeps nothing in browser storage', async () => {
    const { desktop } = setup(RUN);
    desktop.handler = (request) => ({ status: request.method === 'POST' ? 202 : 200, body: request.method === 'POST' ? execution(10, 'PENDING') : page([]) });
    const { fixture, el } = render(detail(1));
    await settle(fixture);
    type(el, 'task');
    await settle(fixture);
    runButton(el).click();
    await settle(fixture);
    expect(el.querySelector('input[type=password]')).toBeNull();
    expect(text(el).toLowerCase()).not.toContain('api key');
    expect(JSON.stringify(desktop.requests)).not.toContain('credential');
    expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toContain(SECRET_LIKE);
  });
});

/** Lets timers and effects run without waiting for requests that stay pending on purpose. */
async function flush(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 8; i++) {
    await new Promise((resolve) => setTimeout(resolve, 5));
    TestBed.tick();
    fixture.detectChanges();
  }
}

/**
 * A fake Core API that follows the REAL response contracts (agents.api.ts): lists are `Page` wrappers,
 * but the tool catalog is a plain `ToolDefinition[]`. Unknown routes answer 404 so that a request the
 * component should not make, or a wrongly shaped fixture, cannot go unnoticed.
 */
function contractHandler(overrides: { executions?: () => ApiResponse | Promise<ApiResponse> } = {}) {
  return (request: ApiRequest): ApiResponse | Promise<ApiResponse> => {
    const path = request.path;
    if (request.method !== 'GET') return { status: 404, body: { detail: 'unexpected write' } };
    if (path.endsWith(`/agents/${id(1)}`)) return ok(detail(1));
    if (path.endsWith(`/agents/${id(1)}/executions`)) return overrides.executions?.() ?? ok(page([]));
    if (path.endsWith(`/agents/${id(1)}/history`)) return ok(page([]));
    if (path.endsWith('/tools')) return ok([]); // ToolDefinition[]: NOT a page
    if (path.endsWith('/skills')) return ok(page([]));
    if (path.endsWith('/providers/connections')) return ok(page([]));
    return { status: 404, body: { detail: `unexpected request ${request.method} ${path}` } };
  };
}

async function mountDetail(handler: (request: ApiRequest) => ApiResponse | Promise<ApiResponse>, permissions: PermissionCode[] = RUN) {
  const desktop = new FakeDesktop();
  desktop.handler = handler;
  desktop.install();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      { provide: AUTH_REQUIRED, useValue: true },
      { provide: WorkspaceStore, useValue: { currentOrganizationId: signal<string | null>(ORG_A) } },
      { provide: AccessStore, useValue: { can: (p: PermissionCode) => permissions.includes(p), organization: signal({ name: 'Acme' }), reload: () => undefined } },
    ],
  });
  const fixture = TestBed.createComponent(AgentDetailPanel);
  fixture.componentRef.setInput('agentId', id(1));
  fixture.detectChanges();
  return { desktop, fixture, el: fixture.nativeElement as HTMLElement };
}

describe('Agent detail integration', () => {
  let errors: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    // Angular reports rendering errors (such as a non-iterable @for) through console.error.
    errors = vi.spyOn(console, 'error');
  });

  afterEach(() => {
    expect(errors).not.toHaveBeenCalled();
    errors.mockRestore();
  });

  it('shows the execution panel with an empty history and no rendering error', async () => {
    const { fixture, el } = await mountDetail(contractHandler());
    await settle(fixture);
    expect(text(el)).toContain('Run this agent');
    expect(el.querySelector('#exec-task')).not.toBeNull();
    expect(el.querySelector('[data-testid=exec-empty]')).not.toBeNull();
    expect(text(el)).toContain('Tool permissions');
  });

  it('renders one or more executions of the history', async () => {
    const { fixture, el } = await mountDetail(
      contractHandler({ executions: () => ok(page([execution(11, 'SUCCEEDED', { result: 'r' }), execution(12, 'FAILED', { errorMessage: 'e' })])) }),
    );
    await settle(fixture);
    expect(el.querySelectorAll('button.link')).toHaveLength(2);
    expect(text(el)).toContain('task 11');
    expect(text(el)).toContain('task 12');
    expect(el.querySelector('[data-testid=exec-empty]')).toBeNull();
  });

  it('shows the loading state while the history is requested, then the result', async () => {
    let release!: (response: ApiResponse) => void;
    const pending = new Promise<ApiResponse>((resolve) => (release = resolve));
    const { fixture, el } = await mountDetail(contractHandler({ executions: () => pending }));
    await flush(fixture); // does not wait for stability: the history request is deliberately still pending
    expect(el.querySelector('ui-skeleton')).not.toBeNull();
    expect(el.querySelector('[data-testid=exec-empty]')).toBeNull();
    release(ok(page([execution(11, 'SUCCEEDED')])));
    await settle(fixture);
    expect(text(el)).toContain('task 11');
  });

  it('handles an empty API response', async () => {
    const { fixture, el } = await mountDetail(contractHandler({ executions: () => ok({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }) }));
    await settle(fixture);
    expect(el.querySelector('[data-testid=exec-empty]')).not.toBeNull();
  });

  it('shows an error state when the history request fails, without breaking the rest of the panel', async () => {
    const { fixture, el } = await mountDetail(contractHandler({ executions: () => ({ status: 500, body: { detail: 'Request failed (500).' } }) }));
    await settle(fixture);
    expect(text(el)).toContain('History unavailable');
    expect(el.querySelector('#exec-task')).not.toBeNull();
  });

  it('uses the same response shapes as the real API contract', async () => {
    const desktop = new FakeDesktop();
    desktop.handler = contractHandler();
    desktop.install(); // before injection: the backend client captures the bridge when it is created
    setupApiOnly();
    const api = TestBed.inject(AgentsApi);
    // The tool catalog is an array, every list endpoint is a page whose `content` is an array.
    expect(Array.isArray(await api.toolCatalog(ORG_A))).toBe(true);
    expect(Array.isArray((await api.listSkills(ORG_A)).content)).toBe(true);
    expect(Array.isArray((await api.listConnections(ORG_A)).content)).toBe(true);
    expect(Array.isArray((await api.listExecutions(ORG_A, id(1))).content)).toBe(true);
  });
});

function setupApiOnly(): void {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      { provide: AUTH_REQUIRED, useValue: true },
      { provide: WorkspaceStore, useValue: { currentOrganizationId: signal<string | null>(ORG_A) } },
      { provide: AccessStore, useValue: { can: () => true, organization: signal({ name: 'Acme' }), reload: () => undefined } },
    ],
  });
}
