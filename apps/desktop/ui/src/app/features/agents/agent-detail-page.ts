import { ChangeDetectionStrategy, Component, computed, DestroyRef, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Project } from '../../core/models';
import { AgentStore } from '../../core/state/agent.store';
import { CommunicationStore } from '../../core/state/communication.store';
import { DirectoryStore } from '../../core/state/directory.store';
import { ProjectStore } from '../../core/state/project.store';
import { SupervisionStore } from '../../core/state/supervision.store';
import { ActivityList } from '../../shared/components/activity-list/activity-list';
import { Avatar } from '../../shared/components/avatar/avatar';
import { Badge } from '../../shared/components/badge/badge';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { Panel } from '../../shared/components/panel/panel';
import { ProgressBar } from '../../shared/components/progress-bar/progress-bar';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { TabItem, Tabs } from '../../shared/components/tabs/tabs';
import { DurationPipe } from '../../shared/pipes/duration.pipe';
import { RelativeTimePipe } from '../../shared/pipes/relative-time.pipe';
import {
  AUTONOMY_LEVEL,
  EXECUTION_STATUS,
  PERMISSION_LEVEL,
  PROJECT_STATUS,
  PROVIDER_CONNECTION,
  SENSITIVE_OPERATION,
  TASK_PRIORITY,
  TASK_STATUS,
  WORK_STATUS,
} from '../../shared/utils/presentation';
import { LayoutService } from '../../layout/layout.service';
import { AgentFormModal } from './components/agent-form-modal';

type AgentTab = 'overview' | 'responsibilities' | 'skills' | 'configuration' | 'activity';

@Component({
  selector: 'app-agent-detail-page',
  imports: [
    RouterLink,
    Avatar,
    Badge,
    Icon,
    Panel,
    Tabs,
    ProgressBar,
    Skeleton,
    EmptyState,
    ActivityList,
    AgentFormModal,
    RelativeTimePipe,
    DurationPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './agent-detail-page.html',
  styleUrl: './agent-detail-page.scss',
})
export class AgentDetailPage {
  /** Route parameter bound by the router. */
  readonly agentId = input.required<string>();

  protected readonly store = inject(AgentStore);
  protected readonly projects = inject(ProjectStore);
  private readonly supervision = inject(SupervisionStore);
  private readonly communication = inject(CommunicationStore);
  private readonly directory = inject(DirectoryStore);
  private readonly layout = inject(LayoutService);

  protected readonly workStatus = WORK_STATUS;
  protected readonly projectStatus = PROJECT_STATUS;
  protected readonly taskStatus = TASK_STATUS;
  protected readonly taskPriority = TASK_PRIORITY;
  protected readonly executionStatus = EXECUTION_STATUS;
  protected readonly permission = PERMISSION_LEVEL;
  protected readonly autonomy = AUTONOMY_LEVEL;
  protected readonly operations = SENSITIVE_OPERATION;
  protected readonly connection = PROVIDER_CONNECTION;

  protected readonly tab = signal<AgentTab>('overview');
  protected readonly editing = signal(false);

  protected readonly agent = computed(() => this.store.agentById().get(this.agentId()) ?? null);
  protected readonly ref = computed(() => ({ kind: 'agent' as const, id: this.agentId() }));
  protected readonly profile = computed(() => this.directory.resolve(this.ref()));
  protected readonly assignedProjects = computed(() => {
    const agent = this.agent();
    return agent
      ? agent.assignedProjectIds
          .map((id) => this.projects.projectById().get(id))
          .filter((p): p is Project => p !== undefined)
      : [];
  });
  protected readonly tasks = computed(() =>
    [...this.projects.tasksForMember(this.ref())].sort((a, b) => a.status.localeCompare(b.status)),
  );
  protected readonly openTasks = computed(() => this.tasks().filter((t) => t.status !== 'done'));
  protected readonly executions = computed(() => this.supervision.executions().filter((e) => e.agentId === this.agentId()));
  protected readonly activity = computed(() =>
    this.supervision.activity().filter((e) => e.actorRef.kind === 'agent' && e.actorRef.id === this.agentId()),
  );
  protected readonly pendingApprovals = computed(() =>
    this.supervision.pendingApprovals().filter((a) => a.agentId === this.agentId()),
  );
  protected readonly directConversation = computed(
    () =>
      this.communication
        .conversations()
        .find((c) => c.kind === 'direct' && c.participantRefs.some((p) => p.kind === 'agent' && p.id === this.agentId())) ??
      null,
  );
  protected readonly bindings = computed(() => {
    const agent = this.agent();
    if (!agent) return [];
    return agent.providers.map((binding) => ({
      binding,
      provider: this.store.providerById().get(binding.providerId) ?? null,
      model: this.store.modelById().get(binding.modelId) ?? null,
    }));
  });

  protected readonly tabs = computed<readonly TabItem[]>(() => {
    const agent = this.agent();
    return [
      { id: 'overview', label: 'Overview' },
      { id: 'responsibilities', label: 'Responsibilities', count: agent?.identity.responsibilities.length ?? 0 },
      { id: 'skills', label: 'Skills', count: agent?.identity.skills.length ?? 0 },
      { id: 'configuration', label: 'Configuration' },
      { id: 'activity', label: 'Activity history', count: this.executions().length },
    ];
  });

  constructor() {
    effect(() => this.layout.detailTitle.set(this.agent()?.identity.fullName ?? null));
    inject(DestroyRef).onDestroy(() => this.layout.detailTitle.set(null));
  }

  protected selectTab(id: string): void {
    this.tab.set(id as AgentTab);
  }
}
