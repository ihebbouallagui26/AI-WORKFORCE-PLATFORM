import { Id, IsoDateString, MemberRef } from './common.model';

export type ProjectStatus = 'planning' | 'active' | 'at-risk' | 'on-hold' | 'completed';

export type ProjectCategory =
  | 'software'
  | 'design'
  | 'marketing'
  | 'research'
  | 'operations'
  | 'human-resources'
  | 'finance';

export interface ProjectMember {
  readonly member: MemberRef;
  readonly projectRole: string;
  /** Allocation on the project, in percent (0–100). */
  readonly allocation: number;
}

export interface Project {
  readonly id: Id;
  readonly organizationId: Id;
  readonly code: string;
  readonly name: string;
  readonly description: string;
  readonly category: ProjectCategory;
  readonly status: ProjectStatus;
  readonly departmentId: Id;
  readonly managerRef: MemberRef;
  readonly members: readonly ProjectMember[];
  readonly startDate: IsoDateString;
  readonly dueDate: IsoDateString;
}

export type TaskStatus = 'backlog' | 'todo' | 'in-progress' | 'in-review' | 'blocked' | 'done';

export type TaskPriority = 'low' | 'medium' | 'high' | 'urgent';

export interface Task {
  readonly id: Id;
  readonly projectId: Id;
  /** Human-readable key, e.g. `ORB-112`. */
  readonly key: string;
  readonly title: string;
  readonly description: string;
  readonly status: TaskStatus;
  readonly priority: TaskPriority;
  readonly assigneeRef: MemberRef | null;
  readonly reporterRef: MemberRef;
  readonly dueDate: IsoDateString | null;
  readonly labels: readonly string[];
  readonly createdAt: IsoDateString;
  readonly updatedAt: IsoDateString;
}

export interface TaskDraft {
  readonly projectId: Id;
  readonly title: string;
  readonly description: string;
  readonly status: TaskStatus;
  readonly priority: TaskPriority;
  readonly assigneeRef: MemberRef | null;
  readonly dueDate: IsoDateString | null;
  readonly labels: readonly string[];
}

export type TaskUpdate = Partial<Omit<TaskDraft, 'projectId'>>;
