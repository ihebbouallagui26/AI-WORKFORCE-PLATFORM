import { Observable } from 'rxjs';
import { Department, DepartmentDraft, Employee, Id, Role, Team } from '../../models';

/** Organizational structure: departments, roles, teams and human employees. */
export abstract class OrganizationApi {
  abstract listDepartments(organizationId: Id): Observable<Department[]>;
  abstract createDepartment(organizationId: Id, draft: DepartmentDraft): Observable<Department>;
  abstract listRoles(organizationId: Id): Observable<Role[]>;
  abstract listTeams(organizationId: Id): Observable<Team[]>;
  abstract listEmployees(organizationId: Id): Observable<Employee[]>;
}
