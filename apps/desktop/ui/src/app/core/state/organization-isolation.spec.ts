import { TestBed } from '@angular/core/testing';
import { Observable, Subject, throwError } from 'rxjs';
import { configureMockWorkspace } from '../../../testing/mock-workspace';
import { AgentApi } from '../data/api/agent.api';
import { Agent, AgentDraft, AIModel, AIProvider, Id } from '../models';
import { AgentStore } from './agent.store';
import { WorkspaceStore } from './workspace.store';

/** Minimal agent used by the stubbed API. */
function agentFixture(id: Id, organizationId: Id, name: string): Agent {
  return {
    id,
    organizationId,
    identity: { fullName: name, departmentId: 'd', roleId: 'r', roleDescription: '', responsibilities: [], skills: [] },
    providers: [],
    systemInstructions: '',
    permissionLevel: 'contributor',
    autonomy: { level: 'supervised', requireApprovalFor: [], maxConcurrentTasks: 1 },
    status: 'idle',
    assignedProjectIds: [],
    lastActiveAt: null,
    createdAt: '2026-01-01T00:00:00.000Z',
    avatarTone: 1,
  };
}

const DRAFT: AgentDraft = {
  identity: { fullName: 'Pixel', departmentId: 'd', roleId: 'r', roleDescription: '', responsibilities: [], skills: [] },
  providers: [],
  systemInstructions: '',
  permissionLevel: 'contributor',
  autonomy: { level: 'supervised', requireApprovalFor: [], maxConcurrentTasks: 1 },
  assignedProjectIds: [],
};

/** Agent API whose responses are released manually, to reproduce request orderings. */
class ControlledAgentApi extends AgentApi {
  readonly lists = new Map<Id, Subject<Agent[]>>();
  readonly creates: Subject<Agent>[] = [];
  readonly listCalls: Id[] = [];
  failListsFor = new Set<Id>();

  listAgents(organizationId: Id): Observable<Agent[]> {
    this.listCalls.push(organizationId);
    if (this.failListsFor.has(organizationId)) {
      return throwError(() => new Error(`Backend unavailable for ${organizationId}`));
    }
    const subject = new Subject<Agent[]>();
    this.lists.set(organizationId, subject);
    return subject;
  }
  createAgent(): Observable<Agent> {
    const subject = new Subject<Agent>();
    this.creates.push(subject);
    return subject;
  }
  updateAgent(): Observable<Agent> {
    return throwError(() => new Error('not used'));
  }
  listProviders(): Observable<AIProvider[]> {
    const s = new Subject<AIProvider[]>();
    queueMicrotask(() => { s.next([]); s.complete(); });
    return s;
  }
  listModels(): Observable<AIModel[]> {
    const s = new Subject<AIModel[]>();
    queueMicrotask(() => { s.next([]); s.complete(); });
    return s;
  }
}

/**
 * Runs effects and lets timers fire. `whenStable()` cannot be used here: the controlled
 * requests are intentionally left pending, so the application never becomes stable.
 */
const flush = async () => {
  for (let i = 0; i < 6; i++) {
    TestBed.tick();
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
};

describe('organization isolation (agent store)', () => {
  let api: ControlledAgentApi;
  let workspace: WorkspaceStore;
  let store: AgentStore;

  beforeEach(async () => {
    localStorage.clear();
    configureMockWorkspace();
    api = new ControlledAgentApi();
    TestBed.overrideProvider(AgentApi, { useValue: api });
    workspace = TestBed.inject(WorkspaceStore);
    store = TestBed.inject(AgentStore);
    await flush();
    // Northwind loads first.
    api.lists.get('org-northwind')?.next([agentFixture('n1', 'org-northwind', 'Atlas')]);
    await flush();
  });

  it('shows only the selected organization once its list has loaded', async () => {
    expect(store.agents().map((a) => a.identity.fullName)).toEqual(['Atlas']);
    workspace.switchOrganization('org-helix');
    await flush();
    api.lists.get('org-helix')?.next([agentFixture('h1', 'org-helix', 'Tempo')]);
    await flush();
    expect(store.agents().map((a) => a.identity.fullName)).toEqual(['Tempo']);
  });

  it('ignores a slow response of the previous organization', async () => {
    const northwindList = api.lists.get('org-northwind');
    workspace.switchOrganization('org-helix');
    await flush();
    // The old request is answered after the switch: it must not be displayed.
    northwindList?.next([agentFixture('n-late', 'org-northwind', 'LateNorthwind')]);
    await flush();
    expect(store.agents().map((a) => a.identity.fullName)).not.toContain('LateNorthwind');

    api.lists.get('org-helix')?.next([agentFixture('h1', 'org-helix', 'Tempo')]);
    await flush();
    expect(store.agents().map((a) => a.identity.fullName)).toEqual(['Tempo']);
  });

  it('does not insert an agent created in the previous organization (answer before the new list)', async () => {
    const pending = store.createAgent(DRAFT);
    workspace.switchOrganization('org-helix');
    await flush();

    api.creates[0].next(agentFixture('created', 'org-northwind', 'Pixel'));
    await pending.catch(() => undefined);
    await flush();
    api.lists.get('org-helix')?.next([agentFixture('h1', 'org-helix', 'Tempo')]);
    await flush();

    expect(store.agents().map((a) => a.identity.fullName)).toEqual(['Tempo']);
  });

  it('does not insert an agent created in the previous organization (answer after the new list)', async () => {
    const pending = store.createAgent(DRAFT);
    workspace.switchOrganization('org-helix');
    await flush();
    api.lists.get('org-helix')?.next([agentFixture('h1', 'org-helix', 'Tempo')]);
    await flush();

    api.creates[0].next(agentFixture('created', 'org-northwind', 'Pixel'));
    await pending.catch(() => undefined);
    await flush();

    expect(store.agents().map((a) => a.identity.fullName)).toEqual(['Tempo']);
  });

  it('presents an empty list and an error — never stale data — when the new organization fails to load', async () => {
    api.failListsFor.add('org-helix');
    workspace.switchOrganization('org-helix');
    await flush();

    expect(store.agents()).toEqual([]);
    expect(store.error()).toBe('Backend unavailable for org-helix');
  });
});
