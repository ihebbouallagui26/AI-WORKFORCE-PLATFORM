import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { Task, TaskStatus } from '../../../core/models';
import { ToastService } from '../../../core/services/toast.service';
import { ProjectStore } from '../../../core/state/project.store';
import { Dropdown } from '../../../shared/components/dropdown/dropdown';
import { Icon } from '../../../shared/components/icon/icon';
import { Member } from '../../../shared/components/member/member';
import { RelativeTimePipe } from '../../../shared/pipes/relative-time.pipe';
import { TASK_PRIORITY, TASK_STATUS, TASK_STATUS_ORDER } from '../../../shared/utils/presentation';

const DRAG_TYPE = 'application/x-awp-task';
const PRIORITY_RANK = { urgent: 0, high: 1, medium: 2, low: 3 } as const;

/**
 * Kanban board. Tasks move by drag and drop, or with the per-card "Move to" menu
 * which provides the keyboard-accessible equivalent.
 */
@Component({
  selector: 'app-kanban-board',
  imports: [Icon, Member, Dropdown, RelativeTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './kanban-board.html',
  styleUrl: './kanban-board.scss',
})
export class KanbanBoard {
  readonly tasks = input.required<readonly Task[]>();
  readonly openTask = output<Task>();
  readonly addTask = output<TaskStatus>();

  private readonly store = inject(ProjectStore);
  private readonly toast = inject(ToastService);

  protected readonly statuses = TASK_STATUS;
  protected readonly priorities = TASK_PRIORITY;
  protected readonly order = TASK_STATUS_ORDER;
  protected readonly dragOver = signal<TaskStatus | null>(null);
  protected readonly draggingId = signal<string | null>(null);

  protected readonly columns = computed(() =>
    TASK_STATUS_ORDER.map((status) => ({
      status,
      tasks: this.tasks()
        .filter((t) => t.status === status)
        .sort((a, b) => PRIORITY_RANK[a.priority] - PRIORITY_RANK[b.priority] || a.key.localeCompare(b.key)),
    })),
  );

  protected onDragStart(event: DragEvent, task: Task): void {
    event.dataTransfer?.setData(DRAG_TYPE, task.id);
    event.dataTransfer?.setData('text/plain', task.key);
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'move';
    }
    this.draggingId.set(task.id);
  }

  protected onDragEnd(): void {
    this.draggingId.set(null);
    this.dragOver.set(null);
  }

  protected onDragOver(event: DragEvent, status: TaskStatus): void {
    if (event.dataTransfer?.types.includes(DRAG_TYPE)) {
      event.preventDefault();
      event.dataTransfer.dropEffect = 'move';
      this.dragOver.set(status);
    }
  }

  protected onDragLeave(event: DragEvent, status: TaskStatus): void {
    const related = event.relatedTarget as Node | null;
    if (this.dragOver() === status && !(event.currentTarget as HTMLElement).contains(related)) {
      this.dragOver.set(null);
    }
  }

  protected onDrop(event: DragEvent, status: TaskStatus): void {
    event.preventDefault();
    const taskId = event.dataTransfer?.getData(DRAG_TYPE);
    this.onDragEnd();
    if (taskId) {
      void this.move(taskId, status);
    }
  }

  protected async move(taskId: string, status: TaskStatus): Promise<void> {
    const task = this.tasks().find((t) => t.id === taskId);
    if (!task || task.status === status) {
      return;
    }
    try {
      await this.store.moveTask(taskId, status);
      this.toast.info(`${task.key} moved to ${TASK_STATUS[status].label}`);
    } catch (error) {
      this.toast.error(`Could not move ${task.key}`, error);
    }
  }

  protected isOverdue(task: Task): boolean {
    return !!task.dueDate && task.status !== 'done' && new Date(task.dueDate).getTime() < Date.now();
  }
}
