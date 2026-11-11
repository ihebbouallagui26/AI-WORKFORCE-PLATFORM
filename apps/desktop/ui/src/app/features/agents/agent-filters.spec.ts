import { Agent } from '../../core/models';
import { EMPTY_AGENT_FILTERS, filterAgents, hasActiveFilters } from './agent-filters';

function agent(id: string, name: string, overrides: Partial<Agent> = {}, skills: string[] = []): Agent {
  return {
    id,
    organizationId: 'org',
    identity: {
      fullName: name,
      departmentId: 'dep-eng',
      roleId: 'rol-frontend',
      roleDescription: '',
      responsibilities: [],
      skills,
    },
    providers: [{ providerId: 'prv-a', modelId: 'mdl-a', priority: 'primary' }],
    systemInstructions: '',
    permissionLevel: 'contributor',
    autonomy: { level: 'supervised', requireApprovalFor: [], maxConcurrentTasks: 1 },
    status: 'online',
    assignedProjectIds: [],
    lastActiveAt: null,
    createdAt: '2026-01-01T00:00:00.000Z',
    avatarTone: 1,
    ...overrides,
  };
}

const context = {
  roleTitle: (id: string) => ({ 'rol-frontend': 'Frontend Engineer', 'rol-qa': 'QA Engineer' })[id] ?? '',
  departmentName: (id: string) => ({ 'dep-eng': 'Engineering', 'dep-mkt': 'Marketing' })[id] ?? '',
};

describe('filterAgents', () => {
  const agents: Agent[] = [
    agent('1', 'Nova', { lastActiveAt: '2026-01-03T00:00:00.000Z' }, ['Angular', 'Accessibility']),
    agent('2', 'Echo', {
      identity: { fullName: 'Echo', departmentId: 'dep-mkt', roleId: 'rol-qa', roleDescription: '', responsibilities: [], skills: ['SEO'] },
      providers: [{ providerId: 'prv-b', modelId: 'mdl-b', priority: 'primary' }],
      status: 'error',
      lastActiveAt: '2026-01-05T00:00:00.000Z',
    }),
    agent('3', 'Atlas', { status: 'working' }, ['Planning']),
  ];

  it('returns every agent sorted by name without filters', () => {
    expect(filterAgents(agents, EMPTY_AGENT_FILTERS, context).map((a) => a.identity.fullName)).toEqual([
      'Atlas',
      'Echo',
      'Nova',
    ]);
  });

  it('searches by skill, role title and department name', () => {
    const search = (query: string) =>
      filterAgents(agents, { ...EMPTY_AGENT_FILTERS, query }, context).map((a) => a.id);
    expect(search('accessib')).toEqual(['1']);
    expect(search('qa engineer')).toEqual(['2']);
    expect(search('marketing')).toEqual(['2']);
  });

  it('combines department, provider and status filters', () => {
    const result = filterAgents(
      agents,
      { ...EMPTY_AGENT_FILTERS, departmentId: 'dep-eng', providerId: 'prv-a', status: 'working' },
      context,
    );
    expect(result.map((a) => a.id)).toEqual(['3']);
  });

  it('sorts by status severity and by last activity', () => {
    expect(filterAgents(agents, EMPTY_AGENT_FILTERS, context, 'status').map((a) => a.id)).toEqual(['2', '3', '1']);
    expect(filterAgents(agents, EMPTY_AGENT_FILTERS, context, 'last-active').map((a) => a.id)).toEqual(['2', '1', '3']);
  });

  it('detects active filters', () => {
    expect(hasActiveFilters(EMPTY_AGENT_FILTERS)).toBe(false);
    expect(hasActiveFilters({ ...EMPTY_AGENT_FILTERS, query: '  ' })).toBe(false);
    expect(hasActiveFilters({ ...EMPTY_AGENT_FILTERS, status: 'idle' })).toBe(true);
  });
});
