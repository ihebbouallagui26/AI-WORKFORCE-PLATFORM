import { computed, inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AgentApi } from '../data/api/agent.api';
import { Agent, AgentDraft, AIModel, AIProvider, Id } from '../models';
import { globalCollection, indexById, orgCollection, requireOrganization, upsertById } from './collection';
import { WorkspaceStore } from './workspace.store';

@Injectable({ providedIn: 'root' })
export class AgentStore {
  private readonly api = inject(AgentApi);
  private readonly organizationId = inject(WorkspaceStore).currentOrganizationId;

  private readonly agentList = orgCollection(this.organizationId, (id) => this.api.listAgents(id));
  private readonly providerList = globalCollection(() => this.api.listProviders());
  private readonly modelList = globalCollection(() => this.api.listModels());

  readonly agents = this.agentList.items;
  readonly providers = this.providerList.items;
  readonly models = this.modelList.items;
  readonly loading = this.agentList.loading;
  readonly error = this.agentList.error;

  readonly agentById = computed(() => indexById(this.agents()));
  readonly providerById = computed(() => indexById(this.providers()));
  readonly modelById = computed(() => indexById(this.models()));

  modelsForProvider(providerId: Id | null): readonly AIModel[] {
    return providerId ? this.models().filter((m) => m.providerId === providerId) : [];
  }

  /** Primary provider/model pair of an agent, resolved for display. */
  primaryBinding(agent: Agent): { provider: AIProvider | null; model: AIModel | null } {
    const binding = agent.providers.find((p) => p.priority === 'primary') ?? agent.providers[0];
    return {
      provider: binding ? (this.providerById().get(binding.providerId) ?? null) : null,
      model: binding ? (this.modelById().get(binding.modelId) ?? null) : null,
    };
  }

  async createAgent(draft: AgentDraft): Promise<Agent> {
    const organizationId = requireOrganization(this.organizationId);
    const created = await firstValueFrom(this.api.createAgent(organizationId, draft));
    this.agentList.mutate(organizationId, (items) => upsertById(items, created));
    return created;
  }

  async updateAgent(agentId: Id, draft: AgentDraft): Promise<Agent> {
    const organizationId = requireOrganization(this.organizationId);
    const updated = await firstValueFrom(this.api.updateAgent(agentId, draft));
    this.agentList.mutate(organizationId, (items) => upsertById(items, updated));
    return updated;
  }

  reload(): void {
    this.agentList.reload();
  }
}
