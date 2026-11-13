import { inject, Injectable } from '@angular/core';
import { from, map, Observable, switchMap } from 'rxjs';
import { IdentityApi } from '../../identity/identity.api';
import { OrganizationSummaryDto } from '../../identity/identity.models';
import { AvatarTone, Id, Organization, OrganizationUpdate, User, UserProfileUpdate } from '../../models';
import { WorkspaceApi } from '../api/workspace.api';
import { DEMO_EMPLOYEE_ID } from './demo-scope';

/**
 * Workspace context backed by the Core API: the signed-in user (`/me`) and the organizations in
 * which they are an active member (`/organizations`). Fields the backend does not model yet
 * (industry, plan, deployment, job title) get neutral values.
 */
@Injectable()
export class BackendWorkspaceApi extends WorkspaceApi {
  private readonly api = inject(IdentityApi);
  private title = '';

  getCurrentUser(): Observable<User> {
    return from(Promise.all([this.api.me(), this.api.listOrganizations()])).pipe(
      map(([me, organizations]) => ({
        id: me.id,
        email: me.email,
        displayName: me.displayName,
        title: this.title,
        avatarTone: toneOf(me.id),
        // Demo domains (agents, projects, messages) act as the demo employee in every organization.
        memberships: organizations.content.map((o) => ({ organizationId: o.id, employeeId: DEMO_EMPLOYEE_ID })),
      })),
    );
  }

  updateProfile(update: UserProfileUpdate): Observable<User> {
    this.title = update.title;
    return from(this.api.updateMe(update.displayName)).pipe(switchMap(() => this.getCurrentUser()));
  }

  listOrganizations(): Observable<Organization[]> {
    return from(this.api.listOrganizations()).pipe(map((page) => page.content.map(toOrganization)));
  }

  updateOrganization(id: Id, update: OrganizationUpdate): Observable<Organization> {
    return from(this.api.updateOrganization(id, {
      name: update.name,
      description: update.description,
      timezone: update.timezone,
    })).pipe(map(toOrganization));
  }
}

export function toOrganization(dto: OrganizationSummaryDto): Organization {
  return {
    id: dto.id,
    name: dto.name,
    slug: dto.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, ''),
    industry: '',
    description: dto.description,
    timezone: dto.timezone,
    plan: 'business',
    deployment: 'local',
    createdAt: dto.createdAt,
  };
}

function toneOf(id: string): AvatarTone {
  let hash = 0;
  for (const char of id) {
    hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  }
  return ((hash % 8) + 1) as AvatarTone;
}
