import { Observable } from 'rxjs';
import { Id, Project, Task, TaskDraft, TaskUpdate } from '../../models';

export abstract class ProjectApi {
  abstract listProjects(organizationId: Id): Observable<Project[]>;
  abstract listTasks(organizationId: Id): Observable<Task[]>;
  abstract createTask(draft: TaskDraft): Observable<Task>;
  abstract updateTask(taskId: Id, update: TaskUpdate): Observable<Task>;
}
