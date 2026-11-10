import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, DestroyRef, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MemberKind, sameMember, Task, TaskStatus } from '../../core/models';
import { ProjectStore } from '../../core/state/project.store';
import { SupervisionStore } from '../../core/state/supervision.store';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { LayoutService } from '../../layout/layout.service';
import { ActivityList } from '../../shared/components/activity-list/activity-list';
import { Badge } from '../../shared/components/badge/badge';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { Member } from '../../shared/components/member/member';
import { Panel } from '../../shared/components/panel/panel';
import { ProgressBar } from '../../shared/components/progress-bar/progress-bar';
import { SearchInput } from '../../shared/components/search-input/search-input';
import { Segmented, SegmentOption } from '../../shared/components/segmented/segmented';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { TabItem, Tabs } from '../../shared/components/tabs/tabs';
import { DurationPipe } from '../../shared/pipes/duration.pipe';
import { RelativeTimePipe } from '../../shared/pipes/relative-time.pipe';
import { EXECUTION_STATUS, PROJECT_CATEGORY, PROJECT_STATUS } from '../../shared/utils/presentation';
import { KanbanBoard } from './components/kanban-board';
import { TaskModal } from './components/task-modal';
import { TaskTable } from './components/task-table';

type ProjectTab = 'board' | 'list' | 'members' | 'activity';
type AssigneeFilter = 'all' | MemberKind | 'mine';

const TABS: readonly ProjectTab[] = ['board', 'list', 'members', 'activity'];

@Component({
  selector: 'app-project-detail-page',
  imports: [
    DatePipe,
    RouterLink,
    Tabs,
    Panel,
    Badge,
    Member,
    Icon,
    ProgressBar,
    SearchInput,
    Segmented,
    Skeleton,
    EmptyState,
    ActivityList,
    KanbanBoard,
    TaskTable,
    TaskModal,
    RelativeTimePipe,
    DurationPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './project-detail-page.html',
  styleUrl: './project-detail-page.scss',
})
export class ProjectDetailPage {
  /** Route and query parameters bound by the router. */
  readonly projectId = input.required<string>();
  readonly task = input<string | undefined>();
  readonly tab = input<string | undefined>();

  protected readonly store = inject(ProjectStore);
  private readonly supervision = inject(SupervisionStore);
  private readonly workspace = inject(WorkspaceStore);
  private readonly layout = inject(LayoutService);
  private readonly router = inject(Router);

  protected readonly projectStatus = PROJECT_STATUS;
  protected readonly categories = PROJECT_CATEGORY;
  protected readonly executionStatus = EXECUTION_STATUS;
  protected readonly assigneeOptions: readonly SegmentOption<AssigneeFilter>[] = [
    { value: 'all', label: 'All' },
    { value: 'agent', label: 'AI agents', icon: 'agent' },
    { value: 'human', label: 'People', icon: 'user' },
    { value: 'mine', label: 'Mine' },
  ];

  protected readonly query = signal('');
  protected readonly assignee = signal<AssigneeFilter>('all');
  protected readonly creatingStatus = signal<TaskStatus | null>(null);

  protected readonly activeTab = computed<ProjectTab>(() => {
    const tab = this.tab();
    return TABS.includes(tab as ProjectTab) ? (tab as ProjectTab) : 'board';
  });

  protected readonly project = computed(() => this.store.projectById().get(this.projectId()) ?? null);
  protected readonly progress = computed(() => this.store.progressOf(this.projectId()));
  protected readonly projectTasks = computed(() => this.store.tasksByProject().get(this.projectId()) ?? []);

  protected readonly filteredTasks = computed(() => {
    const query = this.query().trim().toLowerCase();
    const filter = this.assignee();
    const me = this.workspace.currentMemberRef();
    return this.projectTasks().filter((t) => {
      if (query && !`${t.key} ${t.title} ${t.labels.join(' ')}`.toLowerCase().includes(query)) return false;
      if (filter === 'mine') return sameMember(t.assigneeRef, me);
      if (filter === 'agent' || filter === 'human') return t.assigneeRef?.kind === filter;
      return true;
    });
  });

  protected readonly selectedTask = computed<Task | null>(
    () => this.projectTasks().find((t) => t.id === this.task()) ?? null,
  );
  protected readonly taskModalOpen = computed(() => this.selectedTask() !== null || this.creatingStatus() !== null);

  protected readonly activity = computed(() => this.supervision.activity().filter((e) => e.projectId === this.projectId()));
  protected readonly executions = computed(() =>
    this.supervision.executions().filter((e) => e.projectId === this.projectId()),
  );
  protected readonly memberCounts = computed(() => {
    const members = this.project()?.members ?? [];
    return {
      humans: members.filter((m) => m.member.kind === 'human').length,
      agents: members.filter((m) => m.member.kind === 'agent').length,
    };
  });

  protected readonly tabs = computed<readonly TabItem[]>(() => [
    { id: 'board', label: 'Board', icon: 'grid' },
    { id: 'list', label: 'Task list', icon: 'list', count: this.projectTasks().length },
    { id: 'members', label: 'Team', icon: 'users', count: this.project()?.members.length ?? 0 },
    { id: 'activity', label: 'Activity', icon: 'activity', count: this.activity().length },
  ]);

  constructor() {
    effect(() => this.layout.detailTitle.set(this.project()?.name ?? null));
    inject(DestroyRef).onDestroy(() => this.layout.detailTitle.set(null));
  }

  protected selectTab(tab: string): void {
    void this.router.navigate([], { queryParams: { tab }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  protected openTask(task: Task): void {
    void this.router.navigate([], { queryParams: { task: task.id }, queryParamsHandling: 'merge' });
  }

  protected newTask(status: TaskStatus = 'todo'): void {
    this.creatingStatus.set(status);
  }

  protected closeTaskModal(): void {
    this.creatingStatus.set(null);
    if (this.task()) {
      void this.router.navigate([], { queryParams: { task: null }, queryParamsHandling: 'merge' });
    }
  }

  protected tasksAssignedTo(index: number): number {
    const member = this.project()?.members[index]?.member;
    return member ? this.projectTasks().filter((t) => sameMember(t.assigneeRef, member) && t.status !== 'done').length : 0;
  }
}
