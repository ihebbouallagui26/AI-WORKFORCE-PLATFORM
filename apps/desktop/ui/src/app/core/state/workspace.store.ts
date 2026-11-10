import { computed, inject, Injectable, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { firstValueFrom } from 'rxjs';
import { WorkspaceApi } from '../data/api/workspace.api';
import { Id, MemberRef, Organization, OrganizationUpdate, User, UserProfileUpdate } from '../models';
import { PreferencesService } from '../services/preferences.service';
import { globalCollection, upsertById } from './collection';

/** Current account and organization context. Every org-scoped store derives from it. */
@Injectable({ providedIn: 'root' })
export class WorkspaceStore {
  private readonly api = inject(WorkspaceApi);
  private readonly preferences = inject(PreferencesService);

  private readonly userResource = rxResource<User | undefined, true>({
    params: () => true,
    stream: () => this.api.getCurrentUser(),
  });
  private readonly organizationList = globalCollection(() => this.api.listOrganizations());
  private readonly selectedOrganizationId = signal<Id | null>(this.preferences.get('lastOrganizationId'));

  readonly user = computed<User | null>(() =>
    this.userResource.hasValue() ? (this.userResource.value() ?? null) : null,
  );
  readonly organizations = this.organizationList.items;
  readonly loading = computed(() => this.organizationList.loading() || this.userResource.isLoading());
  readonly error = this.organizationList.error;

  readonly currentOrganizationId = computed<Id | null>(() => {
    const organizations = this.organizations();
    const selected = this.selectedOrganizationId();
    if (selected && organizations.some((o) => o.id === selected)) {
      return selected;
    }
    return organizations[0]?.id ?? null;
  });

  readonly currentOrganization = computed<Organization | null>(
    () => this.organizations().find((o) => o.id === this.currentOrganizationId()) ?? null,
  );

  /** The signed-in user as a workforce member of the current organization. */
  readonly currentMemberRef = computed<MemberRef | null>(() => {
    const user = this.user();
    const organizationId = this.currentOrganizationId();
    const membership = user?.memberships.find((m) => m.organizationId === organizationId);
    return membership ? { kind: 'human', id: membership.employeeId } : null;
  });

  switchOrganization(organizationId: Id): void {
    this.selectedOrganizationId.set(organizationId);
    this.preferences.set('lastOrganizationId', organizationId);
  }

  async updateOrganization(update: OrganizationUpdate): Promise<Organization> {
    const id = this.currentOrganizationId();
    if (!id) {
      throw new Error('No organization selected.');
    }
    const updated = await firstValueFrom(this.api.updateOrganization(id, update));
    this.organizationList.mutate((items) => upsertById(items, updated));
    return updated;
  }

  async updateProfile(update: UserProfileUpdate): Promise<User> {
    const updated = await firstValueFrom(this.api.updateProfile(update));
    this.userResource.set(updated);
    return updated;
  }
}
