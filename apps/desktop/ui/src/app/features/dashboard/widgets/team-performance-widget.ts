import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { DirectoryStore } from '../../../core/state/directory.store';
import { OrganizationStore } from '../../../core/state/organization.store';
import { ProjectStore } from '../../../core/state/project.store';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Panel } from '../../../shared/components/panel/panel';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';

interface DepartmentPerformance {
  readonly id: string;
  readonly name: string;
  readonly total: number;
  readonly done: number;
  readonly active: number;
  readonly blocked: number;
}

/** Task throughput per department, attributed through the assignee (human or agent). */
@Component({
  selector: 'app-team-performance-widget',
  imports: [Panel, Skeleton, EmptyState],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-panel heading="Team performance" subheading="Assigned tasks by department">
      @if (projects.loading()) {
        <ui-skeleton [rows]="5" />
      } @else {
        <ul class="perf">
          @for (row of rows(); track row.id) {
            <li class="perf__row">
              <div class="perf__head">
                <span class="perf__name">{{ row.name }}</span>
                <span class="perf__figures tabular">{{ row.done }}/{{ row.total }} done</span>
              </div>
              <div class="perf__bar" role="img" [attr.aria-label]="row.name + ': ' + row.done + ' done, ' + row.active + ' in progress, ' + row.blocked + ' blocked, out of ' + row.total">
                <span class="perf__seg perf__seg--done" [style.flex-grow]="row.done"></span>
                <span class="perf__seg perf__seg--active" [style.flex-grow]="row.active"></span>
                <span class="perf__seg perf__seg--blocked" [style.flex-grow]="row.blocked"></span>
                <span class="perf__seg perf__seg--rest" [style.flex-grow]="row.total - row.done - row.active - row.blocked"></span>
              </div>
            </li>
          } @empty {
            <ui-empty-state [compact]="true" icon="chart" heading="No assigned tasks yet" />
          }
        </ul>
        <div class="perf__legend" aria-hidden="true">
          <span><i class="perf__swatch perf__seg--done"></i>Done</span>
          <span><i class="perf__swatch perf__seg--active"></i>In progress / review</span>
          <span><i class="perf__swatch perf__seg--blocked"></i>Blocked</span>
        </div>
      }
    </ui-panel>
  `,
  styles: `
    .perf {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
    }
    .perf__head {
      display: flex;
      justify-content: space-between;
      margin-bottom: 6px;
      font-size: var(--text-xs);
    }
    .perf__name {
      color: var(--color-text-primary);
      font-weight: var(--weight-medium);
    }
    .perf__figures {
      color: var(--color-text-tertiary);
    }
    .perf__bar {
      display: flex;
      gap: 2px;
      height: 6px;
      overflow: hidden;
      border-radius: var(--radius-full);
      background: var(--color-surface-3);
    }
    .perf__seg--done { background: var(--color-success); }
    .perf__seg--active { background: var(--color-accent); }
    .perf__seg--blocked { background: var(--color-danger); }
    .perf__seg--rest { background: var(--color-surface-3); }
    .perf__legend {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-3);
      margin-top: var(--space-4);
      color: var(--color-text-tertiary);
      font-size: var(--text-2xs);
    }
    .perf__legend span {
      display: inline-flex;
      align-items: center;
      gap: 5px;
    }
    .perf__swatch {
      width: 8px;
      height: 8px;
      border-radius: 2px;
    }
  `,
})
export class TeamPerformanceWidget {
  protected readonly projects = inject(ProjectStore);
  private readonly directory = inject(DirectoryStore);
  private readonly organization = inject(OrganizationStore);

  protected readonly rows = computed<readonly DepartmentPerformance[]>(() => {
    const byDepartment = new Map<string, { total: number; done: number; active: number; blocked: number }>();
    for (const task of this.projects.tasks()) {
      const departmentId = this.directory.resolve(task.assigneeRef).departmentId;
      if (!task.assigneeRef || !departmentId) {
        continue;
      }
      const entry = byDepartment.get(departmentId) ?? { total: 0, done: 0, active: 0, blocked: 0 };
      entry.total += 1;
      if (task.status === 'done') entry.done += 1;
      if (task.status === 'in-progress' || task.status === 'in-review') entry.active += 1;
      if (task.status === 'blocked') entry.blocked += 1;
      byDepartment.set(departmentId, entry);
    }
    return [...byDepartment.entries()]
      .map(([id, figures]) => ({
        id,
        name: this.organization.departmentById().get(id)?.name ?? 'Unknown department',
        ...figures,
      }))
      .sort((a, b) => b.total - a.total);
  });
}
