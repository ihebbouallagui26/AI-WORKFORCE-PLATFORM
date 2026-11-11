import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { Id, Organization, OrganizationUpdate, User, UserProfileUpdate } from '../../models';
import { WorkspaceApi } from '../api/workspace.api';
import { MockDatabase } from './mock-database';

@Injectable()
export class MockWorkspaceApi extends WorkspaceApi {
  private readonly db = inject(MockDatabase);

  getCurrentUser(): Observable<User> {
    return this.db.respond(this.db.catalog.user);
  }

  updateProfile(update: UserProfileUpdate): Observable<User> {
    const next: User = { ...this.db.catalog.user, ...update };
    this.db.catalog.user = next;
    return this.db.respond(next);
  }

  listOrganizations(): Observable<Organization[]> {
    const ids = new Set(this.db.catalog.user.memberships.map((m) => m.organizationId));
    return this.db.respond(this.db.tables.organizations.filter((o) => ids.has(o.id)));
  }

  updateOrganization(id: Id, update: OrganizationUpdate): Observable<Organization> {
    const current = this.db.tables.organizations.find((o) => o.id === id);
    if (!current) {
      return this.db.notFound('Organization', id);
    }
    return this.db.respond(this.db.replace(this.db.tables.organizations, { ...current, ...update }));
  }
}
