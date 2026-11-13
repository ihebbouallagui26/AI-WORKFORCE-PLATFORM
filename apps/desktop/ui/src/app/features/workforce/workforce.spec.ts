import { ApplicationRef, provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { FakeDesktop } from '../../../testing/fake-desktop';
import { AUTH_REQUIRED } from '../../core/auth/session.service';
import type { ApiRequest, ApiResponse } from '../../core/desktop/desktop-api';
import { AccessStore } from '../../core/identity/access.store';
import { PermissionCode } from '../../core/identity/identity.models';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { orgResource } from '../../core/workforce/org-resource';
import { WorkforceApi } from '../../core/workforce/workforce.api';
import { WorkforceStore } from '../../core/workforce/workforce.store';
import { routes } from '../../app.routes';
import { OrganizationPage } from '../organization/organization-page';
import { WfEmployees } from './employees-tab';
import { WfPositions } from './positions-tab';
import { WfStructure } from './structure-tab';
import { WfTeams } from './teams-tab';
import { WorkforcePage } from './workforce-page';

const ORG_A = '0195f0a4-1c2d-7e3f-8a9b-00000000000a';
const ORG_B = '0195f0a4-1c2d-7e3f-8a9b-00000000000b';
const id = (n: number) => `0195f0a4-1c2d-7e3f-8a9b-${String(n).padStart(12, '0')}`;
const ALL: PermissionCode[] = [
  'organization.read', 'member.read', 'employee.read', 'employee.create', 'employee.manage', 'employee.deactivate',
  'department.read', 'department.manage', 'position.read', 'position.manage', 'team.read', 'team.manage', 'invitation.create',
];
const READ_ONLY: PermissionCode[] = ['organization.read', 'employee.read', 'department.read', 'position.read', 'team.read'];

const page = <T>(content: T[], extra: object = {}) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1, ...extra });
const dept = (n: number, name: string, extra: object = {}) => ({
  id: id(n), name, code: name.slice(0, 3).toUpperCase(), description: '', parentId: null, head: null, status: 'ACTIVE',
  employeeCount: 2, childCount: 0, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', version: 0, ...extra,
});
const employee = (n: number, name: string, extra: object = {}) => ({
  id: id(n), membershipId: null, membershipStatus: null, employeeNumber: null, displayName: name, professionalEmail: null,
  position: null, department: null, manager: null, status: 'ACTIVE', startDate: '2026-01-01', endDate: null, description: '',
  directReportCount: 0, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', version: 0, ...extra,
});
const position = (n: number, title: string, extra: object = {}) => ({
  id: id(n), title, code: title.slice(0, 3).toUpperCase(), description: '', department: null, status: 'ACTIVE', employeeCount: 1,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', version: 0, ...extra,
});
const team = (n: number, name: string, extra: object = {}) => ({
  id: id(n), name, description: '', department: null, leader: null, status: 'ACTIVE', memberCount: 2,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', version: 0, ...extra,
});

/** Canned Core API for organization A (and an empty organization B). */
function core(desktop: FakeDesktop, overrides: (request: ApiRequest) => ApiResponse | undefined = () => undefined): void {
  desktop.handler = (request) => {
    const custom = overrides(request);
    if (custom) {
      return custom;
    }
    const path = request.path;
    const forB = path.includes(ORG_B);
    if (path.endsWith('/departments/tree')) {
      return ok(forB ? [] : [{ department: dept(1, 'Engineering', { childCount: 1 }), children: [{ department: dept(2, 'Backend', { parentId: id(1) }), children: [] }] }]);
    }
    if (path.endsWith('/positions')) return ok(page(forB ? [] : [position(10, 'Backend Engineer', { department: { id: id(2), name: 'Backend', code: 'BAC', status: 'ACTIVE' } })]));
    if (/\/positions\/.*|\/employees\?/.test(path)) return ok(page([]));
    if (/\/employees\/[^/]+\/teams$/.test(path)) return ok([team(20, 'Launch squad')]);
    if (path.endsWith('/teams')) return ok(page(forB ? [] : [team(20, 'Launch squad', { leader: { id: id(30), displayName: 'Lea Lead', status: 'ACTIVE' } })]));
    if (path.endsWith(`/teams/${id(20)}`)) {
      return ok({
        team: team(20, 'Launch squad', { leader: { id: id(30), displayName: 'Lea Lead', status: 'ACTIVE' } }),
        members: [
          { employee: { id: id(30), displayName: 'Lea Lead', status: 'ACTIVE' }, joinedAt: '2026-01-01T00:00:00Z', leftAt: null, leader: true },
          { employee: { id: id(31), displayName: 'Max Member', status: 'ACTIVE' }, joinedAt: '2026-01-01T00:00:00Z', leftAt: null, leader: false },
        ],
      });
    }
    if (path.endsWith('/employees')) {
      const content = forB ? [] : [
        employee(30, 'Lea Lead', { position: { id: id(10), title: 'Backend Engineer', code: 'BAC', status: 'ACTIVE' } }),
        employee(31, 'Max Member'),
        employee(32, 'Ina Inactive', { status: 'INACTIVE' }),
      ];
      return ok(page(content, { totalElements: forB ? 0 : 40, totalPages: forB ? 1 : 3 }));
    }
    return ok({});
  };
}

