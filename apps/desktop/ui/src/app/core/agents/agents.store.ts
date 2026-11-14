import { computed, inject, Injectable, signal } from '@angular/core';
import { AccessStore } from '../identity/access.store';
import { PermissionCode } from '../identity/identity.models';
import { WorkspaceStore } from '../state/workspace.store';
import { orgResource } from '../workforce/org-resource';
import { AgentsApi } from './agents.api';
import { AgentStatus, AgentSummary, ProviderConnection, Skill, ToolDefinition } from './agents.models';

export const AGENT_PAGE_SIZE = 50;

/**
 * Organization-scoped state of the AI agents, provider connections, skills and the tool catalog.
 * Every collection is keyed by the current organization: switching organization reloads
 * everything and results of the previous organization are never exposed. Provider credentials
 * are never held here: they only pass through the write-only API methods.
 */
@Injectable({ providedIn: 'root' })
export class AgentsStore {
  private readonly api = inject(AgentsApi);
  private readonly workspace = inject(WorkspaceStore);
  readonly access = inject(AccessStore);

  readonly organizationId = this.workspace.currentOrganizationId;
  readonly search = signal('');
  readonly statusFilter = signal<AgentStatus | ''>('');

  private readonly agentList = orgResource(
    this.organizationId,
    () => this.can('agent.read'),
    () => ({ q: this.search().trim(), status: this.statusFilter() }),
    (id, filter) =>
      this.api.listAgents(id, {
        size: AGENT_PAGE_SIZE,
        ...(filter.q ? { q: filter.q } : {}),
        ...(filter.status ? { status: filter.status } : {}),
      }),
  );
  private readonly connectionList = orgResource(
    this.organizationId,
    () => this.can('provider.read'),
    () => null,
    (id) => this.api.listConnections(id),
  );
  private readonly skillList = orgResource(
    this.organizationId,
    () => this.can('skill.read'),
    () => null,
    (id) => this.api.listSkills(id),
  );
  private readonly toolList = orgResource(
    this.organizationId,
    () => this.can('skill.read'),
    () => null,
    (id) => this.api.toolCatalog(id),
  );

  readonly agents = computed<readonly AgentSummary[]>(() => this.agentList.value()?.content ?? []);
  readonly totalAgents = computed(() => this.agentList.value()?.totalElements ?? 0);
  readonly connections = computed<readonly ProviderConnection[]>(() => this.connectionList.value()?.content ?? []);
  /** Connections that can be bound to an agent (revoked ones cannot). */
  readonly usableConnections = computed(() => this.connections().filter((c) => c.status !== 'REVOKED'));
  readonly skills = computed<readonly Skill[]>(() => this.skillList.value()?.content ?? []);
  readonly tools = computed<readonly ToolDefinition[]>(() => this.toolList.value() ?? []);

  readonly loading = computed(() => this.agentList.loading());
  readonly error = computed(() => this.agentList.error());
  readonly connectionsLoading = computed(() => this.connectionList.loading());
  readonly connectionsError = computed(() => this.connectionList.error());
  readonly skillsLoading = computed(() => this.skillList.loading());
  readonly skillsError = computed(() => this.skillList.error());

  can(permission: PermissionCode): boolean {
    return this.access.can(permission);
  }

  /** Runs a change against the organization selected when it starts, then refreshes the lists. */
  async mutate<T>(change: (organizationId: string) => Promise<T>): Promise<T> {
    const organizationId = this.organizationId();
    if (!organizationId) {
      throw new Error('No organization selected.');
    }
    const result = await change(organizationId);
    this.reload();
    return result;
  }

  reload(): void {
    this.agentList.reload();
    this.connectionList.reload();
    this.skillList.reload();
    this.toolList.reload();
  }
}
