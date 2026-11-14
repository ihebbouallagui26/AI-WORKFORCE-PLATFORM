import { inject, Injectable } from '@angular/core';
import { BackendClient } from '../identity/backend-client';
import {
  AgentBinding,
  AgentDetail,
  AgentExecution,
  AgentDraft,
  AgentMemoryConfig,
  AgentQuery,
  AgentSkillRef,
  AgentSummary,
  AgentToolGrant,
  AgentUpdate,
  BindingDraft,
  BindingOptions,
  ConnectionStatus,
  ConnectionTestResult,
  ExecutionStatus,
  HistoryEntry,
  Page,
  ProviderConnection,
  ProviderModel,
  ProviderType,
  ProviderTypeInfo,
  Skill,
  TeamAgent,
  ToolDefinition,
  ToolGrantInput,
} from './agents.models';

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** Path segments are validated: an id can never inject another path. */
function uuid(value: string): string {
  if (!UUID.test(value)) {
    throw new Error('Invalid identifier');
  }
  return value;
}

function org(organizationId: string): string {
  return `/api/v1/organizations/${uuid(organizationId)}`;
}

function clean(query: object): Record<string, string | number | boolean> {
  const result: Record<string, string | number | boolean> = {};
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      result[key] = value as string | number | boolean;
    }
  }
  return result;
}

/**
 * Typed client of the AI agent endpoints, over the Electron main-process proxy. Provider
 * credentials appear only as write-only arguments of `createConnection` and `replaceCredential`:
 * the API never returns them, and no method here keeps them.
 */
@Injectable({ providedIn: 'root' })
export class AgentsApi {
  private readonly http = inject(BackendClient);

  // ---------------------------------------------------------------- agents

  listAgents(organizationId: string, query: AgentQuery = {}): Promise<Page<AgentSummary>> {
    return this.http.get(`${org(organizationId)}/agents`, clean({ ...query }));
  }

  getAgent(organizationId: string, id: string): Promise<AgentDetail> {
    return this.http.get(`${org(organizationId)}/agents/${uuid(id)}`);
  }

  createAgent(organizationId: string, draft: AgentDraft): Promise<AgentDetail> {
    return this.http.post(`${org(organizationId)}/agents`, draft);
  }

  updateAgent(organizationId: string, id: string, update: AgentUpdate): Promise<AgentDetail> {
    return this.http.patch(`${org(organizationId)}/agents/${uuid(id)}`, update);
  }

  changeAgentDepartment(organizationId: string, id: string, departmentId: string | null): Promise<AgentDetail> {
    return this.http.put(`${org(organizationId)}/agents/${uuid(id)}/department`, { departmentId });
  }

  activateAgent(organizationId: string, id: string): Promise<AgentDetail> {
    return this.http.post(`${org(organizationId)}/agents/${uuid(id)}/activate`);
  }

  pauseAgent(organizationId: string, id: string): Promise<AgentDetail> {
    return this.http.post(`${org(organizationId)}/agents/${uuid(id)}/pause`);
  }

  archiveAgent(organizationId: string, id: string): Promise<AgentDetail> {
    return this.http.post(`${org(organizationId)}/agents/${uuid(id)}/archive`);
  }

  agentHistory(organizationId: string, id: string, page = 0, size = 20): Promise<Page<HistoryEntry>> {
    return this.http.get(`${org(organizationId)}/agents/${uuid(id)}/history`, { page, size });
  }

  // ---------------------------------------------------------------- supervised executions

  /** Launches a run (202: it executes asynchronously; poll {@link getExecution}). Instruction only: no provider or key. */
  startExecution(organizationId: string, agentId: string, task: string): Promise<AgentExecution> {
    return this.http.post(`${org(organizationId)}/agents/${uuid(agentId)}/executions`, { task });
  }

  listExecutions(
    organizationId: string,
    agentId: string,
    query: { status?: ExecutionStatus; page?: number; size?: number } = {},
  ): Promise<Page<AgentExecution>> {
    return this.http.get(`${org(organizationId)}/agents/${uuid(agentId)}/executions`, clean({ size: 10, ...query }));
  }

  getExecution(organizationId: string, executionId: string): Promise<AgentExecution> {
    return this.http.get(`${org(organizationId)}/executions/${uuid(executionId)}`);
  }

  // ---------------------------------------------------------------- configuration

  addBinding(organizationId: string, agentId: string, draft: BindingDraft): Promise<AgentBinding[]> {
    return this.http.post(`${org(organizationId)}/agents/${uuid(agentId)}/providers`, draft);
  }