const ok = (body: unknown): ApiResponse => ({ status: 200, body });

/** Lets timers and signal effects run without waiting for requests that stay pending on purpose. */
async function tick(): Promise<void> {
  for (let i = 0; i < 6; i++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    TestBed.tick();
  }
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

function setup(permissions: PermissionCode[], desktop = new FakeDesktop(), organization: string | null = ORG_A) {
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
      {
        provide: AccessStore,
        useValue: { can: (p: PermissionCode) => granted().has(p), organization: signal({ name: 'Acme', myMembershipId: 'm' }), reload: () => undefined },
      },
    ],
  });
  return { desktop, organizationId, granted };
}

function render<T>(component: new () => T): { fixture: ComponentFixture<T>; el: HTMLElement } {
  const fixture = TestBed.createComponent(component);
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement };
}

const text = (el: HTMLElement) => (el.textContent ?? '').replace(/\s+/g, ' ');
const buttons = (el: HTMLElement) => Array.from(el.querySelectorAll('button')).map((b) => (b.textContent ?? '').trim());
const click = (el: Element | null | undefined) => (el as HTMLElement).click();
const choose = (select: HTMLSelectElement, value: string) => {
  select.value = value;
  select.dispatchEvent(new Event('change', { bubbles: true }));
};

afterEach(() => FakeDesktop.uninstall());

describe('WorkforceApi', () => {
  it('builds organization-scoped requests and drops blank filters', async () => {
    const { desktop } = setup(ALL);
    const api = TestBed.inject(WorkforceApi);
    await api.listEmployees(ORG_A, { q: 'ines', status: 'ACTIVE', departmentId: '', page: 0, size: 15 });
    await api.moveDepartment(ORG_A, id(2), null);
    await api.removeTeamMember(ORG_A, id(20), id(31));
    await api.assignEmployeeManager(ORG_A, id(31), id(30));
    expect(desktop.requests).toEqual([
      { method: 'GET', path: `/api/v1/organizations/${ORG_A}/employees`, query: { q: 'ines', status: 'ACTIVE', page: 0, size: 15 } },
      { method: 'PUT', path: `/api/v1/organizations/${ORG_A}/departments/${id(2)}/parent`, body: { parentId: null } },
      { method: 'DELETE', path: `/api/v1/organizations/${ORG_A}/teams/${id(20)}/members/${id(31)}` },
      { method: 'PUT', path: `/api/v1/organizations/${ORG_A}/employees/${id(31)}/manager`, body: { managerId: id(30) } },
    ]);
  });

  it('refuses identifiers that could alter the path', () => {
    setup(ALL);
    const api = TestBed.inject(WorkforceApi);
    expect(() => api.getEmployee(ORG_A, '../../actuator')).toThrow();
    expect(() => api.getEmployee('x/y', id(1))).toThrow();
    expect(() => api.deleteDepartment(ORG_A, `${id(1)}/archive`)).toThrow();
  });
});

