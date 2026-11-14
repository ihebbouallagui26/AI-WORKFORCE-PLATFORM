import { computed, inject, Injectable, resource } from '@angular/core';
import { AUTH_REQUIRED } from '../auth/session.service';
import { WorkspaceStore } from '../state/workspace.store';
import { IdentityApi } from './identity.api';
import { OrganizationDto, PermissionCode } from './identity.models';

/**
 * The caller's roles and permissions in the current organization, as computed by the Core API
 * (`GET /organizations/{id}`). Used to show or hide actions; the API enforces every permission
 * again on each request, so this is a usability aid, never a security boundary.
 */
@Injectable({ providedIn: 'root' })
export class AccessStore {
  private readonly api = inject(IdentityApi);
  private readonly workspace = inject(WorkspaceStore);
  readonly enabled = inject(AUTH_REQUIRED);

  private readonly details = resource<OrganizationDto | null, string | undefined>({
    params: () => (this.enabled ? (this.workspace.currentOrganizationId() ?? undefined) : undefined),
    loader: ({ params }) => (params ? this.api.getOrganization(params) : Promise.resolve(null)),
  });

  readonly organization = computed(() => (this.details.hasValue() ? this.details.value() : null));
  readonly loading = this.details.isLoading;
  readonly permissions = computed<ReadonlySet<PermissionCode>>(
    () => new Set(this.organization()?.myPermissions ?? []),
  );
  readonly roles = computed(() => this.organization()?.myRoles ?? []);

  can(permission: PermissionCode): boolean {
    return this.permissions().has(permission);
  }

  reload(): void {
    this.details.reload();
  }
}
