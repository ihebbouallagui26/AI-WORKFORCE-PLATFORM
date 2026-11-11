import { computed, inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { OrganizationApi } from '../data/api/organization.api';
import { Department, DepartmentDraft, Id, Role } from '../models';
import { indexById, orgCollection, requireOrganization, upsertById } from './collection';
import { WorkspaceStore } from './workspace.store';

export interface DepartmentNode {
  readonly department: Department;
  readonly depth: number;
  readonly children: readonly DepartmentNode[];
}

@Injectable({ providedIn: 'root' })
export class OrganizationStore {
  private readonly api = inject(OrganizationApi);
  private readonly organizationId = inject(WorkspaceStore).currentOrganizationId;

  private readonly departmentList = orgCollection(this.organizationId, (id) => this.api.listDepartments(id));
  private readonly roleList = orgCollection(this.organizationId, (id) => this.api.listRoles(id));
  private readonly teamList = orgCollection(this.organizationId, (id) => this.api.listTeams(id));
  private readonly employeeList = orgCollection(this.organizationId, (id) => this.api.listEmployees(id));

  readonly departments = this.departmentList.items;
  readonly roles = this.roleList.items;
  readonly teams = this.teamList.items;
  readonly employees = this.employeeList.items;

  readonly loading = computed(
    () => this.departmentList.loading() || this.employeeList.loading() || this.roleList.loading(),
  );
  readonly error = computed(() => this.departmentList.error() ?? this.employeeList.error() ?? this.roleList.error());

  readonly departmentById = computed(() => indexById(this.departments()));
  readonly roleById = computed(() => indexById(this.roles()));
  readonly employeeById = computed(() => indexById(this.employees()));

  /** Departments arranged as a tree, ordered by name at each level. */
  readonly departmentTree = computed<readonly DepartmentNode[]>(() => {
    const all = [...this.departments()].sort((a, b) => a.name.localeCompare(b.name));
    const ids = new Set(all.map((d) => d.id));
    const build = (parentId: Id | null, depth: number): DepartmentNode[] =>
      all
        .filter((d) => (parentId === null ? d.parentId === null || !ids.has(d.parentId) : d.parentId === parentId))
        .map((department) => ({ department, depth, children: build(department.id, depth + 1) }));
    return build(null, 0);
  });

  /** Depth-first flattening of the department tree, convenient for lists and selects. */
  readonly departmentsFlat = computed<readonly DepartmentNode[]>(() => {
    const result: DepartmentNode[] = [];
    const walk = (nodes: readonly DepartmentNode[]) =>
      nodes.forEach((node) => {
        result.push(node);
        walk(node.children);
      });
    walk(this.departmentTree());
    return result;
  });

  rolesForDepartment(departmentId: Id | null): readonly Role[] {
    return departmentId ? this.roles().filter((r) => r.departmentId === departmentId) : this.roles();
  }

  async createDepartment(draft: DepartmentDraft): Promise<Department> {
    const organizationId = requireOrganization(this.organizationId);
    const created = await firstValueFrom(this.api.createDepartment(organizationId, draft));
    this.departmentList.mutate(organizationId, (items) => upsertById(items, created));
    return created;
  }

  reload(): void {
    this.departmentList.reload();
    this.roleList.reload();
    this.teamList.reload();
    this.employeeList.reload();
  }
}
