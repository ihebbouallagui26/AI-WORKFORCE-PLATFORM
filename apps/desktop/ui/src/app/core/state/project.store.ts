import { computed, inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ProjectApi } from '../data/api/project.api';
import { Id, MemberRef, Project, sameMember, Task, TaskDraft, TaskStatus, TaskUpdate } from '../models';
import { indexById, orgCollection, requireOrganization, upsertById } from './collection';
import { WorkspaceStore } from './workspace.store';

export interface ProjectProgress {
  readonly total: number;
  readonly done: number;
  /** 0–100, derived from task completion. */
  readonly percent: number;
  readonly blocked: number;
}

@Injectable({ providedIn: 'root' })
export class ProjectStore {
  private readonly api = inject(ProjectApi);
  private readonly organizationId = inject(WorkspaceStore).currentOrganizationId;

  private readonly projectList = orgCollection(this.organizationId, (id) => this.api.listProjects(id));
  private readonly taskList = orgCollection(this.organizationId, (id) => this.api.listTasks(id));

  readonly projects = this.projectList.items;
  readonly tasks = this.taskList.items;
  readonly loading = computed(() => this.projectList.loading() || this.taskList.loading());
  readonly error = computed(() => this.projectList.error() ?? this.taskList.error());

  readonly projectById = computed(() => indexById(this.projects()));

  readonly tasksByProject = computed(() => {
    const map = new Map<Id, Task[]>();
    for (const task of this.tasks()) {
      const list = map.get(task.projectId) ?? [];
      list.push(task);
      map.set(task.projectId, list);
    }
    return map as ReadonlyMap<Id, readonly Task[]>;
  });

  readonly progressByProject = computed(() => {
    const map = new Map<Id, ProjectProgress>();
    for (const project of this.projects()) {
      const tasks = this.tasksByProject().get(project.id) ?? [];
      const done = tasks.filter((t) => t.status === 'done').length;
      map.set(project.id, {
        total: tasks.length,
        done,
        blocked: tasks.filter((t) => t.status === 'blocked').length,
        percent: project.status === 'completed' ? 100 : tasks.length ? Math.round((done / tasks.length) * 100) : 0,
      });
    }
    return map as ReadonlyMap<Id, ProjectProgress>;
  });

  readonly activeProjects = computed(() =>
    this.projects().filter((p) => p.status === 'active' || p.status === 'at-risk'),
  );

  progressOf(projectId: Id): ProjectProgress {
    return this.progressByProject().get(projectId) ?? { total: 0, done: 0, blocked: 0, percent: 0 };
  }

  projectsForMember(ref: MemberRef): readonly Project[] {
    return this.projects().filter(
      (p) => sameMember(p.managerRef, ref) || p.members.some((m) => sameMember(m.member, ref)),
    );
  }

  tasksForMember(ref: MemberRef): readonly Task[] {
    return this.tasks().filter((t) => sameMember(t.assigneeRef, ref));
  }

  async createTask(draft: TaskDraft): Promise<Task> {
    const organizationId = requireOrganization(this.organizationId);
    const created = await firstValueFrom(this.api.createTask(draft));
    this.taskList.mutate(organizationId, (items) => upsertById(items, created));
    return created;
  }

  async updateTask(taskId: Id, update: TaskUpdate): Promise<Task> {
    const organizationId = requireOrganization(this.organizationId);
    const updated = await firstValueFrom(this.api.updateTask(taskId, update));
    this.taskList.mutate(organizationId, (items) => upsertById(items, updated));
    return updated;
  }

  /**
   * Optimistically moves a task (used by drag and drop) and rolls back if the API fails.
   */
  async moveTask(taskId: Id, status: TaskStatus): Promise<Task> {
    const organizationId = requireOrganization(this.organizationId);
    const previous = this.tasks().find((t) => t.id === taskId);
    if (!previous) {
      throw new Error('Task not found.');
    }
    if (previous.status === status) {
      return previous;
    }
    this.taskList.mutate(organizationId, (items) => upsertById(items, { ...previous, status }));
    try {
      return await this.updateTask(taskId, { status });
    } catch (error) {
      this.taskList.mutate(organizationId, (items) => upsertById(items, previous));
      throw error;
    }
  }

  reload(): void {
    this.projectList.reload();
    this.taskList.reload();
  }
}
