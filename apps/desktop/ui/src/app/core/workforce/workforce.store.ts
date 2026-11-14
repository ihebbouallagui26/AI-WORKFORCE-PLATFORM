import { computed, inject, Injectable, signal } from '@angular/core';
import { AccessStore } from '../identity/access.store';
import { PermissionCode } from '../identity/identity.models';
import { WorkspaceStore } from '../state/workspace.store';
import { orgResource } from './org-resource';
import { WorkforceApi } from './workforce.api';
import {
  DepartmentDto,
  DepartmentRow,
  DepartmentTreeNodeDto,
  EmployeeDto,
  PositionDto,
  TeamDto,
} from './workforce.models';

/** Page size used for the catalogs shown in full (departments, positions, teams, pickers). */
export const CATALOG_SIZE = 100;

/**
 * Organization-scoped state of the workforce (departments, positions, teams and the employee
 * picker). Every collection is keyed by the current organization: switching organization
 * reloads everything, and results of the previous organization are never exposed.
 */
@Injectable({ providedIn: 'root' })
export class WorkforceStore {
  private readonly api = inject(WorkforceApi);
  private readonly workspace = inject(WorkspaceStore);
  readonly access = inject(AccessStore);

  readonly organizationId = this.workspace.currentOrganizationId;
  /** Include archived departments, positions and teams in the catalogs. */
  readonly showArchived = signal(false);

  private readonly tree = orgResource(
    this.organizationId,
    () => this.can('department.read'),
    () => this.showArchived(),
    (id, archived) => this.api.departmentTree(id, archived),
  );
  private readonly positionList = orgResource(
    this.organizationId,
    () => this.can('position.read'),
    () => this.showArchived(),
    (id, archived) => this.api.listPositions(id, { size: CATALOG_SIZE, ...(archived ? {} : { status: 'ACTIVE' as const }) }),
  );
  private readonly teamList = orgResource(
    this.organizationId,
    () => this.can('team.read'),
    () => this.showArchived(),
    (id, archived) => this.api.listTeams(id, { size: CATALOG_SIZE, ...(archived ? {} : { status: 'ACTIVE' as const }) }),
  );
  private readonly employeePicker = orgResource(
    this.organizationId,
    () => this.can('employee.read'),
    () => null,
    (id) => this.api.listEmployees(id, { size: CATALOG_SIZE }),
  );

  readonly departmentTree = computed<readonly DepartmentTreeNodeDto[]>(() => this.tree.value() ?? []);
  /** Departments in hierarchy order, with their depth. */
  readonly departments = computed<readonly DepartmentRow[]>(() => {
    const rows: DepartmentRow[] = [];
    const walk = (nodes: readonly DepartmentTreeNodeDto[], depth: number) =>
      nodes.forEach((node) => {
        rows.push({ department: node.department, depth });
        walk(node.children, depth + 1);
      });
    walk(this.departmentTree(), 0);
    return rows;
  });
  readonly activeDepartments = computed<readonly DepartmentRow[]>(() =>
    this.departments().filter((row) => row.department.status === 'ACTIVE'),
  );
  readonly positions = computed<readonly PositionDto[]>(() => this.positionList.value()?.content ?? []);
  readonly activePositions = computed(() => this.positions().filter((p) => p.status === 'ACTIVE'));
  readonly teams = computed<readonly TeamDto[]>(() => this.teamList.value()?.content ?? []);
  /** Employees (first page) offered by pickers; people who are not inactive only. */
  readonly currentEmployees = computed<readonly EmployeeDto[]>(() =>
    (this.employeePicker.value()?.content ?? []).filter((e) => e.status !== 'INACTIVE'),
  );
  readonly totalEmployees = computed(() => this.employeePicker.value()?.totalElements ?? 0);

  readonly departmentById = computed(() => new Map<string, DepartmentDto>(this.departments().map((r) => [r.department.id, r.department])));

  readonly loading = computed(
    () => this.tree.loading() || this.positionList.loading() || this.teamList.loading() || this.employeePicker.loading(),
  );
  readonly error = computed(
    () => this.tree.error() ?? this.positionList.error() ?? this.teamList.error() ?? this.employeePicker.error(),
  );

  can(permission: PermissionCode): boolean {
    return this.access.can(permission);
  }

  /**
   * Runs a change against the organization selected when it starts, then refreshes the shared
   * collections. Resolves with the API result; failures propagate to the caller.
   */
  async mutate<T>(change: (organizationId: string) => Promise<T>): Promise<T> {
    const organizationId = this.organizationId();
    if (!organizationId) {
      throw new Error('No organization selected.');
    }
    const result = await change(organizationId);
    this.reload();
    return result;
  }

  reload(): void {
    this.tree.reload();
    this.positionList.reload();
    this.teamList.reload();
    this.employeePicker.reload();
  }
}
