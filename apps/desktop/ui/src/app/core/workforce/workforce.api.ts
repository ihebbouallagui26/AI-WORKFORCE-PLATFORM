import { inject, Injectable } from '@angular/core';
import { BackendClient } from '../identity/backend-client';
import {
  DepartmentDraft,
  DepartmentDto,
  DepartmentTreeNodeDto,
  EmployeeDraft,
  EmployeeDto,
  EmployeeQuery,
  EmployeeUpdate,
  EmploymentStatus,
  Page,
  PositionDraft,
  PositionDto,
  TeamDetailDto,
  TeamDraft,
  TeamDto,
  TeamMemberDto,
  ArchiveStatus,
} from './workforce.models';

type Query = Record<string, string | number | boolean>;

/**
 * Typed client of the workforce endpoints of the Core API (departments, positions, employees,
 * teams), over the Electron main-process proxy. Organization ids only select an organization:
 * the API checks membership and permissions on every call.
 */
@Injectable({ providedIn: 'root' })
export class WorkforceApi {
  private readonly http = inject(BackendClient);

  // ---------------------------------------------------------------- departments

  departmentTree(organizationId: string, includeArchived = false): Promise<DepartmentTreeNodeDto[]> {
    return this.http.get(`${org(organizationId)}/departments/tree`, { includeArchived });
  }

  listDepartments(organizationId: string, query: { q?: string; status?: ArchiveStatus; page?: number; size?: number } = {}): Promise<Page<DepartmentDto>> {
    return this.http.get(`${org(organizationId)}/departments`, clean(query));
  }

  createDepartment(organizationId: string, draft: DepartmentDraft): Promise<DepartmentDto> {
    return this.http.post(`${org(organizationId)}/departments`, draft);
  }

  updateDepartment(organizationId: string, id: string, update: { name?: string; code?: string; description?: string; expectedVersion?: number }): Promise<DepartmentDto> {
    return this.http.patch(`${org(organizationId)}/departments/${uuid(id)}`, update);
  }

  moveDepartment(organizationId: string, id: string, parentId: string | null): Promise<DepartmentDto> {
    return this.http.put(`${org(organizationId)}/departments/${uuid(id)}/parent`, { parentId });
  }

  assignDepartmentHead(organizationId: string, id: string, employeeId: string | null): Promise<DepartmentDto> {
    return this.http.put(`${org(organizationId)}/departments/${uuid(id)}/head`, { employeeId });
  }

  archiveDepartment(organizationId: string, id: string): Promise<DepartmentDto> {
    return this.http.post(`${org(organizationId)}/departments/${uuid(id)}/archive`);
  }

  restoreDepartment(organizationId: string, id: string): Promise<DepartmentDto> {
    return this.http.post(`${org(organizationId)}/departments/${uuid(id)}/restore`);
  }

  deleteDepartment(organizationId: string, id: string): Promise<void> {
    return this.http.delete(`${org(organizationId)}/departments/${uuid(id)}`);
  }

  // ---------------------------------------------------------------- positions

  listPositions(organizationId: string, query: { q?: string; status?: ArchiveStatus; departmentId?: string; page?: number; size?: number } = {}): Promise<Page<PositionDto>> {
    return this.http.get(`${org(organizationId)}/positions`, clean(query));
  }

  createPosition(organizationId: string, draft: PositionDraft): Promise<PositionDto> {
    return this.http.post(`${org(organizationId)}/positions`, draft);
  }

  updatePosition(organizationId: string, id: string, update: { title?: string; code?: string; description?: string; expectedVersion?: number }): Promise<PositionDto> {
    return this.http.patch(`${org(organizationId)}/positions/${uuid(id)}`, update);
  }

  changePositionDepartment(organizationId: string, id: string, departmentId: string | null): Promise<PositionDto> {
    return this.http.put(`${org(organizationId)}/positions/${uuid(id)}/department`, { departmentId });
  }

  archivePosition(organizationId: string, id: string): Promise<PositionDto> {
    return this.http.post(`${org(organizationId)}/positions/${uuid(id)}/archive`);
  }

  restorePosition(organizationId: string, id: string): Promise<PositionDto> {
    return this.http.post(`${org(organizationId)}/positions/${uuid(id)}/restore`);
  }

  deletePosition(organizationId: string, id: string): Promise<void> {
    return this.http.delete(`${org(organizationId)}/positions/${uuid(id)}`);
  }

  // ---------------------------------------------------------------- employees

  listEmployees(organizationId: string, query: EmployeeQuery = {}): Promise<Page<EmployeeDto>> {
    return this.http.get(`${org(organizationId)}/employees`, clean({ ...query }));
  }

