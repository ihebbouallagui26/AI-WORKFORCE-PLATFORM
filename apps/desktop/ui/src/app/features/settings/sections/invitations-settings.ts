import { ChangeDetectionStrategy, Component, computed, inject, resource, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AccessStore } from '../../../core/identity/access.store';
import { IdentityApi } from '../../../core/identity/identity.api';
import { InvitationDto, RoleDto } from '../../../core/identity/identity.models';
import { ToastService } from '../../../core/services/toast.service';
import { WorkspaceStore } from '../../../core/state/workspace.store';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Panel } from '../../../shared/components/panel/panel';

/**
 * Invitations of the current organization. The invitation code is sent by e-mail only: it is
 * never displayed here nor returned by the API.
 */
@Component({
  selector: 'app-invitations-settings',
  imports: [ReactiveFormsModule, RouterLink, Panel, EmptyState],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="stack">
      @if (access.can('invitation.create')) {
        <ui-panel heading="Invite someone" subheading="An e-mail with a single-use code is sent. Only roles you can grant are offered.">
          <form class="form-grid" [formGroup]="form" (ngSubmit)="invite()" novalidate>
            <div class="field">
              <label class="field__label" for="invite-email">E-mail</label>
              <input id="invite-email" type="email" class="input" formControlName="email" maxlength="254" autocomplete="off" />
            </div>
            <div class="field">
              <label class="field__label" for="invite-role">Role</label>
              <select id="invite-role" class="select" formControlName="roleId">
                @for (role of grantableRoles(); track role.id) {
                  <option [value]="role.id">{{ role.name }}</option>
                }
              </select>
            </div>
            <div class="field field--full actions">
              <button type="submit" class="btn btn--primary" [disabled]="busy() || form.invalid">Send invitation</button>
            </div>
          </form>
        </ui-panel>
      }

      @if (access.can('invitation.read')) {
        <ui-panel heading="Pending invitations" [flush]="true">
          <div class="table-wrap">
            <table class="table">
              <thead><tr><th>E-mail</th><th>Role</th><th>Expires</th><th><span class="sr-only">Actions</span></th></tr></thead>
              <tbody>
                @for (invitation of invitations(); track invitation.id) {
                  <tr>
                    <td>{{ invitation.email }}</td>
                    <td>{{ roleName(invitation.roleId) }}</td>
                    <td>{{ invitation.expiresAt.slice(0, 16).replace('T', ' ') }} UTC</td>
                    <td class="actions-col">
                      @if (access.can('invitation.revoke')) {
                        <button type="button" class="btn btn--ghost btn--sm" (click)="revoke(invitation)">Revoke</button>
                      }
                    </td>
                  </tr>
                } @empty {
                  <tr><td colspan="4" class="text-tertiary">No pending invitation.</td></tr>
                }
              </tbody>
            </table>
          </div>
        </ui-panel>
      }

      <ui-panel heading="Received an invitation?">
        <p>Open <a routerLink="/invitations/accept">Accept an invitation</a> and paste the code from the e-mail.</p>
      </ui-panel>

      @if (!access.can('invitation.read') && !access.can('invitation.create')) {
        <ui-empty-state icon="lock" heading="No access" description="Your role does not allow managing invitations." [compact]="true" />
      }
    </div>
  `,
  styles: `
    .actions { flex-direction: row; justify-content: flex-end; }
    .actions-col { text-align: right; }
  `,
})
export class InvitationsSettings {
  protected readonly access = inject(AccessStore);
  private readonly api = inject(IdentityApi);
  private readonly workspace = inject(WorkspaceStore);
  private readonly toast = inject(ToastService);

  private readonly list = resource({
    params: () => (this.access.can('invitation.read') ? (this.workspace.currentOrganizationId() ?? undefined) : undefined),
    loader: ({ params }) => this.api.listInvitations(params),
  });
  private readonly roleList = resource({
    params: () => (this.access.can('role.read') ? (this.workspace.currentOrganizationId() ?? undefined) : undefined),
    loader: ({ params }) => this.api.listRoles(params),
  });

  protected readonly invitations = computed<readonly InvitationDto[]>(() => (this.list.hasValue() ? this.list.value().content : []));
  private readonly roles = computed<readonly RoleDto[]>(() => (this.roleList.hasValue() ? this.roleList.value() : []));
  /** Same rule as the API: OWNER needs ownership.manage, other roles need all their permissions. */
  protected readonly grantableRoles = computed(() =>
    this.roles().filter((role) =>
      role.code === 'OWNER' ? this.access.can('ownership.manage') : role.permissions.every((p) => this.access.can(p)),
    ),
  );
  protected readonly busy = signal(false);
  protected readonly form = inject(NonNullableFormBuilder).group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    roleId: ['', Validators.required],
  });

  protected roleName(roleId: string): string {
    return this.roles().find((r) => r.id === roleId)?.name ?? '—';
  }

  protected async invite(): Promise<void> {
    const organizationId = this.workspace.currentOrganizationId();
    if (!organizationId || this.form.invalid) {
      return;
    }
    this.busy.set(true);
    try {
      const value = this.form.getRawValue();
      const invitation = await this.api.createInvitation(organizationId, value.email.trim(), value.roleId);
      this.form.reset();
      this.list.reload();
      this.toast.success('Invitation sent', `An e-mail was sent to ${invitation.email}.`);
    } catch (error) {
      this.toast.error('Could not send the invitation', error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async revoke(invitation: InvitationDto): Promise<void> {
    const organizationId = this.workspace.currentOrganizationId();
    if (!organizationId) {
      return;
    }
    try {
      await this.api.revokeInvitation(organizationId, invitation.id);
      this.list.reload();
      this.toast.success('Invitation revoked');
    } catch (error) {
      this.toast.error('Could not revoke the invitation', error);
    }
  }
}
