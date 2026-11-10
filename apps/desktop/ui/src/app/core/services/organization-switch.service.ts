import { inject, Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { Id } from '../models';
import { WorkspaceStore } from '../state/workspace.store';

/**
 * Switches the current organization and leaves any view that referenced entities of the
 * previous organization (detail pages, query-string selections) by returning to the root
 * of the current section, e.g. `/agents/agt-nova` → `/agents`.
 */
@Injectable({ providedIn: 'root' })
export class OrganizationSwitchService {
  private readonly workspace = inject(WorkspaceStore);
  private readonly router = inject(Router);

  /** Returns false when the organization was already selected or is unknown. */
  switchTo(organizationId: Id): boolean {
    const known = this.workspace.organizations().some((o) => o.id === organizationId);
    if (!known || organizationId === this.workspace.currentOrganizationId()) {
      return false;
    }
    this.workspace.switchOrganization(organizationId);
    const section = this.router.parseUrl(this.router.url).root.children['primary']?.segments[0]?.path ?? 'dashboard';
    // Settings sections are organization-agnostic and keep their sub-route.
    const target = section === 'settings' ? this.router.url.split('?')[0] : `/${section}`;
    void this.router.navigateByUrl(target);
    return true;
  }
}
