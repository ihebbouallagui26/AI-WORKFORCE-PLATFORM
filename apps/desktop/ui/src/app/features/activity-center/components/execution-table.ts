import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AgentExecution, ExecutionStatus } from '../../../core/models';
import { AgentStore } from '../../../core/state/agent.store';
import { ProjectStore } from '../../../core/state/project.store';
import { Badge } from '../../../shared/components/badge/badge';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { Member } from '../../../shared/components/member/member';
import { DurationPipe } from '../../../shared/pipes/duration.pipe';
import { RelativeTimePipe } from '../../../shared/pipes/relative-time.pipe';
import { EXECUTION_STATUS } from '../../../shared/utils/presentation';

type SortKey = 'started' | 'duration' | 'status';

const STATUSES = Object.keys(EXECUTION_STATUS) as ExecutionStatus[];

@Component({
  selector: 'app-execution-table',
  imports: [RouterLink, Badge, Icon, Member, EmptyState, RelativeTimePipe, DurationPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toolbar filters">
      <select class="select select--sm" aria-label="Filter by status" (change)="setStatus(statusSelect.value)" #statusSelect>
        <option value="">All statuses</option>
        @for (s of statusOptions; track s) {
          <option [value]="s">{{ statuses[s].label }}</option>
        }
      </select>
      <select class="select select--sm" aria-label="Filter by agent" (change)="agentId.set(agentSelect.value || null)" #agentSelect>
        <option value="">All agents</option>
        @for (agent of agents.agents(); track agent.id) {
          <option [value]="agent.id">{{ agent.identity.fullName }}</option>
        }
      </select>
      <span class="toolbar__spacer"></span>
      <span class="text-tertiary count">{{ rows().length }} executions</span>
    </div>
    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th scope="col">Agent</th>
            <th scope="col">Task</th>
            <th scope="col" [attr.aria-sort]="ariaSort('status')">
              <button type="button" class="table__sort" [class.is-active]="sortKey() === 'status'" (click)="sortBy('status')">Status <ui-icon name="sort" [size]="12" /></button>
            </th>
            <th scope="col" [attr.aria-sort]="ariaSort('started')">
              <button type="button" class="table__sort" [class.is-active]="sortKey() === 'started'" (click)="sortBy('started')">Started <ui-icon name="sort" [size]="12" /></button>
            </th>
            <th scope="col" [attr.aria-sort]="ariaSort('duration')">
              <button type="button" class="table__sort" [class.is-active]="sortKey() === 'duration'" (click)="sortBy('duration')">Duration <ui-icon name="sort" [size]="12" /></button>
            </th>
            <th scope="col">Result</th>
          </tr>
        </thead>
        <tbody>
          @for (execution of rows(); track execution.id) {
            <tr>
              <td><ui-member [ref]="{ kind: 'agent', id: execution.agentId }" size="xs" [showAiTag]="false" /></td>
              <td>
                <span class="table__primary">{{ execution.title }}</span>
                @if (execution.projectId && projects.projectById().get(execution.projectId); as project) {
                  <a class="project-link" [routerLink]="['/projects', project.id]" [queryParams]="execution.taskId ? { task: execution.taskId } : {}">{{ project.name }}</a>
                }
              </td>
              <td><ui-badge [presentation]="statuses[execution.status]" /></td>
              <td class="tabular nowrap">{{ execution.startedAt | relativeTime }}</td>
              <td class="tabular nowrap">
                {{ execution.startedAt | duration: execution.finishedAt }}
                @if (!execution.finishedAt) {
                  <span class="text-tertiary">so far</span>
                }
              </td>
              <td class="result">{{ execution.resultSummary }}</td>
            </tr>
          } @empty {
            <tr><td colspan="6"><ui-empty-state [compact]="true" icon="cpu" heading="No executions match" /></td></tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: `
    .filters {
      padding: var(--space-3) var(--space-4);
      border-bottom: 1px solid var(--color-border-subtle);
    }
    .filters .select {
      width: auto;
      min-width: 150px;
    }
    .count {
      font-size: var(--text-xs);
    }
    .project-link {
      display: block;
      font-size: var(--text-xs);
    }
    .nowrap {
      white-space: nowrap;
    }
    .result {
      max-width: 360px;
    }
  `,
})
export class ExecutionTable {
  readonly executions = input.required<readonly AgentExecution[]>();

  protected readonly agents = inject(AgentStore);
  protected readonly projects = inject(ProjectStore);
  protected readonly statuses = EXECUTION_STATUS;
  protected readonly statusOptions = STATUSES;

  protected readonly status = signal<ExecutionStatus | null>(null);
  protected readonly agentId = signal<string | null>(null);
  protected readonly sortKey = signal<SortKey>('started');
  protected readonly descending = signal(true);

  protected readonly rows = computed(() => {
    const now = Date.now();
    const duration = (e: AgentExecution) =>
      (e.finishedAt ? new Date(e.finishedAt).getTime() : now) - new Date(e.startedAt).getTime();
    const direction = this.descending() ? -1 : 1;
    return this.executions()
      .filter((e) => (!this.status() || e.status === this.status()) && (!this.agentId() || e.agentId === this.agentId()))
      .sort((a, b) => {
        switch (this.sortKey()) {
          case 'duration':
            return (duration(a) - duration(b)) * direction;
          case 'status':
            return a.status.localeCompare(b.status) * direction;
          default:
            return a.startedAt.localeCompare(b.startedAt) * direction;
        }
      });
  });

  protected setStatus(value: string): void {
    this.status.set(STATUSES.includes(value as ExecutionStatus) ? (value as ExecutionStatus) : null);
  }

  protected sortBy(key: SortKey): void {
    if (this.sortKey() === key) {
      this.descending.update((d) => !d);
    } else {
      this.sortKey.set(key);
      this.descending.set(key !== 'status');
    }
  }

  protected ariaSort(key: SortKey): 'ascending' | 'descending' | null {
    return this.sortKey() === key ? (this.descending() ? 'descending' : 'ascending') : null;
  }
}
