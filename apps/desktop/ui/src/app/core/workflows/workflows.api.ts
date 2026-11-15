import { inject, Injectable } from '@angular/core';
import { BackendClient } from '../identity/backend-client';
import {
  Page,
  RunStatus,
  RunSummary,
  StepInput,
  WorkflowAction,
  WorkflowCreate,
  WorkflowDefinition,
  WorkflowDefinitionStatus,
  WorkflowMessage,
  WorkflowRun,
  WorkflowStepView,
  WorkflowUpdate,
} from './workflows.models';

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
 * Typed client of the workflow endpoints, over the Electron main-process proxy (which only forwards what its allowlist names: never an
 * internal service endpoint). Nothing here changes a workflow state by itself: the Core API is the only authority, these methods only
 * ASK (create a definition, launch a run, request a cancellation, request the retry of one failed step).
 */
@Injectable({ providedIn: 'root' })
export class WorkflowsApi {
  private readonly http = inject(BackendClient);

  // ---------------------------------------------------------------- definitions

  listDefinitions(organizationId: string, query: { status?: WorkflowDefinitionStatus; page?: number; size?: number } = {}): Promise<Page<WorkflowDefinition>> {
    return this.http.get(`${org(organizationId)}/workflows`, clean({ size: 50, ...query }));
  }

  getDefinition(organizationId: string, id: string): Promise<WorkflowDefinition> {
    return this.http.get(`${org(organizationId)}/workflows/${uuid(id)}`);
  }

  createDefinition(organizationId: string, draft: WorkflowCreate): Promise<WorkflowDefinition> {
    return this.http.post(`${org(organizationId)}/workflows`, draft);
  }

  updateDefinition(organizationId: string, id: string, update: WorkflowUpdate): Promise<WorkflowDefinition> {
    return this.http.patch(`${org(organizationId)}/workflows/${uuid(id)}`, update);
  }

  setParticipants(organizationId: string, id: string, agentIds: readonly string[], coordinatorAgentId?: string): Promise<WorkflowDefinition> {
    const body = coordinatorAgentId ? { agentIds, coordinatorAgentId: uuid(coordinatorAgentId) } : { agentIds };
    return this.http.put(`${org(organizationId)}/workflows/${uuid(id)}/participants`, body);
  }

  setSteps(organizationId: string, id: string, steps: readonly StepInput[]): Promise<WorkflowDefinition> {
    return this.http.put(`${org(organizationId)}/workflows/${uuid(id)}/steps`, { steps });
  }

  archiveDefinition(organizationId: string, id: string): Promise<WorkflowDefinition> {
    return this.http.post(`${org(organizationId)}/workflows/${uuid(id)}/archive`);
  }

  // ---------------------------------------------------------------- runs

  /** Launches a run (202: it advances asynchronously; poll the run). `requestId` makes a repeated launch return the same run. */
  startRun(organizationId: string, workflowId: string, objective: string, requestId?: string): Promise<WorkflowRun> {
    return this.http.post(`${org(organizationId)}/workflows/${uuid(workflowId)}/runs`, requestId ? { objective, requestId } : { objective });
  }

  listRuns(
    organizationId: string,
    query: { status?: RunStatus; definitionId?: string; page?: number; size?: number } = {},
  ): Promise<Page<WorkflowRun>> {
    if (query.definitionId !== undefined) {
      uuid(query.definitionId);
    }
    return this.http.get(`${org(organizationId)}/workflow-runs`, clean({ size: 50, ...query }));
  }

  getRun(organizationId: string, runId: string): Promise<WorkflowRun> {
    return this.http.get(`${org(organizationId)}/workflow-runs/${uuid(runId)}`);
  }

  /** Every ATTEMPT of every step of the run, ordered by rank then attempt. */
  runSteps(organizationId: string, runId: string, page = 0, size = 100): Promise<Page<WorkflowStepView>> {
    return this.http.get(`${org(organizationId)}/workflow-runs/${uuid(runId)}/steps`, { page, size });
  }

  runMessages(organizationId: string, runId: string, page = 0, size = 100): Promise<Page<WorkflowMessage>> {
    return this.http.get(`${org(organizationId)}/workflow-runs/${uuid(runId)}/messages`, { page, size });
  }

  runActions(organizationId: string, runId: string, page = 0, size = 100): Promise<Page<WorkflowAction>> {
    return this.http.get(`${org(organizationId)}/workflow-runs/${uuid(runId)}/actions`, { page, size });
  }

  runSummary(organizationId: string, runId: string): Promise<RunSummary> {
    return this.http.get(`${org(organizationId)}/workflow-runs/${uuid(runId)}/summary`);
  }

  /** Cooperative cancellation: nothing new starts; started steps are not undone and an unknown external outcome stays unknown. */
  cancelRun(organizationId: string, runId: string): Promise<WorkflowRun> {
    return this.http.post(`${org(organizationId)}/workflow-runs/${uuid(runId)}/cancel`);
  }

  /** Asks the Core API to retry the failed step that holds a run in WAITING_RECOVERY. The Core API decides (409 when it cannot). */
  retryStep(organizationId: string, runId: string, rank: number): Promise<WorkflowRun> {
    if (!Number.isInteger(rank) || rank < 1 || rank > 99) {
      throw new Error('Invalid step number');
    }
    return this.http.post(`${org(organizationId)}/workflow-runs/${uuid(runId)}/steps/${rank}/retry`);
  }
}
