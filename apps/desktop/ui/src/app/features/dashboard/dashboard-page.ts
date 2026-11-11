import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AgentStore } from '../../core/state/agent.store';
import { OrganizationStore } from '../../core/state/organization.store';
import { ProjectStore } from '../../core/state/project.store';
import { SupervisionStore } from '../../core/state/supervision.store';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { ActivityList } from '../../shared/components/activity-list/activity-list';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { Panel } from '../../shared/components/panel/panel';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { StatCard } from '../../shared/components/stat-card/stat-card';
import { AgentStatusWidget } from './widgets/agent-status-widget';
import { ApprovalsWidget } from './widgets/approvals-widget';
import { MeetingsWidget } from './widgets/meetings-widget';
import { ProjectsWidget } from './widgets/projects-widget';
import { ReportsWidget } from './widgets/reports-widget';
import { TeamPerformanceWidget } from './widgets/team-performance-widget';

@Component({
  selector: 'app-dashboard-page',
  imports: [
    RouterLink,
    PageHeader,
    StatCard,
    Panel,
    Icon,
    Skeleton,
    EmptyState,
    ActivityList,
    ProjectsWidget,
    AgentStatusWidget,
    TeamPerformanceWidget,
    ApprovalsWidget,
    MeetingsWidget,
    ReportsWidget,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dashboard-page.html',
})
export class DashboardPage {
  protected readonly workspace = inject(WorkspaceStore);
  protected readonly supervision = inject(SupervisionStore);
  private readonly organization = inject(OrganizationStore);
  private readonly agents = inject(AgentStore);
  private readonly projects = inject(ProjectStore);

  protected readonly greeting = computed(() => {
    const hour = new Date().getHours();
    const part = hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening';
    const firstName = this.workspace.user()?.displayName.split(' ')[0];
    return firstName ? `${part}, ${firstName}` : part;
  });

  protected readonly loading = computed(
    () => this.organization.loading() || this.agents.loading() || this.projects.loading(),
  );

  protected readonly stats = computed(() => {
    const humans = this.organization.employees().length;
    const agents = this.agents.agents();
    const activeAgents = agents.filter((a) => a.status !== 'offline');
    const projects = this.projects.projects();
    const activeProjects = projects.filter((p) => p.status === 'active' || p.status === 'at-risk');
    const tasks = this.projects.tasks();
    const pending = this.supervision.pendingApprovals();

    return {
      workforce: humans + agents.length,
      workforceCaption: `${humans} people · ${agents.length} AI agents`,
      activeAgents: activeAgents.length,
      activeAgentsCaption: `${agents.filter((a) => a.status === 'working').length} working right now`,
      activeProjects: activeProjects.length,
      activeProjectsCaption: `${projects.filter((p) => p.status === 'at-risk').length} at risk · ${projects.length} total`,
      tasksInProgress: tasks.filter((t) => t.status === 'in-progress' || t.status === 'in-review').length,
      tasksCaption: `${tasks.filter((t) => t.status === 'blocked').length} blocked`,
      pendingApprovals: pending.length,
      pendingCaption: `${pending.filter((a) => a.risk === 'high' || a.risk === 'critical').length} high or critical risk`,
    };
  });

  protected readonly recentActivity = computed(() => this.supervision.activity().slice(0, 7));
}
