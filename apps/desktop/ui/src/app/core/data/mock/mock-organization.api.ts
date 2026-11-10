import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { Department, DepartmentDraft, Employee, Id, Role, Team } from '../../models';
import { OrganizationApi } from '../api/organization.api';
import { MockDatabase } from './mock-database';

@Injectable()
export class MockOrganizationApi extends OrganizationApi {
  private readonly db = inject(MockDatabase);

  listDepartments(organizationId: Id): Observable<Department[]> {
    return this.db.respond(this.db.tables.departments.filter((d) => d.organizationId === organizationId));
  }

  createDepartment(organizationId: Id, draft: DepartmentDraft): Observable<Department> {
    const department: Department = {
      ...draft,
      id: this.db.nextId('dep'),
      organizationId,
      createdAt: this.db.now(),
    };
    this.db.tables.departments.push(department);
    return this.db.respond(department);
  }

  listRoles(organizationId: Id): Observable<Role[]> {
    return this.db.respond(this.db.tables.roles.filter((r) => r.organizationId === organizationId));
  }

  listTeams(organizationId: Id): Observable<Team[]> {
    return this.db.respond(this.db.tables.teams.filter((t) => t.organizationId === organizationId));
  }

  listEmployees(organizationId: Id): Observable<Employee[]> {
    return this.db.respond(this.db.tables.employees.filter((e) => e.organizationId === organizationId));
  }
}
