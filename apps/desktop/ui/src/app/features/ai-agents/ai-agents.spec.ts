import { ApplicationRef, provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { FakeDesktop } from '../../../testing/fake-desktop';
import { routes } from '../../app.routes';
import { AgentsApi } from '../../core/agents/agents.api';
import { AgentsStore } from '../../core/agents/agents.store';
import { AUTH_REQUIRED } from '../../core/auth/session.service';
import type { ApiRequest, ApiResponse } from '../../core/desktop/desktop-api';
import { AccessStore } from '../../core/identity/access.store';
import { PermissionCode } from '../../core/identity/identity.models';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { WfTeams } from '../workforce/teams-tab';
import { AgentDetailPanel } from './agent-detail';
import { AgentsTab } from './agents-tab';
import { AiAgentsPage } from './ai-agents-page';
import { ProvidersTab } from './providers-tab';
import { SkillsTab } from './skills-tab';

const ORG_A = '0195f0a4-1c2d-7e3f-8a9b-00000000000a';
const ORG_B = '0195f0a4-1c2d-7e3f-8a9b-00000000000b';
const id = (n: number) => `0195f0a4-1c2d-7e3f-8a9b-${String(n).padStart(12, '0')}`;
const NOW = '2026-01-01T00:00:00Z';

const ALL: PermissionCode[] = [
  'organization.read', 'team.read', 'team.manage', 'department.read', 'employee.read',
  'agent.read', 'agent.create', 'agent.manage', 'agent.activate', 'agent.archive', 'agent.tool.manage',
  'provider.read', 'provider.connect', 'provider.manage', 'provider.test', 'skill.read', 'skill.manage',
];
const MEMBER: PermissionCode[] = ['organization.read', 'agent.read', 'skill.read'];

const page = <T>(content: T[]) => ({ content, page: 0, size: 50, totalElements: content.length, totalPages: 1 });
const agent = (n: number, name: string, extra: object = {}) => ({
  id: id(n), handle: name.toLowerCase().replace(/\s+/g, '-'), name, avatarTone: 1, title: 'Analyst', description: '', department: null,
  responsibilities: [], status: 'DRAFT', configVersion: 1, skillCount: 0, createdAt: NOW, updatedAt: NOW, version: 0, ...extra,
});
const connection = (n: number, name: string, extra: object = {}) => ({
  id: id(n), providerType: 'ANTHROPIC', providerName: 'Anthropic Claude', displayName: name, accountIdentifier: null,
  status: 'PENDING', statusDetail: null, lastTestedAt: null, createdAt: NOW, updatedAt: NOW, version: 0, ...extra,
});
const detail = (summary: object, extra: object = {}) => ({
  agent: summary, systemInstructions: 'Be concise.', providers: [], skills: [], tools: [],
  memory: { enabled: false, scope: 'PROJECT', accessMode: 'AGENT_ONLY', retentionDays: null, updatedAt: null }, teams: [], ...extra,
});
const tool = (code: string, risk = 'LOW', maxLevel = 'READ') => ({
  code, name: code, description: `${code} tool`, category: 'dev', risk, maxLevel, mcpServer: null, mcpTool: null, enabled: true,
});

const ok = (body: unknown): ApiResponse => ({ status: 200, body });

function core(desktop: FakeDesktop): void {
  desktop.handler = (request: ApiRequest) => {
    const path = request.path;
    const forB = path.includes(ORG_B);
    if (path.endsWith(`/teams/${id(20)}/agents`) && request.method === 'GET') return ok([{ agent: agent(1, 'Ada Analyst'), joinedAt: NOW, leftAt: null }]);
    if (path.endsWith('/agents') && request.method === 'GET') return ok(page(forB ? [] : [agent(1, 'Ada Analyst'), agent(2, 'Bob Builder', { status: 'ACTIVE' })]));
    if (path.endsWith('/history')) return ok(page([{ id: id(90), actorUserId: null, action: 'agent.created', details: '', occurredAt: NOW }]));
    if (path.endsWith(`/agents/${id(1)}`)) return ok(detail(agent(1, 'Ada Analyst')));
    if (path.endsWith(`/agents/${id(2)}`)) return ok(detail(agent(2, 'Bob Builder', { status: 'ACTIVE' }), { systemInstructions: null }));
    if (path.endsWith('/providers/connections')) return ok(page(forB ? [] : [connection(10, 'Team key'), connection(11, 'Old key', { status: 'REVOKED' })]));
    if (path.endsWith('/models')) return ok([{ id: 'model-x', displayName: 'Model X' }]);
    if (path.endsWith('/skills') && request.method === 'GET') return ok(page(forB ? [] : [{ id: id(40), name: 'Code review', category: 'Dev', description: '', active: true, createdAt: NOW, updatedAt: NOW, version: 0 }]));
    if (path.endsWith('/tools')) return ok([tool('docs.search'), tool('git.access', 'HIGH', 'EXECUTE')]);
    if (path.endsWith('/teams') ) return ok(page([{ id: id(20), name: 'Launch squad', description: '', department: null, leader: null, status: 'ACTIVE', memberCount: 1, agentCount: 1, createdAt: NOW, updatedAt: NOW, version: 0 }]));
    if (path.endsWith(`/teams/${id(20)}`)) return ok({ team: { id: id(20), name: 'Launch squad', description: '', department: null, leader: null, status: 'ACTIVE', memberCount: 1, agentCount: 1, createdAt: NOW, updatedAt: NOW, version: 0 }, members: [] });
    if (path.includes('/departments') || path.includes('/positions') || path.includes('/employees')) return ok(path.endsWith('/tree') ? [] : page([]));
    return ok({});
  };
}

async function settle(fixture?: ComponentFixture<unknown>): Promise<void> {
  const appRef = TestBed.inject(ApplicationRef);
  for (let i = 0; i < 16; i++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    TestBed.tick();
    fixture?.detectChanges();
    await appRef.whenStable();
  }
}

function setup(permissions: PermissionCode[], organization: string | null = ORG_A) {
  const desktop = new FakeDesktop();
  core(desktop);
  desktop.install();
  const organizationId = signal<string | null>(organization);
  const granted = signal<ReadonlySet<PermissionCode>>(new Set(permissions));
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      { provide: AUTH_REQUIRED, useValue: true },
      { provide: WorkspaceStore, useValue: { currentOrganizationId: organizationId } },
      { provide: AccessStore, useValue: { can: (p: PermissionCode) => granted().has(p), organization: signal({ name: 'Acme' }), reload: () => undefined } },
    ],
  });
  return { desktop, organizationId, granted };
}

