import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Department, Id, MemberKind } from '../../core/models';
import { DirectoryStore, MemberProfile } from '../../core/state/directory.store';
import { OrganizationStore } from '../../core/state/organization.store';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { AvatarStack } from '../../shared/components/avatar-stack/avatar-stack';
import { Badge } from '../../shared/components/badge/badge';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { Member } from '../../shared/components/member/member';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { Panel } from '../../shared/components/panel/panel';
import { SearchInput } from '../../shared/components/search-input/search-input';
import { Segmented, SegmentOption } from '../../shared/components/segmented/segmented';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { StatCard } from '../../shared/components/stat-card/stat-card';
import { WORK_STATUS } from '../../shared/utils/presentation';
import { DepartmentFormModal } from './components/department-form-modal';
import { EmployeeDetailModal } from './components/employee-detail-modal';
import { OrgChart } from './components/org-chart';

type KindFilter = 'all' | MemberKind;

@Component({
  selector: 'app-organization-page',
  imports: [
    RouterLink,
    PageHeader,
    Panel,
    StatCard,
    Member,
    AvatarStack,
    Badge,
    Icon,
    SearchInput,
    Segmented,
    Skeleton,
    EmptyState,
    OrgChart,
    DepartmentFormModal,
    EmployeeDetailModal,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './organization-page.html',
  styleUrl: './organization-page.scss',
})
export class OrganizationPage {
  /** Query parameters bound by the router. */
  readonly department = input<string | undefined>();
  readonly member = input<string | undefined>();

  protected readonly workspace = inject(WorkspaceStore);
  protected readonly store = inject(OrganizationStore);
  private readonly directory = inject(DirectoryStore);
  private readonly router = inject(Router);

  protected readonly statuses = WORK_STATUS;
  protected readonly kindOptions: readonly SegmentOption<KindFilter>[] = [
    { value: 'all', label: 'Everyone' },
    { value: 'human', label: 'People', icon: 'user' },
    { value: 'agent', label: 'AI agents', icon: 'agent' },
  ];

  protected readonly creating = signal(false);
  protected readonly kind = signal<KindFilter>('all');
  protected readonly query = signal('');

  protected readonly selectedDepartment = computed<Department | null>(
    () => this.store.departmentById().get(this.department() ?? '') ?? null,
  );
  protected readonly selectedEmployee = computed(() => this.store.employeeById().get(this.member() ?? '') ?? null);

  protected readonly totals = computed(() => {
    const members = this.directory.members();
    return {
      departments: this.store.departments().length,
      people: members.filter((m) => m.kind === 'human').length,
      agents: members.filter((m) => m.kind === 'agent').length,
      teams: this.store.teams().length,
    };
  });

  protected readonly subDepartments = computed(() => {
    const selected = this.selectedDepartment();
    return selected ? this.store.departments().filter((d) => d.parentId === selected.id) : [];
  });

  protected readonly departmentTeams = computed(() => {
    const selected = this.selectedDepartment();
    return selected ? this.store.teams().filter((t) => t.departmentId === selected.id) : [];
  });

  /** Members of the selected department (or the whole organization), filtered. */
  protected readonly members = computed<readonly MemberProfile[]>(() => {
    const selected = this.selectedDepartment();
    const base = selected ? this.directory.membersOfDepartment(selected.id) : this.directory.members();
    const kind = this.kind();
    const query = this.query().trim().toLowerCase();
    return base.filter(
      (m) =>
        (kind === 'all' || m.kind === kind) &&
        (!query || `${m.name} ${m.roleTitle} ${m.departmentName}`.toLowerCase().includes(query)),
    );
  });

  protected countFor(departmentId: Id): number {
    return this.directory.membersOfDepartment(departmentId).length;
  }

  protected selectDepartment(departmentId: Id | null): void {
    void this.router.navigate([], { queryParams: { department: departmentId, member: null }, queryParamsHandling: 'merge' });
  }

  protected openMember(profile: MemberProfile): void {
    if (profile.kind === 'agent') {
      void this.router.navigate(['/agents', profile.ref.id]);
      return;
    }
    void this.router.navigate([], { queryParams: { member: profile.ref.id }, queryParamsHandling: 'merge' });
  }

  protected closeMember(): void {
    void this.router.navigate([], { queryParams: { member: null }, queryParamsHandling: 'merge' });
  }

  protected reportsTo(profile: MemberProfile): MemberProfileRef {
    if (profile.kind === 'human') {
      return this.store.employeeById().get(profile.ref.id)?.managerRef ?? null;
    }
    // Agents report to the head of their department.
    return profile.departmentId ? (this.store.departmentById().get(profile.departmentId)?.managerRef ?? null) : null;
  }

  protected onCreated(department: Department): void {
    this.creating.set(false);
    this.selectDepartment(department.id);
  }
}

type MemberProfileRef = MemberProfile['ref'] | null;
