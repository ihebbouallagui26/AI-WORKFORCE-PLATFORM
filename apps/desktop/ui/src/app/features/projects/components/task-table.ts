import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { Task, TaskPriority, TaskStatus } from '../../../core/models';
import { Badge } from '../../../shared/components/badge/badge';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { Member } from '../../../shared/components/member/member';
import { RelativeTimePipe } from '../../../shared/pipes/relative-time.pipe';
import { TASK_PRIORITY, TASK_STATUS, TASK_STATUS_ORDER } from '../../../shared/utils/presentation';

type SortKey = 'key' | 'title' | 'status' | 'priority' | 'due';

const PRIORITY_RANK: Record<TaskPriority, number> = { urgent: 0, high: 1, medium: 2, low: 3 };

@Component({
  selector: 'app-task-table',
  imports: [Badge, Member, Icon, EmptyState, RelativeTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            @for (column of columnsDef; track column.key) {
              <th scope="col" [attr.aria-sort]="sortKey() === column.key ? (ascending() ? 'ascending' : 'descending') : null">
                <button type="button" class="table__sort" [class.is-active]="sortKey() === column.key" (click)="sortBy(column.key)">
                  {{ column.label }}
                  <ui-icon name="sort" [size]="12" />
                </button>
              </th>
            }
            <th scope="col">Assignee</th>
          </tr>
        </thead>
        <tbody>
          @for (task of sorted(); track task.id) {
            <tr>
              <td class="mono text-tertiary">{{ task.key }}</td>
              <td>
                <button type="button" class="title-button" (click)="openTask.emit(task)">{{ task.title }}</button>
              </td>
              <td><ui-badge [presentation]="statuses[task.status]" /></td>
              <td><ui-badge [presentation]="priorities[task.priority]" [plain]="true" /></td>
              <td class="tabular">{{ task.dueDate ? (task.dueDate | relativeTime) : '—' }}</td>
              <td>
                @if (task.assigneeRef) {
                  <ui-member [ref]="task.assigneeRef" size="xs" />
                } @else {
                  <span class="text-tertiary">Unassigned</span>
                }
              </td>
            </tr>
          } @empty {
            <tr><td colspan="6"><ui-empty-state [compact]="true" icon="check" heading="No tasks match" /></td></tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: `
    .title-button {
      padding: 0;
      border: 0;
      background: none;
      color: var(--color-text-primary);
      font: inherit;
      font-weight: var(--weight-medium);
      text-align: left;
      cursor: pointer;
    }
    .title-button:hover {
      color: var(--color-accent-text);
    }
  `,
})
export class TaskTable {
  readonly tasks = input.required<readonly Task[]>();
  readonly openTask = output<Task>();

  protected readonly statuses = TASK_STATUS;
  protected readonly priorities = TASK_PRIORITY;
  protected readonly columnsDef: readonly { key: SortKey; label: string }[] = [
    { key: 'key', label: 'Key' },
    { key: 'title', label: 'Title' },
    { key: 'status', label: 'Status' },
    { key: 'priority', label: 'Priority' },
    { key: 'due', label: 'Due' },
  ];

  protected readonly sortKey = signal<SortKey>('status');
  protected readonly ascending = signal(true);

  protected readonly sorted = computed(() => {
    const direction = this.ascending() ? 1 : -1;
    const statusRank = (s: TaskStatus) => TASK_STATUS_ORDER.indexOf(s);
    return [...this.tasks()].sort((a, b) => {
      let result: number;
      switch (this.sortKey()) {
        case 'title':
          result = a.title.localeCompare(b.title);
          break;
        case 'status':
          result = statusRank(a.status) - statusRank(b.status);
          break;
        case 'priority':
          result = PRIORITY_RANK[a.priority] - PRIORITY_RANK[b.priority];
          break;
        case 'due':
          result = (a.dueDate ?? '9999').localeCompare(b.dueDate ?? '9999');
          break;
        default:
          result = a.key.localeCompare(b.key, undefined, { numeric: true });
      }
      return result * direction || a.key.localeCompare(b.key, undefined, { numeric: true });
    });
  });

  protected sortBy(key: SortKey): void {
    if (this.sortKey() === key) {
      this.ascending.update((v) => !v);
    } else {
      this.sortKey.set(key);
      this.ascending.set(true);
    }
  }
}
