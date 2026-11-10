import { agent, FixtureClock, human } from './fixture-helpers';
import { OrganizationSeed } from './mock-seed';

const ORG = 'org-helix';

/** Secondary organization: a small design studio, used to demonstrate workspace switching. */
export function createHelixSeed(clock: FixtureClock): OrganizationSeed {
  return {
    organization: {
      id: ORG,
      name: 'Helix Studio',
      slug: 'helix',
      industry: 'Brand & Product Design',
      description: 'Independent design studio working with a small hybrid team.',
      timezone: 'Europe/Lisbon',
      plan: 'business',
      deployment: 'cloud',
      createdAt: clock.daysAgo(150),
    },
    departments: [
      { id: 'hx-dep-studio', organizationId: ORG, name: 'Studio', code: 'STU', description: 'Leadership and studio operations.', managerRef: human('hx-emp-leila'), parentId: null, createdAt: clock.daysAgo(150) },
      { id: 'hx-dep-design', organizationId: ORG, name: 'Design', code: 'DSN', description: 'Brand identity and product design.', managerRef: human('hx-emp-rui'), parentId: 'hx-dep-studio', createdAt: clock.daysAgo(150) },
      { id: 'hx-dep-clients', organizationId: ORG, name: 'Client Services', code: 'CS', description: 'Account management and project delivery.', managerRef: agent('hx-agt-tempo'), parentId: 'hx-dep-studio', createdAt: clock.daysAgo(120) },
    ],
    roles: [
      { id: 'hx-rol-director', organizationId: ORG, departmentId: 'hx-dep-studio', title: 'Studio Director', level: 'head', description: 'Runs the studio.' },
      { id: 'hx-rol-brand', organizationId: ORG, departmentId: 'hx-dep-design', title: 'Brand Designer', level: 'senior', description: 'Identity systems and visual language.' },
      { id: 'hx-rol-uiux', organizationId: ORG, departmentId: 'hx-dep-design', title: 'UI/UX Designer', level: 'mid', description: 'Product interfaces.' },
      { id: 'hx-rol-pm', organizationId: ORG, departmentId: 'hx-dep-clients', title: 'Project Manager', level: 'lead', description: 'Client delivery.' },
    ],
    teams: [
      { id: 'hx-team-brand', organizationId: ORG, departmentId: 'hx-dep-design', name: 'Brand Team', leadRef: human('hx-emp-rui'), memberRefs: [agent('hx-agt-muse')] },
    ],
    employees: [
      { id: 'hx-emp-leila', organizationId: ORG, firstName: 'Leila', lastName: 'Ben Salem', email: 'leila@helix.example', departmentId: 'hx-dep-studio', roleId: 'hx-rol-director', managerRef: null, status: 'online', location: 'Paris', joinedAt: clock.daysAgo(150), avatarTone: 6 },
      { id: 'hx-emp-rui', organizationId: ORG, firstName: 'Rui', lastName: 'Carvalho', email: 'rui@helix.example', departmentId: 'hx-dep-design', roleId: 'hx-rol-brand', managerRef: human('hx-emp-leila'), status: 'online', location: 'Porto', joinedAt: clock.daysAgo(140), avatarTone: 2 },
      { id: 'hx-emp-maya', organizationId: ORG, firstName: 'Maya', lastName: 'Lind', email: 'maya@helix.example', departmentId: 'hx-dep-design', roleId: 'hx-rol-uiux', managerRef: human('hx-emp-rui'), status: 'offline', location: 'Lisbon', joinedAt: clock.daysAgo(80), avatarTone: 4 },
    ],
    agents: [
      {
        id: 'hx-agt-tempo', organizationId: ORG,
        identity: { fullName: 'Tempo', departmentId: 'hx-dep-clients', roleId: 'hx-rol-pm', roleDescription: 'Coordinates client projects and timelines.', responsibilities: ['Plan client milestones', 'Prepare weekly client updates'], skills: ['Planning', 'Client communication'] },
        providers: [{ providerId: 'prv-anthropic', modelId: 'mdl-claude-sonnet', priority: 'primary' }],
        systemInstructions: 'You are Tempo, project manager at Helix Studio. Never contact clients without approval.',
        permissionLevel: 'contributor',
        autonomy: { level: 'supervised', requireApprovalFor: ['external-communication'], maxConcurrentTasks: 3 },
        status: 'working', assignedProjectIds: ['hx-prj-rebrand'], lastActiveAt: clock.minutesAgo(4), createdAt: clock.daysAgo(100), avatarTone: 3,
      },
      {
        id: 'hx-agt-muse', organizationId: ORG,
        identity: { fullName: 'Muse', departmentId: 'hx-dep-design', roleId: 'hx-rol-brand', roleDescription: 'Explores visual directions and prepares brand guidelines.', responsibilities: ['Generate moodboards', 'Draft brand guidelines'], skills: ['Visual exploration', 'Typography', 'Brand guidelines'] },
        providers: [{ providerId: 'prv-openai', modelId: 'mdl-gpt-4o', priority: 'primary' }],
        systemInstructions: 'You are Muse, brand designer at Helix Studio.',
        permissionLevel: 'contributor',
        autonomy: { level: 'supervised', requireApprovalFor: ['document-publication'], maxConcurrentTasks: 2 },
        status: 'idle', assignedProjectIds: ['hx-prj-rebrand'], lastActiveAt: clock.hoursAgo(3), createdAt: clock.daysAgo(90), avatarTone: 7,
      },
    ],
    projects: [
      {
        id: 'hx-prj-rebrand', organizationId: ORG, code: 'ALT', name: 'Altitude Coffee Rebrand',
        description: 'Full identity refresh for a specialty coffee chain.',
        category: 'design', status: 'active', departmentId: 'hx-dep-design', managerRef: agent('hx-agt-tempo'),
        members: [
          { member: agent('hx-agt-tempo'), projectRole: 'Project Manager', allocation: 50 },
          { member: human('hx-emp-rui'), projectRole: 'Lead Designer', allocation: 70 },
          { member: agent('hx-agt-muse'), projectRole: 'Exploration', allocation: 80 },
          { member: human('hx-emp-maya'), projectRole: 'Packaging', allocation: 40 },
        ],
        startDate: clock.daysAgo(18), dueDate: clock.daysFromNow(30),
      },
    ],
    tasks: [
      { id: 'tsk-hx-1', projectId: 'hx-prj-rebrand', key: 'ALT-1', title: 'Moodboards, three directions', description: '', status: 'done', priority: 'high', assigneeRef: agent('hx-agt-muse'), reporterRef: human('hx-emp-rui'), dueDate: clock.daysAgo(6), labels: ['exploration'], createdAt: clock.daysAgo(17), updatedAt: clock.daysAgo(6) },
      { id: 'tsk-hx-2', projectId: 'hx-prj-rebrand', key: 'ALT-2', title: 'Logo refinement', description: '', status: 'in-progress', priority: 'high', assigneeRef: human('hx-emp-rui'), reporterRef: agent('hx-agt-tempo'), dueDate: clock.daysFromNow(5), labels: ['identity'], createdAt: clock.daysAgo(6), updatedAt: clock.hoursAgo(4) },
      { id: 'tsk-hx-3', projectId: 'hx-prj-rebrand', key: 'ALT-3', title: 'Packaging mockups', description: '', status: 'todo', priority: 'medium', assigneeRef: human('hx-emp-maya'), reporterRef: agent('hx-agt-tempo'), dueDate: clock.daysFromNow(14), labels: ['packaging'], createdAt: clock.daysAgo(4), updatedAt: clock.daysAgo(4) },
      { id: 'tsk-hx-4', projectId: 'hx-prj-rebrand', key: 'ALT-4', title: 'Client presentation deck', description: '', status: 'backlog', priority: 'medium', assigneeRef: null, reporterRef: human('hx-emp-leila'), dueDate: null, labels: ['client'], createdAt: clock.daysAgo(2), updatedAt: clock.daysAgo(2) },
    ],
    conversations: [
      { id: 'hx-cnv-rebrand', organizationId: ORG, kind: 'project', name: 'altitude-rebrand', topic: 'Altitude Coffee delivery', participantRefs: [human('hx-emp-leila'), human('hx-emp-rui'), agent('hx-agt-tempo'), agent('hx-agt-muse')], projectId: 'hx-prj-rebrand', departmentId: null, createdAt: clock.daysAgo(18), lastReadAt: clock.hoursAgo(1) },
    ],
    messages: [
      { id: 'hx-msg-1', conversationId: 'hx-cnv-rebrand', authorRef: agent('hx-agt-tempo'), body: 'Client feedback received on direction B. They want a warmer palette.', sentAt: clock.hoursAgo(2), delivery: 'sent' },
      { id: 'hx-msg-2', conversationId: 'hx-cnv-rebrand', authorRef: human('hx-emp-rui'), body: 'Got it, I will iterate on the logo with warmer tones today.', sentAt: clock.minutesAgo(40), delivery: 'sent' },
    ],
    sharedFiles: [],
    approvals: [
      { id: 'hx-apr-1', organizationId: ORG, agentId: 'hx-agt-tempo', action: 'Send weekly update to Altitude Coffee', operation: 'external-communication', description: 'Emails the weekly progress summary to the client contact.', projectId: 'hx-prj-rebrand', requestedAt: clock.minutesAgo(30), risk: 'low', status: 'pending', decidedAt: null, decidedByUserId: null, decisionNote: null },
    ],
    executions: [
      { id: 'hx-exe-1', organizationId: ORG, agentId: 'hx-agt-tempo', taskId: null, projectId: 'hx-prj-rebrand', title: 'Prepare weekly client update', status: 'awaiting-approval', startedAt: clock.minutesAgo(32), finishedAt: null, resultSummary: 'Draft ready; sending requires approval.' },
      { id: 'hx-exe-2', organizationId: ORG, agentId: 'hx-agt-muse', taskId: 'tsk-hx-1', projectId: 'hx-prj-rebrand', title: 'Moodboard generation', status: 'succeeded', startedAt: clock.daysAgo(7), finishedAt: clock.daysAgo(6.9), resultSummary: 'Three directions with 12 references each.' },
    ],
    activity: [
      { id: 'hx-act-1', organizationId: ORG, type: 'approval-requested', actorRef: agent('hx-agt-tempo'), title: 'Tempo requested approval', detail: 'Send weekly update to Altitude Coffee', projectId: 'hx-prj-rebrand', occurredAt: clock.minutesAgo(30), read: false },
    ],
    reports: [],
    meetings: [
      { id: 'hx-mtg-1', organizationId: ORG, title: 'Altitude logo review', startsAt: clock.daysFromNow(1), durationMinutes: 45, participantRefs: [human('hx-emp-leila'), human('hx-emp-rui'), agent('hx-agt-tempo')], projectId: 'hx-prj-rebrand' },
    ],
  };
}
