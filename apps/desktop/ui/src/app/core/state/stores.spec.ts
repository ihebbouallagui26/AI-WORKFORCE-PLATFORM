import { TestBed } from '@angular/core/testing';
import { configureMockWorkspace, settle } from '../../../testing/mock-workspace';
import { AgentStore } from './agent.store';
import { CommunicationStore } from './communication.store';
import { DirectoryStore } from './directory.store';
import { OrganizationStore } from './organization.store';
import { ProjectStore } from './project.store';
import { SupervisionStore } from './supervision.store';
import { WorkspaceStore } from './workspace.store';

describe('state stores (mock data layer)', () => {
  beforeEach(async () => {
    localStorage.clear();
    configureMockWorkspace();
    // Instantiate stores so their resources start loading.
    TestBed.inject(OrganizationStore);
    TestBed.inject(AgentStore);
    TestBed.inject(ProjectStore);
    TestBed.inject(SupervisionStore);
    TestBed.inject(CommunicationStore);
    await settle();
  });

  it('selects the first organization and resolves the current member', () => {
    const workspace = TestBed.inject(WorkspaceStore);
    expect(workspace.currentOrganization()?.name).toBe('Northwind Labs');
    expect(workspace.currentMemberRef()).toEqual({ kind: 'human', id: 'emp-leila' });
  });

  it('reloads organization-scoped data when switching organization', async () => {
    const workspace = TestBed.inject(WorkspaceStore);
    const agents = TestBed.inject(AgentStore);
    expect(agents.agents().length).toBe(9);

    workspace.switchOrganization('org-helix');
    await settle();

    expect(agents.agents().map((a) => a.identity.fullName).sort()).toEqual(['Muse', 'Tempo']);
    expect(workspace.currentMemberRef()).toEqual({ kind: 'human', id: 'hx-emp-leila' });
  });

  it('builds a department tree with nested departments', () => {
    const organization = TestBed.inject(OrganizationStore);
    const [root] = organization.departmentTree();
    expect(root.department.code).toBe('EXEC');
    const engineering = root.children.find((c) => c.department.code === 'ENG');
    expect(engineering?.children.map((c) => c.department.code)).toEqual(['QA']);
  });

  it('resolves humans and agents through the directory', () => {
    const directory = TestBed.inject(DirectoryStore);
    expect(directory.resolve({ kind: 'agent', id: 'agt-atlas' })).toMatchObject({
      name: 'Atlas',
      kind: 'agent',
      roleTitle: 'Project Manager',
    });
    expect(directory.resolve({ kind: 'human', id: 'emp-marcus' }).departmentName).toBe('Engineering');
    expect(directory.resolve({ kind: 'agent', id: 'missing' }).name).toBe('Unknown agent');
  });

  it('creates an agent that stays offline until an orchestrator exists', async () => {
    const agents = TestBed.inject(AgentStore);
    const created = await agents.createAgent({
      identity: {
        fullName: 'Pixel',
        departmentId: 'dep-design',
        roleId: 'rol-uiux',
        roleDescription: 'Designs interfaces for the client portal.',
        responsibilities: ['Wireframes'],
        skills: ['Figma'],
      },
      providers: [{ providerId: 'prv-anthropic', modelId: 'mdl-claude-sonnet', priority: 'primary' }],
      systemInstructions: 'Follow the design system and ask before publishing.',
      permissionLevel: 'contributor',
      autonomy: { level: 'supervised', requireApprovalFor: ['document-publication'], maxConcurrentTasks: 2 },
      assignedProjectIds: [],
    });
    expect(created.status).toBe('offline');
    expect(agents.agentById().get(created.id)?.identity.fullName).toBe('Pixel');
  });

  it('moves a task and derives project progress from task completion', async () => {
    const projects = TestBed.inject(ProjectStore);
    const before = projects.progressOf('prj-orbit');
    const task = projects.tasks().find((t) => t.key === 'ORB-103');
    expect(task?.status).toBe('todo');

    await projects.moveTask(task!.id, 'done');

    expect(projects.tasks().find((t) => t.id === task!.id)?.status).toBe('done');
    expect(projects.progressOf('prj-orbit').done).toBe(before.done + 1);
  });

  it('creates tasks with the next sequential key', async () => {
    const projects = TestBed.inject(ProjectStore);
    const created = await projects.createTask({
      projectId: 'prj-payments',
      title: 'Rollback plan',
      description: '',
      status: 'backlog',
      priority: 'medium',
      assigneeRef: null,
      dueDate: null,
      labels: [],
    });
    expect(created.key).toBe('PAY-47');
    expect(created.reporterRef).toEqual({ kind: 'human', id: 'emp-leila' });
  });

  it('records an approval decision and removes it from the pending queue', async () => {
    const supervision = TestBed.inject(SupervisionStore);
    const pendingBefore = supervision.pendingApprovals().length;
    expect(supervision.pendingApprovals()[0].risk).toBe('critical');

    const decided = await supervision.decide('apr-1', 'rejected', '  Not before the go/no-go  ');

    expect(decided).toMatchObject({ status: 'rejected', decisionNote: 'Not before the go/no-go', decidedByUserId: 'usr-leila' });
    expect(supervision.pendingApprovals().length).toBe(pendingBefore - 1);
    expect(supervision.decidedApprovals()[0].id).toBe('apr-1');

    // Decisions are final: a second decision is refused.
    await expect(supervision.decide('apr-1', 'approved', null)).rejects.toThrow('already been decided');
    expect(supervision.approvals().find((a) => a.id === 'apr-1')?.status).toBe('rejected');
  });

  it('refuses to decide an approval that belongs to another organization', async () => {
    const supervision = TestBed.inject(SupervisionStore);
    await expect(supervision.decide('hx-apr-1', 'approved', null)).rejects.toThrow('current organization');
  });

  it('refuses to send a message to a conversation of another organization', async () => {
    const communication = TestBed.inject(CommunicationStore);
    await expect(communication.send('hx-cnv-rebrand', 'hello')).rejects.toThrow('current organization');
  });

  it('stores sent messages locally and tracks unread counts', async () => {
    const communication = TestBed.inject(CommunicationStore);
    const unreadBefore = communication.summaries().find((s) => s.conversation.id === 'cnv-atlas')?.unread ?? 0;
    expect(unreadBefore).toBeGreaterThan(0);

    await communication.markRead('cnv-atlas');
    expect(communication.summaries().find((s) => s.conversation.id === 'cnv-atlas')?.unread).toBe(0);

    const message = await communication.send('cnv-atlas', '  Thanks, reviewing now.  ');
    expect(message.delivery).toBe('local');
    expect(message.body).toBe('Thanks, reviewing now.');
    expect(communication.messagesOf('cnv-atlas').at(-1)?.id).toBe(message.id);

    await expect(communication.send('cnv-atlas', '   ')).rejects.toThrow('Message is empty.');
  });
});
