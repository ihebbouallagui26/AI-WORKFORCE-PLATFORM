import { Agent, AgentStatus, Id } from '../../core/models';

export interface AgentFilterCriteria {
  readonly query: string;
  readonly departmentId: Id | null;
  readonly roleId: Id | null;
  readonly providerId: Id | null;
  readonly status: AgentStatus | null;
}

export type AgentSort = 'name' | 'last-active' | 'status';

/** Lookup data needed to search on display names rather than identifiers. */
export interface AgentSearchContext {
  readonly roleTitle: (roleId: Id) => string;
  readonly departmentName: (departmentId: Id) => string;
}

export const EMPTY_AGENT_FILTERS: AgentFilterCriteria = {
  query: '',
  departmentId: null,
  roleId: null,
  providerId: null,
  status: null,
};

const STATUS_RANK: Record<AgentStatus, number> = {
  error: 0,
  'needs-approval': 1,
  working: 2,
  online: 3,
  idle: 4,
  offline: 5,
};

/** Pure filtering + sorting, kept outside components so it is unit-testable. */
export function filterAgents(
  agents: readonly Agent[],
  criteria: AgentFilterCriteria,
  context: AgentSearchContext,
  sort: AgentSort = 'name',
): Agent[] {
  const query = criteria.query.trim().toLowerCase();
  const result = agents.filter((agent) => {
    const { identity } = agent;
    if (criteria.departmentId && identity.departmentId !== criteria.departmentId) return false;
    if (criteria.roleId && identity.roleId !== criteria.roleId) return false;
    if (criteria.providerId && !agent.providers.some((p) => p.providerId === criteria.providerId)) return false;
    if (criteria.status && agent.status !== criteria.status) return false;
    if (!query) return true;
    const haystack = [
      identity.fullName,
      context.roleTitle(identity.roleId),
      context.departmentName(identity.departmentId),
      ...identity.skills,
    ]
      .join(' ')
      .toLowerCase();
    return haystack.includes(query);
  });

  return result.sort((a, b) => {
    switch (sort) {
      case 'last-active':
        return (b.lastActiveAt ?? '').localeCompare(a.lastActiveAt ?? '');
      case 'status':
        return STATUS_RANK[a.status] - STATUS_RANK[b.status] || a.identity.fullName.localeCompare(b.identity.fullName);
      default:
        return a.identity.fullName.localeCompare(b.identity.fullName);
    }
  });
}

export function hasActiveFilters(criteria: AgentFilterCriteria): boolean {
  return !!(criteria.query.trim() || criteria.departmentId || criteria.roleId || criteria.providerId || criteria.status);
}
