import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Agent, AgentStatus } from '../../core/models';
import { PreferencesService } from '../../core/services/preferences.service';
import { AgentStore } from '../../core/state/agent.store';
import { DirectoryStore } from '../../core/state/directory.store';
import { OrganizationStore } from '../../core/state/organization.store';
import { ProjectStore } from '../../core/state/project.store';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { Badge } from '../../shared/components/badge/badge';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { Member } from '../../shared/components/member/member';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { SearchInput } from '../../shared/components/search-input/search-input';
import { Segmented, SegmentOption } from '../../shared/components/segmented/segmented';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { RelativeTimePipe } from '../../shared/pipes/relative-time.pipe';
import { WORK_STATUS } from '../../shared/utils/presentation';
import { AgentSort, filterAgents, hasActiveFilters } from './agent-filters';
import { AgentCard } from './components/agent-card';
import { AgentFormModal } from './components/agent-form-modal';

type AgentsView = 'grid' | 'table';

const STATUSES = Object.keys(WORK_STATUS) as AgentStatus[];

@Component({
  selector: 'app-agents-page',
  imports: [
    RouterLink,
    PageHeader,
    SearchInput,
    Segmented,
    Icon,
    Badge,
    Member,
    Skeleton,
    EmptyState,
    AgentCard,
    AgentFormModal,
    RelativeTimePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './agents-page.html',
  styleUrl: './agents-page.scss',
})
export class AgentsPage {
  /** Query parameters bound by the router. */
  readonly status = input<string | undefined>();
  readonly create = input<string | undefined>();

  protected readonly store = inject(AgentStore);
  protected readonly organization = inject(OrganizationStore);
  protected readonly projects = inject(ProjectStore);
  private readonly directory = inject(DirectoryStore);
  private readonly preferences = inject(PreferencesService);
  private readonly workspace = inject(WorkspaceStore);
  private readonly router = inject(Router);

  protected readonly statuses = WORK_STATUS;
  protected readonly statusOptions = STATUSES;
  protected readonly viewOptions: readonly SegmentOption<AgentsView>[] = [
    { value: 'grid', label: 'Grid view', icon: 'grid', iconOnly: true },
    { value: 'table', label: 'Table view', icon: 'list', iconOnly: true },
  ];

  protected readonly query = signal('');
  protected readonly departmentId = signal<string | null>(null);
  protected readonly roleId = signal<string | null>(null);
  protected readonly providerId = signal<string | null>(null);
  protected readonly statusFilter = signal<AgentStatus | null>(null);
  protected readonly sort = signal<AgentSort>('name');
  protected readonly view = signal<AgentsView>(this.preferences.get('agentsView'));
  protected readonly formOpen = signal(false);

  private readonly criteria = computed(() => ({
    query: this.query(),
    departmentId: this.departmentId(),
    roleId: this.roleId(),
    providerId: this.providerId(),
    status: this.statusFilter(),
  }));

  protected readonly filtersActive = computed(() => hasActiveFilters(this.criteria()));

  /** Only roles actually held by agents are offered as a filter. */
  protected readonly agentRoles = computed(() => {
    const roleIds = new Set(this.store.agents().map((a) => a.identity.roleId));
    return this.organization.roles().filter((r) => roleIds.has(r.id));
  });

  protected readonly filtered = computed(() =>
    filterAgents(
      this.store.agents(),
      this.criteria(),
      {
        roleTitle: (id) => this.organization.roleById().get(id)?.title ?? '',
        departmentName: (id) => this.organization.departmentById().get(id)?.name ?? '',
      },
      this.sort(),
    ),
  );

  constructor() {
    effect(() => {
      const status = this.status();
      untracked(() => this.statusFilter.set(STATUSES.includes(status as AgentStatus) ? (status as AgentStatus) : null));
    });
    effect(() => {
      if (this.create()) {
        untracked(() => this.formOpen.set(true));
      }
    });
    effect(() => {
      this.preferences.set('agentsView', this.view());
    });
    // Department and role identifiers are organization-specific: drop them on switch.
    let previousOrganization: string | null = null;
    effect(() => {
      const organizationId = this.workspace.currentOrganizationId();
      if (previousOrganization !== null && organizationId !== previousOrganization) {
        untracked(() => {
          this.departmentId.set(null);
          this.roleId.set(null);
        });
      }
      previousOrganization = organizationId;
    });
  }

  protected profile(agent: Agent) {
    return this.directory.resolve({ kind: 'agent', id: agent.id });
  }

  protected clearFilters(): void {
    this.query.set('');
    this.departmentId.set(null);
    this.roleId.set(null);
    this.providerId.set(null);
    this.statusFilter.set(null);
    void this.router.navigate([], { queryParams: {}, replaceUrl: true });
  }

  protected closeForm(): void {
    this.formOpen.set(false);
    if (this.create()) {
      void this.router.navigate([], { queryParams: { create: null }, queryParamsHandling: 'merge', replaceUrl: true });
    }
  }

  protected onSaved(agent: Agent): void {
    this.closeForm();
    void this.router.navigate(['/agents', agent.id]);
  }

  protected toNullable(value: string): string | null {
    return value || null;
  }

  protected setStatus(value: string): void {
    this.statusFilter.set(STATUSES.includes(value as AgentStatus) ? (value as AgentStatus) : null);
  }

  protected setSort(value: string): void {
    this.sort.set(value === 'status' || value === 'last-active' ? value : 'name');
  }
}
