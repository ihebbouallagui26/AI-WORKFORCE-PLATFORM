import { computed, inject, Injectable, signal } from '@angular/core';
import { AccessStore } from '../identity/access.store';
import { PermissionCode } from '../identity/identity.models';
import { WorkspaceStore } from '../state/workspace.store';
import { orgResource } from '../workforce/org-resource';
import { WorkflowsApi } from './workflows.api';
import { RunStatus, WorkflowDefinition, WorkflowMode, WorkflowRun } from './workflows.models';

export const WORKFLOW_PAGE_SIZE = 100;
export const RUN_PAGE_SIZE = 50;

/**
 * Organization-scoped state of the workflow definitions and runs. Every collection is keyed by the current organization: switching
 * organization reloads everything and a slow answer of the previous organization is never shown. Permissions here only decide what is
 * OFFERED to the user; the Core API enforces them on every call.
 */
@Injectable({ providedIn: 'root' })
export class WorkflowsStore {
  private readonly api = inject(WorkflowsApi);
  private readonly workspace = inject(WorkspaceStore);
  readonly access = inject(AccessStore);

  readonly organizationId = this.workspace.currentOrganizationId;
  readonly search = signal('');
  readonly modeFilter = signal<WorkflowMode | ''>('');
  readonly runFilter = signal<'' | 'active' | 'completed' | 'failed' | 'waiting-approval' | 'waiting-recovery'>('');

  private readonly definitionList = orgResource(
    this.organizationId,
    () => this.can('workflow.read'),
    () => null,
    (id) => this.api.listDefinitions(id, { size: WORKFLOW_PAGE_SIZE }),
  );
  private readonly runList = orgResource(
    this.organizationId,
    () => this.can('workflow.read'),
    () => null,
    (id) => this.api.listRuns(id, { size: RUN_PAGE_SIZE }),
  );

  readonly definitions = computed<readonly WorkflowDefinition[]>(() => this.definitionList.value()?.content ?? []);
  readonly totalDefinitions = computed(() => this.definitionList.value()?.totalElements ?? 0);
  readonly runs = computed<readonly WorkflowRun[]>(() => this.runList.value()?.content ?? []);
  readonly totalRuns = computed(() => this.runList.value()?.totalElements ?? 0);
  readonly loading = computed(() => this.definitionList.loading() || this.runList.loading());
  readonly error = computed(() => this.definitionList.error() ?? this.runList.error());

  /** The definitions matching the search text and the mode filter. */
  readonly filteredDefinitions = computed(() => {
    const q = this.search().trim().toLowerCase();
    const mode = this.modeFilter();
    return this.definitions().filter((d) => (!mode || d.mode === mode) && (!q || d.name.toLowerCase().includes(q) || d.description.toLowerCase().includes(q)));
  });

  /** The most recent run of each definition (runs come newest first). */
  readonly lastRunByDefinition = computed(() => {
    const latest = new Map<string, WorkflowRun>();
    for (const run of this.runs()) {
      if (!latest.has(run.definitionId)) latest.set(run.definitionId, run);
    }
    return latest;
  });

  readonly filteredRuns = computed(() => {
    const filter = this.runFilter();
    const q = this.search().trim().toLowerCase();
    return this.runs().filter((r) => matchesRunFilter(r.status, filter) && (!q || r.objective.toLowerCase().includes(q)));
  });

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
    this.definitionList.reload();
    this.runList.reload();
  }
}

export function matchesRunFilter(status: RunStatus, filter: '' | 'active' | 'completed' | 'failed' | 'waiting-approval' | 'waiting-recovery'): boolean {
  switch (filter) {
    case '':
      return true;
    case 'active':
      return status === 'PENDING' || status === 'RUNNING' || status === 'WAITING_APPROVAL' || status === 'WAITING_RECOVERY' || status === 'CANCEL_REQUESTED';
    case 'completed':
      return status === 'SUCCEEDED';
    case 'failed':
      return status === 'FAILED' || status === 'TIMEOUT';
    case 'waiting-approval':
      return status === 'WAITING_APPROVAL';
    case 'waiting-recovery':
      return status === 'WAITING_RECOVERY';
  }
}