  getEmployee(organizationId: string, id: string): Promise<EmployeeDto> {
    return this.http.get(`${org(organizationId)}/employees/${uuid(id)}`);
  }

  createEmployee(organizationId: string, draft: EmployeeDraft): Promise<EmployeeDto> {
    return this.http.post(`${org(organizationId)}/employees`, draft);
  }

  updateEmployee(organizationId: string, id: string, update: EmployeeUpdate): Promise<EmployeeDto> {
    return this.http.patch(`${org(organizationId)}/employees/${uuid(id)}`, update);
  }

  assignEmployeeDepartment(organizationId: string, id: string, departmentId: string | null): Promise<EmployeeDto> {
    return this.http.put(`${org(organizationId)}/employees/${uuid(id)}/department`, { departmentId });
  }

  assignEmployeePosition(organizationId: string, id: string, positionId: string | null): Promise<EmployeeDto> {
    return this.http.put(`${org(organizationId)}/employees/${uuid(id)}/position`, { positionId });
  }

  assignEmployeeManager(organizationId: string, id: string, managerId: string | null): Promise<EmployeeDto> {
    return this.http.put(`${org(organizationId)}/employees/${uuid(id)}/manager`, { managerId });
  }

  linkEmployeeMembership(organizationId: string, id: string, membershipId: string | null): Promise<EmployeeDto> {
    return this.http.put(`${org(organizationId)}/employees/${uuid(id)}/membership`, { membershipId });
  }

  changeEmployeeStatus(organizationId: string, id: string, status: EmploymentStatus): Promise<EmployeeDto> {
    return this.http.put(`${org(organizationId)}/employees/${uuid(id)}/status`, { status });
  }

  teamsOfEmployee(organizationId: string, id: string): Promise<TeamDto[]> {
    return this.http.get(`${org(organizationId)}/employees/${uuid(id)}/teams`);
  }

  // ---------------------------------------------------------------- teams

  listTeams(organizationId: string, query: { q?: string; status?: ArchiveStatus; departmentId?: string; page?: number; size?: number } = {}): Promise<Page<TeamDto>> {
    return this.http.get(`${org(organizationId)}/teams`, clean(query));
  }

  getTeam(organizationId: string, id: string): Promise<TeamDetailDto> {
    return this.http.get(`${org(organizationId)}/teams/${uuid(id)}`);
  }

  createTeam(organizationId: string, draft: TeamDraft): Promise<TeamDto> {
    return this.http.post(`${org(organizationId)}/teams`, draft);
  }

  updateTeam(organizationId: string, id: string, update: { name?: string; description?: string; expectedVersion?: number }): Promise<TeamDto> {
    return this.http.patch(`${org(organizationId)}/teams/${uuid(id)}`, update);
  }

  changeTeamDepartment(organizationId: string, id: string, departmentId: string | null): Promise<TeamDto> {
    return this.http.put(`${org(organizationId)}/teams/${uuid(id)}/department`, { departmentId });
  }

  assignTeamLeader(organizationId: string, id: string, employeeId: string | null): Promise<TeamDto> {
    return this.http.put(`${org(organizationId)}/teams/${uuid(id)}/leader`, { employeeId });
  }

  archiveTeam(organizationId: string, id: string): Promise<TeamDto> {
    return this.http.post(`${org(organizationId)}/teams/${uuid(id)}/archive`);
  }

  restoreTeam(organizationId: string, id: string): Promise<TeamDto> {
    return this.http.post(`${org(organizationId)}/teams/${uuid(id)}/restore`);
  }

  addTeamMember(organizationId: string, id: string, employeeId: string): Promise<TeamMemberDto> {
    return this.http.post(`${org(organizationId)}/teams/${uuid(id)}/members`, { employeeId });
  }

  removeTeamMember(organizationId: string, id: string, employeeId: string): Promise<void> {
    return this.http.delete(`${org(organizationId)}/teams/${uuid(id)}/members/${uuid(employeeId)}`);
  }
}

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** Path segments are validated: an id can never inject another path. */
function uuid(value: string): string {
  if (!UUID.test(value)) {
    throw new Error('Invalid identifier');
  }
  return value;
}

function org(organizationId: string): string {
  return `/api/v1/organizations/${uuid(organizationId)}`;
}

/** Drops undefined and blank values so they are not sent as filters. */
function clean(query: object): Query {
  const result: Query = {};
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      result[key] = value as string | number | boolean;
    }
  }
  return result;
}