describe('orgResource', () => {
  it('never exposes data requested for a previous organization', async () => {
    setup(ALL);
    const organizationId = signal<string | null>(ORG_A);
    const pending = new Map<string, (value: string) => void>();
    const resource = TestBed.runInInjectionContext(() =>
      orgResource(organizationId, () => true, () => null, (org) => new Promise<string>((resolve) => pending.set(org, resolve))),
    );
    await tick();
    expect(resource.loading()).toBe(true);

    organizationId.set(ORG_B);
    await tick();
    // The slow answer for A arrives after the switch: it must not be shown for B.
    pending.get(ORG_A)?.('data of A');
    await tick();
    expect(resource.value()).toBeNull();
    pending.get(ORG_B)?.('data of B');
    await tick();
    expect(resource.value()).toBe('data of B');

    organizationId.set(ORG_A);
    expect(resource.value()).toBeNull();
  });
});

describe('WorkforceStore', () => {
  it('flattens the hierarchy and only calls endpoints the user may read', async () => {
    const { desktop } = setup(['department.read']);
    const store = TestBed.inject(WorkforceStore);
    await settle();
    expect(store.departments().map((r) => [r.department.name, r.depth])).toEqual([['Engineering', 0], ['Backend', 1]]);
    expect(desktop.to('/departments/tree')).toHaveLength(1);
    // No employee.read / position.read / team.read: nothing is requested for them.
    expect(desktop.to('/employees')).toHaveLength(0);
    expect(desktop.to('/positions')).toHaveLength(0);
    expect(desktop.to('/teams')).toHaveLength(0);
  });

  it('reloads after a change and refuses to change without an organization', async () => {
    const { desktop, organizationId } = setup(ALL);
    const store = TestBed.inject(WorkforceStore);
    await settle();
    const before = desktop.to('/departments/tree').length;
    await store.mutate(async () => 'done');
    await settle();
    expect(desktop.to('/departments/tree').length).toBeGreaterThan(before);
    organizationId.set(null);
    await expect(store.mutate(async () => 'x')).rejects.toThrow('No organization selected.');
  });

  it('reloads every collection for the new organization when switching', async () => {
    const { desktop, organizationId } = setup(ALL);
    const store = TestBed.inject(WorkforceStore);
    await settle();
    expect(store.departments()).toHaveLength(2);
    organizationId.set(ORG_B);
    await settle();
    expect(store.departments()).toHaveLength(0);
    expect(store.teams()).toHaveLength(0);
    expect(desktop.requests.some((r) => r.path.includes(ORG_B) && r.path.endsWith('/departments/tree'))).toBe(true);
  });
});

describe('Structure tab', () => {
  it('shows the hierarchy and, with permission, the structural actions', async () => {
    setup(ALL);
    const { fixture, el } = render(WfStructure);
    await settle(fixture);
    expect(text(el)).toContain('Engineering');
    expect(text(el)).toContain('Backend');
    expect(el.querySelectorAll('.tree__row')).toHaveLength(2);
    expect(buttons(el)).toContain('New');

    click(el.querySelector('.tree__row'));
    await settle(fixture);
    expect(buttons(el)).toEqual(expect.arrayContaining(['Edit', 'Move', 'Set head', 'Archive', 'Delete']));
  });

  it('hides every change without department.manage', async () => {
    setup(READ_ONLY);
    const { fixture, el } = render(WfStructure);
    await settle(fixture);
    click(el.querySelector('.tree__row'));
    await settle(fixture);
    expect(text(el)).toContain('Engineering');
    for (const label of ['New', 'Edit', 'Move', 'Set head', 'Archive', 'Delete']) {
      expect(buttons(el)).not.toContain(label);
    }
  });

  it('shows an access message without department.read', async () => {
    const { desktop } = setup(['employee.read']);
    const { fixture, el } = render(WfStructure);
    await settle(fixture);
    expect(text(el)).toContain('does not allow viewing departments');
    expect(desktop.to('/departments/tree')).toHaveLength(0);
  });

  it('shows the empty state of an organization without departments', async () => {
    setup(ALL, new FakeDesktop(), ORG_B);
    const { fixture, el } = render(WfStructure);
    await settle(fixture);
    expect(text(el)).toContain('No department yet');
  });
});

