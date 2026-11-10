import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { Id, Project, Task, TaskDraft, TaskUpdate } from '../../models';
import { ProjectApi } from '../api/project.api';
import { MockDatabase } from './mock-database';

@Injectable()
export class MockProjectApi extends ProjectApi {
  private readonly db = inject(MockDatabase);

  listProjects(organizationId: Id): Observable<Project[]> {
    return this.db.respond(this.db.tables.projects.filter((p) => p.organizationId === organizationId));
  }

  listTasks(organizationId: Id): Observable<Task[]> {
    const projectIds = new Set(
      this.db.tables.projects.filter((p) => p.organizationId === organizationId).map((p) => p.id),
    );
    return this.db.respond(this.db.tables.tasks.filter((t) => projectIds.has(t.projectId)));
  }

  createTask(draft: TaskDraft): Observable<Task> {
    const project = this.db.tables.projects.find((p) => p.id === draft.projectId);
    if (!project) {
      return this.db.notFound('Project', draft.projectId);
    }
    const sequence = this.nextSequence(project.id, project.code);
    const now = this.db.now();
    const user = this.db.catalog.user;
    const membership = user.memberships.find((m) => m.organizationId === project.organizationId);
    const task: Task = {
      ...draft,
      id: this.db.nextId('tsk'),
      key: `${project.code}-${sequence}`,
      reporterRef: { kind: 'human', id: membership?.employeeId ?? user.id },
      createdAt: now,
      updatedAt: now,
    };
    this.db.tables.tasks.push(task);
    return this.db.respond(task);
  }

  updateTask(taskId: Id, update: TaskUpdate): Observable<Task> {
    const current = this.db.tables.tasks.find((t) => t.id === taskId);
    if (!current) {
      return this.db.notFound('Task', taskId);
    }
    return this.db.respond(
      this.db.replace(this.db.tables.tasks, { ...current, ...update, updatedAt: this.db.now() }),
    );
  }

  private nextSequence(projectId: Id, code: string): number {
    const numbers = this.db.tables.tasks
      .filter((t) => t.projectId === projectId && t.key.startsWith(`${code}-`))
      .map((t) => Number(t.key.slice(code.length + 1)))
      .filter((n) => Number.isFinite(n));
    return (numbers.length ? Math.max(...numbers) : 0) + 1;
  }
}
