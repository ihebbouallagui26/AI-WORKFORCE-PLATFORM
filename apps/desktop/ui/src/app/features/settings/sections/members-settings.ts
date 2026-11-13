import { ChangeDetectionStrategy, Component, computed, inject, resource, signal } from '@angular/core';
import { AccessStore } from '../../../core/identity/access.store';
import { IdentityApi } from '../../../core/identity/identity.api';
import { MemberDto, RoleDto } from '../../../core/identity/identity.models';
import { ConfirmService } from '../../../core/services/confirm.service';
import { ToastService } from '../../../core/services/toast.service';
import { WorkspaceStore } from '../../../core/state/workspace.store';
import { Badge } from '../../../shared/components/badge/badge';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Panel } from '../../../shared/components/panel/panel';

/** Members of the current organization: roles, suspension, removal. Actions follow permissions. */
@Component({
  selector: 'app-members-settings',
  imports: [Panel, Badge, EmptyState],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!access.can('member.read')) {
      <ui-empty-state icon="lock" heading="No access" description="Your role does not allow viewing members." />
    } @else {
      <ui-panel heading="Members" [subheading]="members().length + ' member(s)'" [flush]="true">
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr><th>Member</th><th>Roles</th><th>Status</th><th class="actions-col"><span class="sr-only">Actions</span></th></tr>
            </thead>
            <tbody>
              @for (member of members(); track member.membershipId) {
                <tr>
                  <td>
                    <div class="member-name">{{ member.displayName ?? member.email }}</div>
                    <div class="text-tertiary">{{ member.email }}@if (isMe(member)) { · you }</div>
                  </td>
                  <td>
                    @if (editing() === member.membershipId) {
                      <fieldset class="role-picker">
                        <legend class="sr-only">Roles of {{ member.email }}</legend>
                        @for (role of roles(); track role.id) {
                          <label><input type="checkbox" [checked]="draft().has(role.id)" (change)="toggle(role.id)" /> {{ role.name }}</label>
                        }
                      </fieldset>
                    } @else {
                      @for (role of member.roles; track role.id) {
                        <ui-badge [label]="role.name" [tone]="role.code === 'OWNER' ? 'accent' : 'neutral'" [dot]="false" />
                      }
                    }
                  </td>
                  <td>
                    <ui-badge [label]="member.status === 'ACTIVE' ? 'Active' : 'Suspended'" [tone]="member.status === 'ACTIVE' ? 'success' : 'warning'" />
                  </td>
                  <td class="actions-col">
                    @if (editing() === member.membershipId) {
                      <button type="button" class="btn btn--ghost btn--sm" (click)="editing.set(null)">Cancel</button>
                      <button type="button" class="btn btn--primary btn--sm" [disabled]="busy() || draft().size === 0" (click)="saveRoles(member)">Save</button>
                    } @else {
                      @if (access.can('member.roles.assign')) {
                        <button type="button" class="btn btn--ghost btn--sm" (click)="edit(member)">Roles</button>
                      }
                      @if (access.can('member.manage') && !isMe(member)) {
                        @if (member.status === 'ACTIVE') {
                          <button type="button" class="btn btn--ghost btn--sm" [disabled]="busy()" (click)="suspend(member)">Suspend</button>
                        } @else {
                          <button type="button" class="btn btn--ghost btn--sm" [disabled]="busy()" (click)="reactivate(member)">Reactivate</button>
                        }
                        <button type="button" class="btn btn--ghost btn--sm danger" [disabled]="busy()" (click)="remove(member)">Remove</button>
                      }
                      @if (isMe(member)) {
                        <button type="button" class="btn btn--ghost btn--sm danger" [disabled]="busy()" (click)="remove(member)">Leave</button>
                      }
                    }
                  </td>
                </tr>
              } @empty {
                <tr><td colspan="4" class="text-tertiary">{{ list.isLoading() ? 'Loading…' : 'No members.' }}</td></tr>
              }
            </tbody>
          </table>
        </div>
      </ui-panel>
    }
  `,
  styles: `
    .member-name { font-weight: var(--weight-medium); }
    .actions-col { text-align: right; white-space: nowrap; }
    .role-picker { border: 0; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: var(--space-2); }
    .danger { color: var(--color-danger); }
    td ui-badge { margin-right: var(--space-1); }
  `,
})
export class MembersSettings {
  protected readonly access = inject(AccessStore);
  private readonly api = inject(IdentityApi);
  private readonly workspace = inject(WorkspaceStore);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly list = resource({
    params: () => this.workspace.currentOrganizationId() ?? undefined,
    loader: ({ params }) => this.api.listMembers(params),
  });
  private readonly roleList = resource({
    params: () => (this.access.can('role.read') ? (this.workspace.currentOrganizationId() ?? undefined) : undefined),
    loader: ({ params }) => this.api.listRoles(params),
  });

  protected readonly members = computed(() => (this.list.hasValue() ? this.list.value().content : []));
  protected readonly roles = computed<readonly RoleDto[]>(() => (this.roleList.hasValue() ? this.roleList.value() : []));
  protected readonly editing = signal<string | null>(null);
  protected readonly draft = signal<ReadonlySet<string>>(new Set());
  protected readonly busy = signal(false);

  protected isMe(member: MemberDto): boolean {
    return member.membershipId === this.access.organization()?.myMembershipId;
  }

  protected edit(member: MemberDto): void {
    this.draft.set(new Set(member.roles.map((r) => r.id)));
    this.editing.set(member.membershipId);
  }

  protected toggle(roleId: string): void {
    const next = new Set(this.draft());
    if (!next.delete(roleId)) {
      next.add(roleId);
    }
    this.draft.set(next);
  }

  protected saveRoles(member: MemberDto): Promise<void> {
    return this.run('Roles updated', () =>
      this.api.setMemberRoles(this.organizationId(), member.membershipId, [...this.draft()]), () => this.editing.set(null));
  }

  protected suspend(member: MemberDto): Promise<void> {
    return this.run('Member suspended', () => this.api.suspendMember(this.organizationId(), member.membershipId));
  }

  protected reactivate(member: MemberDto): Promise<void> {
    return this.run('Member reactivated', () => this.api.reactivateMember(this.organizationId(), member.membershipId));
  }

  protected async remove(member: MemberDto): Promise<void> {
    const leaving = this.isMe(member);
    const answer = await this.confirm.ask({
      title: leaving ? 'Leave this organization?' : 'Remove this member?',
      message: leaving
        ? 'You will lose access immediately. An invitation is needed to come back.'
        : `${member.email ?? 'This member'} will lose access immediately.`,
      confirmLabel: leaving ? 'Leave' : 'Remove',
      tone: 'danger',
    });
    if (!answer.confirmed) {
      return;
    }
    await this.run(leaving ? 'You left the organization' : 'Member removed',
      () => this.api.removeMember(this.organizationId(), member.membershipId),
      () => {
        if (leaving) {
          this.workspace.reload();
        }
      });
  }

  private organizationId(): string {
    const id = this.workspace.currentOrganizationId();
    if (!id) {
      throw new Error('No organization selected.');
    }
    return id;
  }

  private async run(success: string, action: () => Promise<unknown>, after?: () => void): Promise<void> {
    this.busy.set(true);
    try {
      await action();
      this.toast.success(success);
      after?.();
      this.list.reload();
      this.access.reload();
    } catch (error) {
      this.toast.error('Action failed', error);
    } finally {
      this.busy.set(false);
    }
  }
}