function render<T>(component: new () => T, inputs: Record<string, unknown> = {}): { fixture: ComponentFixture<T>; el: HTMLElement } {
  const fixture = TestBed.createComponent(component);
  Object.entries(inputs).forEach(([name, value]) => fixture.componentRef.setInput(name, value));
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement };
}

const text = (el: HTMLElement) => (el.textContent ?? '').replace(/\s+/g, ' ');
const buttons = (el: HTMLElement) => Array.from(el.querySelectorAll('button')).map((b) => (b.textContent ?? '').trim());
const button = (el: HTMLElement, label: string) => Array.from(el.querySelectorAll('button')).find((b) => (b.textContent ?? '').trim() === label);
const typeInto = (input: HTMLInputElement | HTMLTextAreaElement, value: string) => {
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
};

// jsdom does not implement <dialog> modals.
beforeAll(() => {
  const proto = HTMLDialogElement.prototype as { showModal?: () => void; close?: () => void };
  proto.showModal ??= function (this: HTMLDialogElement) { this.setAttribute('open', ''); };
  proto.close ??= function (this: HTMLDialogElement) { this.removeAttribute('open'); };
});

afterEach(() => FakeDesktop.uninstall());

describe('AgentsApi', () => {
  it('builds organization-scoped requests and drops blank filters', async () => {
    const { desktop } = setup(ALL);
    const api = TestBed.inject(AgentsApi);
    await api.listAgents(ORG_A, { q: 'ada', status: 'ACTIVE', departmentId: '', size: 25 });
    await api.activateAgent(ORG_A, id(1));
    await api.removeTeamAgent(ORG_A, id(20), id(1));
    expect(desktop.requests).toEqual([
      { method: 'GET', path: `/api/v1/organizations/${ORG_A}/agents`, query: { q: 'ada', status: 'ACTIVE', size: 25 } },
      { method: 'POST', path: `/api/v1/organizations/${ORG_A}/agents/${id(1)}/activate`, body: {} },
      { method: 'DELETE', path: `/api/v1/organizations/${ORG_A}/teams/${id(20)}/agents/${id(1)}` },
    ]);
  });

  it('refuses identifiers that could alter the path', () => {
    setup(ALL);
    const api = TestBed.inject(AgentsApi);
    expect(() => api.getAgent(ORG_A, '../../secrets')).toThrow();
    expect(() => api.listAgents('abc/../x')).toThrow();
    expect(() => api.testConnection(ORG_A, `${id(1)}/credential`)).toThrow();
  });

  it('sends a credential only in the body of the two write operations', async () => {
    const { desktop } = setup(ALL);
    const api = TestBed.inject(AgentsApi);
    await api.createConnection(ORG_A, { providerType: 'OPENAI', displayName: 'Prod', credential: 'sk-test-0123456789' });
    await api.replaceCredential(ORG_A, id(10), 'sk-test-9876543210');
    await api.listConnections(ORG_A);
    await api.testConnection(ORG_A, id(10));
    const carrying = desktop.requests.filter((r) => JSON.stringify(r).includes('sk-test'));
    expect(carrying.map((r) => `${r.method} ${r.path.split('/providers/')[1]}`)).toEqual(['POST connections', `PUT connections/${id(10)}/credential`]);
    carrying.forEach((r) => expect(JSON.stringify(r.query ?? {})).not.toContain('sk-test'));
  });
});