  updateBinding(
    organizationId: string,
    agentId: string,
    bindingId: string,
    update: { modelId?: string; options?: BindingOptions; makePrimary?: boolean; priority?: number },
  ): Promise<AgentBinding[]> {
    return this.http.patch(`${org(organizationId)}/agents/${uuid(agentId)}/providers/${uuid(bindingId)}`, update);
  }

  removeBinding(organizationId: string, agentId: string, bindingId: string): Promise<AgentBinding[]> {
    return this.http.delete<AgentBinding[]>(`${org(organizationId)}/agents/${uuid(agentId)}/providers/${uuid(bindingId)}`);
  }

  setSkills(organizationId: string, agentId: string, skillIds: readonly string[]): Promise<AgentSkillRef[]> {
    return this.http.put(`${org(organizationId)}/agents/${uuid(agentId)}/skills`, { skillIds });
  }

  setTools(organizationId: string, agentId: string, grants: readonly ToolGrantInput[]): Promise<AgentToolGrant[]> {
    return this.http.put(`${org(organizationId)}/agents/${uuid(agentId)}/tools`, { grants });
  }

  setMemory(organizationId: string, agentId: string, memory: Omit<AgentMemoryConfig, 'updatedAt'>): Promise<AgentMemoryConfig> {
    return this.http.put(`${org(organizationId)}/agents/${uuid(agentId)}/memory-config`, memory);
  }

  // ---------------------------------------------------------------- skills and tools

  listSkills(organizationId: string, size = 100): Promise<Page<Skill>> {
    return this.http.get(`${org(organizationId)}/skills`, { size });
  }

  createSkill(organizationId: string, draft: { name: string; category?: string; description?: string }): Promise<Skill> {
    return this.http.post(`${org(organizationId)}/skills`, draft);
  }

  updateSkill(organizationId: string, id: string, update: { name?: string; category?: string; description?: string; active?: boolean }): Promise<Skill> {
    return this.http.patch(`${org(organizationId)}/skills/${uuid(id)}`, update);
  }

  deleteSkill(organizationId: string, id: string): Promise<void> {
    return this.http.delete(`${org(organizationId)}/skills/${uuid(id)}`);
  }

  toolCatalog(organizationId: string): Promise<ToolDefinition[]> {
    return this.http.get(`${org(organizationId)}/tools`);
  }

  // ---------------------------------------------------------------- teams

  teamAgents(organizationId: string, teamId: string): Promise<TeamAgent[]> {
    return this.http.get(`${org(organizationId)}/teams/${uuid(teamId)}/agents`);
  }

  addTeamAgent(organizationId: string, teamId: string, agentId: string): Promise<TeamAgent> {
    return this.http.post(`${org(organizationId)}/teams/${uuid(teamId)}/agents`, { agentId });
  }

  removeTeamAgent(organizationId: string, teamId: string, agentId: string): Promise<void> {
    return this.http.delete(`${org(organizationId)}/teams/${uuid(teamId)}/agents/${uuid(agentId)}`);
  }

  // ---------------------------------------------------------------- provider connections

  providerTypes(): Promise<ProviderTypeInfo[]> {
    return this.http.get('/api/v1/providers/types');
  }

  listConnections(organizationId: string, query: { type?: ProviderType; status?: ConnectionStatus } = {}): Promise<Page<ProviderConnection>> {
    return this.http.get(`${org(organizationId)}/providers/connections`, { ...clean(query), size: 100 });
  }

  /** The credential is write-only: it is sent once and never returned. */
  createConnection(
    organizationId: string,
    draft: { providerType: ProviderType; displayName: string; accountIdentifier?: string; credential: string },
  ): Promise<ProviderConnection> {
    return this.http.post(`${org(organizationId)}/providers/connections`, draft);
  }

  updateConnection(organizationId: string, id: string, update: { displayName?: string; accountIdentifier?: string }): Promise<ProviderConnection> {
    return this.http.patch(`${org(organizationId)}/providers/connections/${uuid(id)}`, update);
  }

  replaceCredential(organizationId: string, id: string, credential: string): Promise<ProviderConnection> {
    return this.http.put(`${org(organizationId)}/providers/connections/${uuid(id)}/credential`, { credential });
  }

  testConnection(organizationId: string, id: string): Promise<ConnectionTestResult> {
    return this.http.post(`${org(organizationId)}/providers/connections/${uuid(id)}/test`);
  }

  connectionModels(organizationId: string, id: string): Promise<ProviderModel[]> {
    return this.http.get(`${org(organizationId)}/providers/connections/${uuid(id)}/models`);
  }

  revokeConnection(organizationId: string, id: string): Promise<void> {
    return this.http.delete(`${org(organizationId)}/providers/connections/${uuid(id)}`);
  }
}
