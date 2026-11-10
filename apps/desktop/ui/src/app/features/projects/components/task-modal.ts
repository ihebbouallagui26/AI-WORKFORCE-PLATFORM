import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { memberKey, Project, Task, TaskPriority, TaskStatus } from '../../../core/models';
import { ToastService } from '../../../core/services/toast.service';
import { DirectoryStore } from '../../../core/state/directory.store';
import { ProjectStore } from '../../../core/state/project.store';
import { Icon } from '../../../shared/components/icon/icon';
import { Member } from '../../../shared/components/member/member';
import { Modal } from '../../../shared/components/modal/modal';
import { TagInput } from '../../../shared/components/tag-input/tag-input';
import { TASK_PRIORITY, TASK_STATUS, TASK_STATUS_ORDER } from '../../../shared/utils/presentation';
import { errorMessage, notBlank } from '../../../shared/utils/validators';

/** Create a task, or view and edit an existing one. */
@Component({
  selector: 'app-task-modal',
  imports: [DatePipe, ReactiveFormsModule, Modal, TagInput, Member, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './task-modal.html',
  styles: `
    .task-meta {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-4);
      margin-bottom: var(--space-5);
      padding: var(--space-3);
      border-radius: var(--radius-md);
      background: var(--color-bg-app);
      color: var(--color-text-tertiary);
      font-size: var(--text-xs);
    }
    .task-meta span {
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
  `,
})
export class TaskModal {
  readonly open = input(false);
  readonly project = input.required<Project>();
  /** Existing task to edit; `null` creates a task. */
  readonly task = input<Task | null>(null);
  readonly defaultStatus = input<TaskStatus>('todo');
  readonly closed = output<void>();

  private readonly fb = inject(NonNullableFormBuilder);
  private readonly projects = inject(ProjectStore);
  private readonly directory = inject(DirectoryStore);
  private readonly toast = inject(ToastService);

  protected readonly statuses = TASK_STATUS;
  protected readonly statusOrder = TASK_STATUS_ORDER;
  protected readonly priorities = Object.entries(TASK_PRIORITY) as [TaskPriority, { label: string }][];
  protected readonly saving = signal(false);
  protected readonly isEdit = computed(() => this.task() !== null);

  protected readonly form = this.fb.group({
    title: ['', [Validators.required, notBlank, Validators.minLength(3), Validators.maxLength(120)]],
    description: ['', Validators.maxLength(2000)],
    status: this.fb.control<TaskStatus>('todo'),
    priority: this.fb.control<TaskPriority>('medium'),
    assigneeKey: [''],
    dueDate: [''],
    labels: this.fb.control<readonly string[]>([]),
  });

  /** Project members first, then the rest of the organization. */
  protected readonly assigneeGroups = computed(() => {
    const projectKeys = new Set(this.project().members.map((m) => memberKey(m.member)));
    const members = this.directory.members();
    return {
      project: members.filter((m) => projectKeys.has(m.key)),
      others: members.filter((m) => !projectKeys.has(m.key)),
    };
  });

  constructor() {
    effect(() => {
      if (this.open()) {
        const task = this.task();
        const status = this.defaultStatus();
        untracked(() => this.reset(task, status));
      }
    });
  }

  protected error(name: keyof typeof this.form.controls, label: string): string | null {
    return errorMessage(this.form.controls[name], label);
  }

  protected async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.saving()) {
      return;
    }
    const v = this.form.getRawValue();
    const assignee = this.directory.members().find((m) => m.key === v.assigneeKey);
    const payload = {
      title: v.title.trim(),
      description: v.description.trim(),
      status: v.status,
      priority: v.priority,
      assigneeRef: assignee ? { kind: assignee.ref.kind, id: assignee.ref.id } : null,
      dueDate: v.dueDate ? new Date(`${v.dueDate}T17:00:00`).toISOString() : null,
      labels: v.labels,
    };

    this.saving.set(true);
    try {
      const existing = this.task();
      const saved = existing
        ? await this.projects.updateTask(existing.id, payload)
        : await this.projects.createTask({ ...payload, projectId: this.project().id });
      this.toast.success(existing ? 'Task updated' : 'Task created', `${saved.key} · saved in the local mock workspace.`);
      this.closed.emit();
    } catch (error) {
      this.toast.error('Could not save the task', error);
    } finally {
      this.saving.set(false);
    }
  }

  protected close(): void {
    if (!this.saving()) {
      this.closed.emit();
    }
  }

  private reset(task: Task | null, status: TaskStatus): void {
    if (!task) {
      this.form.reset({ status });
      return;
    }
    this.form.reset({
      title: task.title,
      description: task.description,
      status: task.status,
      priority: task.priority,
      assigneeKey: task.assigneeRef ? memberKey(task.assigneeRef) : '',
      dueDate: task.dueDate ? toDateInput(task.dueDate) : '',
      labels: task.labels,
    });
  }
}

function toDateInput(iso: string): string {
  const date = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