describe('AgentsStore', () => {
  it('only calls the endpoints the user may read', async () => {
    const { desktop } = setup(['organization.read']);
    TestBed.inject(AgentsStore);
    const { fixture } = render(AiAgentsPage);
    await settle(fixture);
    expect(desktop.requests).toEqual([]);
  });

  it('reloads for the new organization and never shows the previous one', async () => {
    const { organizationId } = setup(ALL);
    const store = TestBed.inject(AgentsStore);
    const { fixture } = render(AiAgentsPage);
    await settle(fixture);
    expect(store.agents().map((a) => a.name)).toEqual(['Ada Analyst', 'Bob Builder']);
    organizationId.set(ORG_B);
    TestBed.tick();
    expect(store.agents()).toEqual([]);
    await settle(fixture);
    expect(store.agents()).toEqual([]);
    expect(store.connections()).toEqual([]);
    organizationId.set(null);
    await settle(fixture);
    expect(text(fixture.nativeElement as HTMLElement)).toContain('No organization selected');
  });

  it('refuses to change without an organization', async () => {
    setup(ALL, null);
    const store = TestBed.inject(AgentsStore);
    await expect(store.mutate(() => Promise.resolve(1))).rejects.toThrow('No organization selected');
  });
});

describe('Agents tab', () => {
  it('lists agents with their status and offers creation only with agent.create', async () => {
    const { granted } = setup(ALL);
    const { fixture, el } = render(AgentsTab);
    await settle(fixture);
    expect(text(el)).toContain('Ada Analyst');
    expect(text(el)).toContain('Bob Builder');
    expect(text(el)).toContain('Draft');
    expect(buttons(el)).toContain('New agent');
    granted.set(new Set<PermissionCode>(MEMBER));
    await settle(fixture);
    expect(buttons(el)).not.toContain('New agent');
  });

  it('shows an access message without agent.read', async () => {
    setup(['organization.read']);
    const { fixture, el } = render(AgentsTab);
    await settle(fixture);
    expect(text(el)).toContain('Your role does not allow viewing AI agents');
  });

  it('sends the search text and status filter to the API', async () => {
    const { desktop } = setup(ALL);
    const store = TestBed.inject(AgentsStore);
    const { fixture } = render(AgentsTab);
    await settle(fixture);
    store.search.set('ada');
    store.statusFilter.set('ACTIVE');
    await settle(fixture);
    const last = desktop.to('/agents').at(-1)!;
    expect(last.query).toMatchObject({ q: 'ada', status: 'ACTIVE' });
  });

  it('creates an agent through the API and selects it', async () => {
    const { desktop } = setup(ALL);
    const { fixture, el } = render(AgentsTab);
    await settle(fixture);
    button(el, 'New agent')!.click();
    await settle(fixture);
    typeInto(el.querySelector('#ag-name') as HTMLInputElement, 'Nina New');
    (el.querySelector('#agent-form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await settle(fixture);
    const created = desktop.to('/agents', 'POST')[0]!;
    expect(created.body).toMatchObject({ name: 'Nina New' });
  });

  it('clears the selection when the organization changes', async () => {
    const { organizationId } = setup(ALL);
    const { fixture, el } = render(AgentsTab);
    await settle(fixture);
    (el.querySelector('.item') as HTMLElement).click();
    await settle(fixture);
    expect(el.querySelector('app-agent-detail')).not.toBeNull();
    organizationId.set(ORG_B);
    await settle(fixture);
    expect(el.querySelector('app-agent-detail')).toBeNull();
  });
});

describe('Agent detail', () => {
  it('offers lifecycle actions according to status and permissions', async () => {
    const { granted } = setup(ALL);
    const { fixture, el } = render(AgentDetailPanel, { agentId: id(1) });
    await settle(fixture);
    expect(buttons(el)).toContain('Activate');
    expect(buttons(el)).toContain('Archive');
    expect(text(el)).toContain('not run in this version');
    granted.set(new Set<PermissionCode>(MEMBER));
    await settle(fixture);
    expect(buttons(el)).not.toContain('Activate');
    expect(buttons(el)).not.toContain('Archive');
    expect(buttons(el)).not.toContain('Save tool permissions');
  });

  it('hides system instructions when the API does not return them', async () => {
    setup(ALL);
    const first = render(AgentDetailPanel, { agentId: id(1) });
    await settle(first.fixture);
    expect(text(first.el)).toContain('System instructions');
    const second = render(AgentDetailPanel, { agentId: id(2) });
    await settle(second.fixture);
    expect(text(second.el)).not.toContain('System instructions');
    expect(buttons(second.el)).toContain('Pause');
  });

  it('activates through the API and reloads the agent', async () => {
    const { desktop } = setup(ALL);
    const { fixture, el } = render(AgentDetailPanel, { agentId: id(1) });
    await settle(fixture);
    button(el, 'Activate')!.click();
    await settle(fixture);
    expect(desktop.to(`/agents/${id(1)}/activate`, 'POST')).toHaveLength(1);
    expect(desktop.to(`/agents/${id(1)}`).length).toBeGreaterThan(1);
  });

  it('keeps tools disabled by default and forces approval on high-risk tools', async () => {
    const { desktop } = setup(ALL);
    const { fixture, el } = render(AgentDetailPanel, { agentId: id(1) });
    await settle(fixture);
    const boxes = () => Array.from(el.querySelectorAll<HTMLInputElement>('li input[type=checkbox]'));
    // Nothing is granted initially.
    expect(el.querySelectorAll('select[aria-label^="Access level"]').length).toBe(0);
    const git = Array.from(el.querySelectorAll('li')).find((li) => (li.textContent ?? '').includes('git.access'))!;
    (git.querySelector('input[type=checkbox]') as HTMLInputElement).click();
    await settle(fixture);
    const approval = Array.from(git.querySelectorAll<HTMLInputElement>('input[type=checkbox]')).at(-1)!;
    expect(approval.checked).toBe(true);
    expect(approval.disabled).toBe(true);
    expect(boxes().length).toBeGreaterThan(2);
    button(el, 'Save tool permissions')!.click();
    await settle(fixture);
    const saved = desktop.to(`/agents/${id(1)}/tools`, 'PUT')[0]!;
    expect(saved.body).toEqual({ grants: [{ toolCode: 'git.access', level: 'READ', approvalRequired: true }] });
  });

  it('never shows data of another organization after a switch', async () => {
    const { organizationId } = setup(ALL);
    const { fixture, el } = render(AgentDetailPanel, { agentId: id(1) });
    await settle(fixture);
    expect(text(el)).toContain('Ada Analyst');
    organizationId.set(ORG_B);
    TestBed.tick();
    fixture.detectChanges();
    expect(text(el)).not.toContain('Ada Analyst');
  });
});

describe('Providers tab', () => {
  it('lists connections with their status and no credential', async () => {
    setup(ALL);
    const { fixture, el } = render(ProvidersTab);
    await settle(fixture);
    expect(text(el)).toContain('Team key');
    expect(text(el)).toContain('Not validated');
    expect(text(el)).toContain('Revoked');
    expect(text(el)).toContain('Saving a credential does not make it valid');
    expect(el.querySelectorAll('input[type=password]').length).toBe(0);
  });

  it('only offers the actions the user is allowed', async () => {
    const { granted } = setup(ALL);
    const { fixture, el } = render(ProvidersTab);
    await settle(fixture);
    expect(buttons(el)).toEqual(expect.arrayContaining(['Connect a provider', 'Validate', 'Replace credential', 'Revoke']));
    granted.set(new Set<PermissionCode>(['organization.read', 'provider.read']));
    await settle(fixture);
    expect(buttons(el)).not.toContain('Connect a provider');
    expect(buttons(el)).not.toContain('Validate');
    expect(buttons(el)).not.toContain('Revoke');
  });

  it('sends the credential once, then clears it and never displays it', async () => {
    const { desktop } = setup(ALL);
    const { fixture, el } = render(ProvidersTab);
    await settle(fixture);
    button(el, 'Connect a provider')!.click();
    await settle(fixture);
    typeInto(el.querySelector('#pc-name') as HTMLInputElement, 'Prod key');
    const field = el.querySelector('#pc-credential') as HTMLInputElement;
    expect(field.type).toBe('password');
    typeInto(field, 'sk-test-0123456789abcdef');
    (el.querySelector('#provider-form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await settle(fixture);
    const created = desktop.to('/providers/connections', 'POST')[0]!;
    expect(created.body).toMatchObject({ providerType: 'ANTHROPIC', displayName: 'Prod key', credential: 'sk-test-0123456789abcdef' });
    expect(field.value).toBe('');
    expect(el.innerHTML).not.toContain('sk-test-0123456789abcdef');
  });

  it('rejects a malformed credential before any request', async () => {
    const { desktop } = setup(ALL);
    const { fixture, el } = render(ProvidersTab);
    await settle(fixture);
    button(el, 'Connect a provider')!.click();
    await settle(fixture);
    typeInto(el.querySelector('#pc-name') as HTMLInputElement, 'Prod key');
    typeInto(el.querySelector('#pc-credential') as HTMLInputElement, 'has spaces in it');
    (el.querySelector('#provider-form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await settle(fixture);
    expect(desktop.to('/providers/connections', 'POST')).toHaveLength(0);
  });

  it('validates a connection on request only', async () => {
    const { desktop } = setup(ALL);
    const { fixture, el } = render(ProvidersTab);
    await settle(fixture);
    expect(desktop.to('/test', 'POST')).toHaveLength(0);
    button(el, 'Validate')!.click();
    await settle(fixture);
    expect(desktop.to(`/providers/connections/${id(10)}/test`, 'POST')).toHaveLength(1);
  });
});

describe('Provider credential hygiene (renderer)', () => {
  async function openForm() {
    const ctx = setup(ALL);
    const { fixture, el } = render(ProvidersTab);
    await settle(fixture);
    button(el, 'Connect a provider')!.click();
    await settle(fixture);
    typeInto(el.querySelector('#pc-name') as HTMLInputElement, 'Prod key');
    const field = el.querySelector('#pc-credential') as HTMLInputElement;
    return { ...ctx, fixture, el, field };
  }

  it('asks the browser not to autofill, correct, capitalize or remember the key', async () => {
    const { field } = await openForm();
    expect(field.type).toBe('password');
    expect(field.getAttribute('autocomplete')).toBe('new-password');
    expect(field.getAttribute('spellcheck')).toBe('false');
    expect(field.getAttribute('autocapitalize')).toBe('off');
    expect(field.getAttribute('autocorrect')).toBe('off');
    expect(field.getAttribute('data-1p-ignore')).toBe('true');
    expect(field.getAttribute('data-lpignore')).toBe('true');
  });

  it('empties the field before the request completes', async () => {
    const { desktop, fixture, el, field } = await openForm();
    let release!: (response: ApiResponse) => void;
    desktop.handler = (request) =>
      request.method === 'POST' && request.path.endsWith('/providers/connections')
        ? new Promise<ApiResponse>((resolve) => (release = resolve))
        : ok(page([]));
    typeInto(field, 'sk-test-0123456789abcdef');
    (el.querySelector('#provider-form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await settle(fixture);
    expect(desktop.to('/providers/connections', 'POST')).toHaveLength(1);
    expect(field.value).toBe('');
    release(ok(connection(12, 'Prod key')));
    await settle(fixture);
  });

  it('wipes the typed key when the window loses focus', async () => {
    const { fixture, field } = await openForm();
    typeInto(field, 'sk-test-0123456789abcdef');
    expect(field.value).not.toBe('');
    window.dispatchEvent(new Event('blur'));
    await settle(fixture);
    expect(field.value).toBe('');
  });

  it('wipes the typed key when the component is destroyed and never writes it to browser storage', async () => {
    const { fixture, field } = await openForm();
    typeInto(field, 'sk-test-0123456789abcdef');
    fixture.destroy();
    expect(field.value).toBe('');
    expect(JSON.stringify({ ...localStorage })).not.toContain('sk-test');
    expect(JSON.stringify({ ...sessionStorage })).not.toContain('sk-test');
  });

  it('wipes the key and keeps the dialog closed state after cancelling', async () => {
    const { fixture, el, field } = await openForm();
    typeInto(field, 'sk-test-0123456789abcdef');
    button(el, 'Cancel')!.click();
    await settle(fixture);
    expect(field.value).toBe('');
  });
});

describe('Skills tab', () => {
  it('lists skills and hides management without skill.manage', async () => {
    const { granted } = setup(ALL);
    const { fixture, el } = render(SkillsTab);
    await settle(fixture);
    expect(text(el)).toContain('Code review');
    expect(buttons(el)).toContain('New skill');
    granted.set(new Set<PermissionCode>(MEMBER));
    await settle(fixture);
    expect(buttons(el)).not.toContain('New skill');
    expect(buttons(el)).not.toContain('Delete');
  });
});

describe('Teams tab with AI agents', () => {
  it('lists AI agents apart from human members and counts them separately', async () => {
    const { desktop } = setup(ALL);
    const { fixture, el } = render(WfTeams);
    await settle(fixture);
    expect(text(el)).toContain('1 member(s) · 1 AI agent(s)');
    (el.querySelector('.item') as HTMLElement).click();
    await settle(fixture);
    expect(text(el)).toContain('Members (0)');
    expect(text(el)).toContain('AI agents (1)');
    expect(desktop.to(`/teams/${id(20)}/agents`)).toHaveLength(1);
  });

  it('does not request agents without agent.read, and hides adding without team.manage', async () => {
    const { desktop, granted } = setup(['team.read', 'department.read', 'employee.read']);
    const first = render(WfTeams);
    await settle(first.fixture);
    (first.el.querySelector('.item') as HTMLElement).click();
    await settle(first.fixture);
    expect(desktop.to(`/teams/${id(20)}/agents`)).toHaveLength(0);
    expect(text(first.el)).not.toContain('AI agents (');
    granted.set(new Set<PermissionCode>(['team.read', 'agent.read']));
    await settle(first.fixture);
    expect(text(first.el)).toContain('AI agents (1)');
    expect(buttons(first.el)).not.toContain('Add');
  });
});

describe('Routing', () => {
  it('keeps the demo agents for the mock build and uses the real ones in api mode', async () => {
    const route = routes.flatMap((r) => r.children ?? []).find((r) => r.path === 'agents');
    expect(route?.loadChildren).toBeDefined();
    TestBed.configureTestingModule({ providers: [{ provide: AUTH_REQUIRED, useValue: true }] });
    const real = (await TestBed.runInInjectionContext(() => route!.loadChildren!())) as { path?: string }[];
    expect(real.some((r) => r.path === ':agentId')).toBe(false);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: AUTH_REQUIRED, useValue: false }] });
    const demo = (await TestBed.runInInjectionContext(() => route!.loadChildren!())) as { path?: string }[];
    expect(demo.some((r) => r.path === ':agentId')).toBe(true);
  });
});
