import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { IdentityApi } from '../../core/identity/identity.api';
import { MemberDto } from '../../core/identity/identity.models';
import { ConfirmService } from '../../core/services/confirm.service';
import { ToastService } from '../../core/services/toast.service';
import { orgResource } from '../../core/workforce/org-resource';
import { WorkforceApi } from '../../core/workforce/workforce.api';
import { EmployeeDto, EmploymentStatus } from '../../core/workforce/workforce.models';
import { WorkforceStore } from '../../core/workforce/workforce.store';
import { Badge } from '../../shared/components/badge/badge';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { Panel } from '../../shared/components/panel/panel';
import { SearchInput } from '../../shared/components/search-input/search-input';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { WfEmployeeForm } from './employee-form';
import { EMPLOYMENT_LABEL, EMPLOYMENT_TONE, idOrNull } from './workforce-format';

const PAGE_SIZE = 15;

/** Employee directory: search, filters, pagination, and a panel to manage one employee. */
@Component({
  selector: 'app-wf-employees',
  imports: [Panel, Badge, EmptyState, Skeleton, Icon, SearchInput, WfEmployeeForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!store.can('employee.read')) {
      <ui-empty-state icon="lock" heading="No access" description="Your role does not allow viewing employees." />
    } @else {
      <div class="employees">
        <ui-panel heading="Employees" [subheading]="total() + ' employee(s)'" [flush]="true">
          <ng-container panelActions>
            @if (store.can('employee.create')) {
              <button type="button" class="btn btn--primary btn--sm" (click)="creating.set(true)"><ui-icon name="plus" [size]="14" /> New employee</button>
            }
          </ng-container>
          <div class="filters">
            <ui-search-input class="filters__search" [(value)]="query" placeholder="Search name, e-mail or number" label="Search employees" />
            <label class="sr-only" for="f-status">Status</label>
            <select id="f-status" class="select" (change)="setStatus($event)">
              <option value="">All statuses</option>
              <option value="ACTIVE">Active</option><option value="ON_LEAVE">On leave</option><option value="INACTIVE">Inactive</option>
            </select>
            <label class="sr-only" for="f-department">Department</label>
            <select id="f-department" class="select" (change)="setDepartment($event)">
              <option value="">All departments</option>
              @for (row of store.departments(); track row.department.id) {
                <option [value]="row.department.id">{{ '— '.repeat(row.depth) }}{{ row.department.name }}</option>
              }
            </select>
            <label class="sr-only" for="f-position">Position</label>
            <select id="f-position" class="select" (change)="setPosition($event)">
              <option value="">All positions</option>
              @for (position of store.positions(); track position.id) { <option [value]="position.id">{{ position.title }}</option> }
            </select>
          </div>

          @if (list.error(); as message) {
            <ui-empty-state icon="circle-alert" tone="danger" heading="Employees unavailable" [description]="message" [compact]="true" />
          } @else if (list.loading() && !list.value()) {
            <ui-skeleton [rows]="6" />
          } @else if ((list.value()?.content ?? []).length === 0) {
            <ui-empty-state icon="users" heading="No employee found" [compact]="true"
              [description]="hasFilters() ? 'No employee matches these filters.' : 'Create the first employee profile of this organization.'" />
          } @else {
            <div class="table-wrap">
              <table class="table">
                <thead><tr><th>Name</th><th>Position</th><th>Department</th><th>Manager</th><th>Status</th><th>Login</th></tr></thead>
                <tbody>
                  @for (employee of list.value()?.content ?? []; track employee.id) {
                    <tr class="row" [class.is-selected]="employee.id === selectedId()" tabindex="0"
                      (click)="selectedId.set(employee.id)" (keydown.enter)="selectedId.set(employee.id)">
                      <td><div class="name">{{ employee.displayName }}</div><div class="text-tertiary">{{ employee.employeeNumber ?? employee.professionalEmail ?? '' }}</div></td>
                      <td>{{ employee.position?.title ?? '—' }}</td>
                      <td>{{ employee.department?.name ?? '—' }}</td>
                      <td>{{ employee.manager?.displayName ?? '—' }}</td>
                      <td><ui-badge [label]="label[employee.status]" [tone]="tone[employee.status]" /></td>
                      <td>{{ employee.membershipStatus ? (employee.membershipStatus === 'ACTIVE' ? 'Yes' : 'Suspended') : 'No' }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            <nav class="pager" aria-label="Employee pages">
              <button type="button" class="btn btn--ghost btn--sm" [disabled]="page() === 0" (click)="page.set(page() - 1)">Previous</button>
              <span>Page {{ page() + 1 }} of {{ totalPages() }}</span>
              <button type="button" class="btn btn--ghost btn--sm" [disabled]="page() + 1 >= totalPages()" (click)="page.set(page() + 1)">Next</button>
            </nav>
          }
        </ui-panel>

        @if (selected(); as employee) {
          <ui-panel [heading]="employee.displayName" [subheading]="employee.professionalEmail ?? 'No professional e-mail'">
            <ng-container panelActions>
              <ui-badge [label]="label[employee.status]" [tone]="tone[employee.status]" />
              @if (store.can('employee.manage')) {
                <button type="button" class="btn btn--secondary btn--sm" (click)="editing.set(employee)">Edit</button>
              }
            </ng-container>
            <dl class="meta">
              <dt>Employee number</dt><dd>{{ employee.employeeNumber ?? '—' }}</dd>
              <dt>Start date</dt><dd>{{ employee.startDate }}{{ employee.endDate ? ' → ' + employee.endDate : '' }}</dd>
              <dt>Direct reports</dt><dd>{{ employee.directReportCount }}</dd>
              <dt>Platform login</dt><dd>{{ employee.membershipId ? 'Linked (' + employee.membershipStatus?.toLowerCase() + ')' : 'None' }}</dd>
            </dl>
            @if (employee.description) { <p>{{ employee.description }}</p> }

            @if (store.can('employee.manage')) {
              <div class="assign">
                <label for="a-department">Department</label>
                <select id="a-department" class="select" (change)="assignDepartment(employee, $event)">
                  <option value="" [selected]="!employee.department">None</option>
                  @for (row of store.activeDepartments(); track row.department.id) {
                    <option [value]="row.department.id" [selected]="row.department.id === employee.department?.id">{{ '— '.repeat(row.depth) }}{{ row.department.name }}</option>
                  }
                </select>
                <label for="a-position">Position</label>
                <select id="a-position" class="select" (change)="assignPosition(employee, $event)">
                  <option value="" [selected]="!employee.position">None</option>
                  @for (position of store.activePositions(); track position.id) {
                    <option [value]="position.id" [selected]="position.id === employee.position?.id">{{ position.title }}</option>
                  }
                </select>
                <label for="a-manager">Manager</label>
                <select id="a-manager" class="select" (change)="assignManager(employee, $event)">
                  <option value="" [selected]="!employee.manager">No manager</option>
                  @for (candidate of managerCandidates(employee); track candidate.id) {
                    <option [value]="candidate.id" [selected]="candidate.id === employee.manager?.id">{{ candidate.displayName }}</option>
                  }
                </select>
                @if (store.can('member.read')) {
                  <label for="a-member">Platform member</label>
                  <select id="a-member" class="select" (change)="linkMember(employee, $event)" (focus)="loadMembers()">
                    <option value="" [selected]="!employee.membershipId">No platform login</option>
                    @if (employee.membershipId && !memberOptions().length) { <option [value]="employee.membershipId" selected>Linked member</option> }
                    @for (member of memberOptions(); track member.membershipId) {
                      <option [value]="member.membershipId" [selected]="member.membershipId === employee.membershipId">{{ member.displayName ?? member.email }}</option>
                    }
                  </select>
                }
              </div>
            }

            <div class="status-actions">
              @if (employee.status !== 'ACTIVE' && canChange(employee, 'ACTIVE')) {
                <button type="button" class="btn btn--secondary btn--sm" (click)="changeStatus(employee, 'ACTIVE')">{{ employee.status === 'INACTIVE' ? 'Reactivate' : 'Back from leave' }}</button>
              }
              @if (employee.status === 'ACTIVE' && canChange(employee, 'ON_LEAVE')) {
                <button type="button" class="btn btn--secondary btn--sm" (click)="changeStatus(employee, 'ON_LEAVE')">Put on leave</button>
              }
              @if (employee.status !== 'INACTIVE' && canChange(employee, 'INACTIVE')) {
                <button type="button" class="btn btn--ghost btn--sm danger" (click)="deactivate(employee)">Deactivate</button>
              }
            </div>

            <h3 class="section">Teams</h3>
            @if (!store.can('team.read')) {
              <p class="text-tertiary">Your role does not allow viewing teams.</p>
            } @else {
              <ul class="plain">
                @for (team of teams.value() ?? []; track team.id) { <li>{{ team.name }}</li> }
                @empty { <li class="text-tertiary">{{ teams.loading() ? 'Loading…' : 'Not a member of any team.' }}</li> }
              </ul>
            }
          </ui-panel>
        } @else {
          <ui-empty-state icon="users" heading="Select an employee" [compact]="true"
            description="Choose an employee to see and change their assignments, status and teams." />
        }
      </div>
    }
    <app-wf-employee-form [open]="creating() || editing() !== null" [employee]="editing()" (closed)="closeForm()" (saved)="onSaved($event)" />
  `,
  styles: `
    .employees { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(300px, 1fr); gap: var(--space-5); align-items: start; }
    .filters { display: flex; flex-wrap: wrap; gap: var(--space-2); padding: var(--space-3); }
    .filters__search { flex: 1 1 220px; }
    .row { cursor: pointer; } .row:hover, .row.is-selected { background: var(--color-surface-hover); }
    .name { font-weight: var(--weight-medium); }
    .pager { display: flex; align-items: center; justify-content: flex-end; gap: var(--space-3); padding: var(--space-3); color: var(--color-text-secondary); }
    .meta { display: grid; grid-template-columns: max-content 1fr; gap: var(--space-1) var(--space-4); margin: 0 0 var(--space-3); }
    .meta dt { color: var(--color-text-tertiary); } .meta dd { margin: 0; }
    .assign { display: grid; grid-template-columns: max-content 1fr; gap: var(--space-2) var(--space-3); align-items: center; margin: var(--space-3) 0; }
    .status-actions { display: flex; gap: var(--space-2); margin: var(--space-3) 0; }
    .section { margin: var(--space-4) 0 var(--space-2); font-size: var(--text-md); }
    .plain { list-style: none; margin: 0; padding: 0; } .danger { color: var(--color-danger); }
    @media (max-width: 1000px) { .employees { grid-template-columns: minmax(0, 1fr); } }
  `,
})
export class WfEmployees {
  protected readonly store = inject(WorkforceStore);
  private readonly api = inject(WorkforceApi);
  private readonly identity = inject(IdentityApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly label = EMPLOYMENT_LABEL;
  protected readonly tone = EMPLOYMENT_TONE;

  protected readonly query = signal('');
  private readonly debouncedQuery = signal('');
  protected readonly status = signal<EmploymentStatus | ''>('');
  protected readonly departmentId = signal('');
  protected readonly positionId = signal('');
  protected readonly page = signal(0);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly creating = signal(false);
  protected readonly editing = signal<EmployeeDto | null>(null);
  private readonly members = signal<readonly MemberDto[]>([]);
  protected readonly memberOptions = this.members.asReadonly();

  protected readonly list = orgResource(
    this.store.organizationId,
    () => this.store.can('employee.read'),
    () => ({ q: this.debouncedQuery(), status: this.status(), departmentId: this.departmentId(), positionId: this.positionId(), page: this.page() }),
    (organizationId, key) =>
      this.api.listEmployees(organizationId, {
        ...(key.q ? { q: key.q } : {}),
        ...(key.status ? { status: key.status } : {}),
        ...(key.departmentId ? { departmentId: key.departmentId } : {}),
        ...(key.positionId ? { positionId: key.positionId } : {}),
        page: key.page,
        size: PAGE_SIZE,
      }),
  );
  protected readonly total = computed(() => this.list.value()?.totalElements ?? 0);
  protected readonly totalPages = computed(() => Math.max(1, this.list.value()?.totalPages ?? 1));
  protected readonly hasFilters = computed(
    () => !!(this.debouncedQuery() || this.status() || this.departmentId() || this.positionId()),
  );

  /** The selected employee, only while it belongs to the page of the current organization. */
  protected readonly selected = computed<EmployeeDto | null>(() => {
    const id = this.selectedId();
    return this.list.value()?.content.find((e) => e.id === id) ?? null;
  });
  protected readonly teams = orgResource(
    this.store.organizationId,
    () => this.selected() !== null && this.store.can('team.read'),
    () => this.selectedId(),
    (organizationId, id) => this.api.teamsOfEmployee(organizationId, id ?? ''),
  );

  constructor() {
    // Debounce the search box, and go back to the first page whenever a filter changes.
    effect((onCleanup) => {
      const value = this.query().trim();
      const timer = setTimeout(() => {
        this.debouncedQuery.set(value);
        this.page.set(0);
      }, 250);
      onCleanup(() => clearTimeout(timer));
    });
    // Switching organization clears the selection and the page.
    effect(() => {
      this.store.organizationId();
      untracked(() => {
        this.selectedId.set(null);
        this.page.set(0);
        this.members.set([]);
      });
    });
  }

  protected setStatus(event: Event): void {
    this.status.set(value(event) as EmploymentStatus | '');
    this.page.set(0);
  }

  protected setDepartment(event: Event): void {
    this.departmentId.set(value(event));
    this.page.set(0);
  }

  protected setPosition(event: Event): void {
    this.positionId.set(value(event));
    this.page.set(0);
  }

  protected managerCandidates(employee: EmployeeDto) {
    return this.store.currentEmployees().filter((candidate) => candidate.id !== employee.id);
  }

  /** Mirrors the API rule: INACTIVE needs employee.deactivate, ACTIVE/ON_LEAVE between themselves employee.manage. */
  protected canChange(employee: EmployeeDto, target: EmploymentStatus): boolean {
    return employee.status === 'INACTIVE' || target === 'INACTIVE'
      ? this.store.can('employee.deactivate')
      : this.store.can('employee.manage');
  }

  protected closeForm(): void {
    this.creating.set(false);
    this.editing.set(null);
  }

  protected onSaved(employee: EmployeeDto): void {
    const wasCreating = this.creating();
    this.closeForm();
    this.list.reload();
    if (wasCreating) {
      this.selectedId.set(employee.id);
    }
  }

  protected async loadMembers(): Promise<void> {
    const organizationId = this.store.organizationId();
    if (!organizationId || this.members().length > 0) {
      return;
    }
    try {
      this.members.set((await this.identity.listMembers(organizationId, 0, 100, true)).content);
    } catch {
      this.members.set([]);
    }
  }

  protected assignDepartment(employee: EmployeeDto, event: Event): Promise<void> {
    return this.run('Department updated', (id) => this.api.assignEmployeeDepartment(id, employee.id, idOrNull(value(event))));
  }

  protected assignPosition(employee: EmployeeDto, event: Event): Promise<void> {
    return this.run('Position updated', (id) => this.api.assignEmployeePosition(id, employee.id, idOrNull(value(event))));
  }

  protected assignManager(employee: EmployeeDto, event: Event): Promise<void> {
    return this.run('Manager updated', (id) => this.api.assignEmployeeManager(id, employee.id, idOrNull(value(event))));
  }

  protected linkMember(employee: EmployeeDto, event: Event): Promise<void> {
    return this.run('Platform login updated', (id) => this.api.linkEmployeeMembership(id, employee.id, idOrNull(value(event))));
  }

  protected changeStatus(employee: EmployeeDto, target: EmploymentStatus): Promise<void> {
    return this.run('Status updated', (id) => this.api.changeEmployeeStatus(id, employee.id, target));
  }

  protected async deactivate(employee: EmployeeDto): Promise<void> {
    const answer = await this.confirm.ask({
      title: `Deactivate ${employee.displayName}?`,
      message: 'The professional record is kept. The employee leaves their teams and can no longer be a manager, head or leader.',
      confirmLabel: 'Deactivate',
      tone: 'danger',
    });
    if (answer.confirmed) {
      await this.changeStatus(employee, 'INACTIVE');
    }
  }

  private async run(success: string, change: (organizationId: string) => Promise<unknown>): Promise<void> {
    try {
      await this.store.mutate(change);
      this.list.reload();
      this.teams.reload();
      this.toast.success(success);
    } catch (error) {
      // The selects keep the chosen value: reload to show what the server really holds.
      this.list.reload();
      this.toast.error('Action failed', error);
    }
  }
}

function value(event: Event): string {
  return (event.target as HTMLSelectElement | HTMLInputElement).value;
}