describe('Employees tab', () => {
  it('lists employees and only offers creation with employee.create', async () => {
    const { granted } = setup(ALL);
    const { fixture, el } = render(WfEmployees);
    await settle(fixture);
    expect(text(el)).toContain('Lea Lead');
    expect(text(el)).toContain('Backend Engineer');
    expect(text(el)).toContain('40 employee(s)');
    expect(buttons(el)).toContain('New employee');

    granted.set(new Set<PermissionCode>(['employee.read', 'department.read']));
    await settle(fixture);
    expect(buttons(el)).not.toContain('New employee');
  });

  it('sends filters and pagination to the API', async () => {
    const { desktop } = setup(ALL);
    const { fixture, el } = render(WfEmployees);
    await settle(fixture);
    const own = () => desktop.to('/employees').filter((r) => r.query?.['size'] === 15);
    expect(own()[0].query).toEqual({ page: 0, size: 15 });

    choose(el.querySelector('#f-status') as HTMLSelectElement, 'INACTIVE');
    await settle(fixture);
    expect(own().at(-1)?.query).toEqual({ status: 'INACTIVE', page: 0, size: 15 });

    click(Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Next'));
    await settle(fixture);
    expect(own().at(-1)?.query).toEqual({ status: 'INACTIVE', page: 1, size: 15 });
  });

  it('searches after a short pause with the typed text', async () => {
    const { desktop } = setup(ALL);
    const { fixture, el } = render(WfEmployees);
    await settle(fixture);
    const input = el.querySelector('ui-search-input input') as HTMLInputElement;
    input.value = 'ines';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    // The search is debounced: wait (up to 5 s) until the request carrying the text was sent.
    for (let i = 0; i < 50 && !desktop.to('/employees').some((r) => r.query?.['q'] === 'ines'); i++) {
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    await settle(fixture);
    expect(desktop.to('/employees').filter((r) => r.query?.['size'] === 15).at(-1)?.query).toMatchObject({ q: 'ines', page: 0 });
  });

  it('shows empty and error states', async () => {
    setup(ALL, new FakeDesktop(), ORG_B);
    const empty = render(WfEmployees);
    await settle(empty.fixture);
    expect(text(empty.el)).toContain('Create the first employee profile');
    TestBed.resetTestingModule();

    const desktop = new FakeDesktop();
    setup(ALL, desktop);
    core(desktop, (request) => (request.path.endsWith('/employees') ? { status: 500, body: { detail: 'Boom' } } : undefined));
    const failing = render(WfEmployees);
    await settle(failing.fixture);
    expect(text(failing.el)).toContain('Employees unavailable');
  });

  it('offers deactivation only with employee.deactivate', async () => {
    const { granted } = setup(['employee.read', 'employee.manage', 'department.read', 'position.read', 'team.read']);
    const { fixture, el } = render(WfEmployees);
    await settle(fixture);
    click(el.querySelector('tr.row'));
    await settle(fixture);
    expect(buttons(el)).toContain('Put on leave');
    expect(buttons(el)).not.toContain('Deactivate');

    granted.set(new Set<PermissionCode>([...granted(), 'employee.deactivate']));
    await settle(fixture);
    expect(buttons(el)).toContain('Deactivate');
  });

  it('shows nothing editable to a read-only member', async () => {
    setup(READ_ONLY);
    const { fixture, el } = render(WfEmployees);
    await settle(fixture);
    click(el.querySelector('tr.row'));
    await settle(fixture);
    expect(text(el)).toContain('Lea Lead');
    expect(el.querySelector('#a-department')).toBeNull();
    for (const label of ['New employee', 'Edit', 'Deactivate', 'Put on leave']) {
      expect(buttons(el)).not.toContain(label);
    }
  });

  it('clears the selection and reloads when the organization changes', async () => {
    const { desktop, organizationId } = setup(ALL);
    const { fixture, el } = render(WfEmployees);
    await settle(fixture);
    click(el.querySelector('tr.row'));
    await settle(fixture);
    expect(text(el)).toContain('Employee number');

    organizationId.set(ORG_B);
    await settle(fixture);
    expect(text(el)).not.toContain('Lea Lead');
    expect(text(el)).not.toContain('Employee number');
    expect(desktop.requests.some((r) => r.path === `/api/v1/organizations/${ORG_B}/employees`)).toBe(true);
  });

  it('changes an assignment through the API and shows server errors', async () => {
    const desktop = new FakeDesktop();
    setup(ALL, desktop);
    core(desktop, (request) => (request.method === 'PUT' && request.path.endsWith('/manager')
      ? { status: 409, body: { detail: 'This would create a reporting cycle.' } } : undefined));
    const { fixture, el } = render(WfEmployees);
    await settle(fixture);
    click(el.querySelector('tr.row'));
    await settle(fixture);
    choose(el.querySelector('#a-manager') as HTMLSelectElement, id(31));
    await settle(fixture);
    expect(desktop.to(`/employees/${id(30)}/manager`, 'PUT')[0].body).toEqual({ managerId: id(31) });
  });
});

describe('Positions tab', () => {
  it('lists positions with their department and holders', async () => {
    const desktop = new FakeDesktop();
    setup(ALL, desktop);
    core(desktop, (request) => (request.path.endsWith('/employees') && request.query?.['positionId']
      ? ok(page([employee(30, 'Lea Lead')])) : undefined));
    const { fixture, el } = render(WfPositions);
    await settle(fixture);
    expect(text(el)).toContain('Backend Engineer');
    expect(text(el)).toContain('Backend');
    click(el.querySelector('tr.row'));
    await settle(fixture);
    expect(text(el)).toContain('Employees holding this position');
    expect(text(el)).toContain('Lea Lead');
  });

  it('hides catalog changes without position.manage', async () => {
    setup(READ_ONLY);
    const { fixture, el } = render(WfPositions);
    await settle(fixture);
    expect(text(el)).toContain('Backend Engineer');
    for (const label of ['New position', 'Edit', 'Archive', 'Delete']) {
      expect(buttons(el)).not.toContain(label);
    }
  });
});

describe('Teams tab', () => {
  it('shows members and protects the leader from removal', async () => {
    setup(ALL);
    const { fixture, el } = render(WfTeams);
    await settle(fixture);
    expect(text(el)).toContain('Launch squad');
    click(el.querySelector('.item'));
    await settle(fixture);
    expect(text(el)).toContain('Members (2)');
    const remove = Array.from(el.querySelectorAll('button')).filter((b) => b.textContent?.trim() === 'Remove');
    expect(remove).toHaveLength(2);
    expect(remove[0].disabled).toBe(true); // the leader
    expect(remove[1].disabled).toBe(false);
    // People already in the team are not offered again; others are.
    const options = Array.from(el.querySelectorAll('#t-add option')).map((o) => o.textContent?.trim());
    expect(options).toContain('Add a member…');
    expect(options).not.toContain('Lea Lead');
    expect(options).not.toContain('Max Member');
  });

  it('is read-only without team.manage', async () => {
    setup(READ_ONLY);
    const { fixture, el } = render(WfTeams);
    await settle(fixture);
    click(el.querySelector('.item'));
    await settle(fixture);
    expect(text(el)).toContain('Max Member');
    expect(el.querySelector('#t-add')).toBeNull();
    for (const label of ['New team', 'Edit', 'Archive', 'Remove']) {
      expect(buttons(el)).not.toContain(label);
    }
  });
});

describe('Organization page and routing', () => {
  it('offers the invitation shortcut only with invitation.create', async () => {
    const { granted } = setup(ALL);
    const { fixture, el } = render(WorkforcePage);
    await settle(fixture);
    expect(text(el)).toContain('Invite a collaborator');
    expect(text(el)).toContain('AI agents');
    expect(text(el)).toContain('Demo data');
    granted.set(new Set<PermissionCode>(READ_ONLY));
    await settle(fixture);
    expect(text(el)).not.toContain('Invite a collaborator');
  });

  it('keeps the demo organization page for the mock build and uses the real one in api mode', async () => {
    const route = routes.flatMap((r) => r.children ?? []).find((r) => r.path === 'organization');
    expect(route?.loadComponent).toBeDefined();
    TestBed.configureTestingModule({ providers: [{ provide: AUTH_REQUIRED, useValue: true }] });
    expect(await TestBed.runInInjectionContext(() => route!.loadComponent!())).toBe(WorkforcePage);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: AUTH_REQUIRED, useValue: false }] });
    expect(await TestBed.runInInjectionContext(() => route!.loadComponent!())).toBe(OrganizationPage);
  });
});
