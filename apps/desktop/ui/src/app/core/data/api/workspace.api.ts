import { Observable } from 'rxjs';
import { Id, Organization, OrganizationUpdate, User, UserProfileUpdate } from '../../models';

/** Account and organization context. Backed by `/me` and `/organizations` on the Core API. */
export abstract class WorkspaceApi {
  abstract getCurrentUser(): Observable<User>;
  abstract updateProfile(update: UserProfileUpdate): Observable<User>;
  abstract listOrganizations(): Observable<Organization[]>;
  abstract updateOrganization(id: Id, update: OrganizationUpdate): Observable<Organization>;
}
