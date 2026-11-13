import { ChangeDetectionStrategy, Component, computed, inject, resource, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AccessStore } from '../../../core/identity/access.store';
import { IdentityApi } from '../../../core/identity/identity.api';
import { PermissionCode, RoleDto } from '../../../core/identity/identity.models';
import { ConfirmService } from '../../../core/services/confirm.service';
import { ToastService } from '../../../core/services/toast.service';
import { WorkspaceStore } from '../../../core/state/workspace.store';
import { Badge } from '../../../shared/components/badge/badge';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Panel } from '../../../shared/components/panel/panel';

/**
 * Roles of the current organization. Built-in roles are read-only. A custom role can only
 * contain permissions the caller holds itself, never `ownership.manage` (the API enforces it).
 */
@Component({
  selector: 'app-roles-settings',
  imports: [ReactiveFormsModule, Panel, Badge, EmptyState],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!access.can('role.read')) {
      <ui-empty-state icon="lock" heading="No access" description="Your role does not allow viewing roles." />
    } @else {
      <div class="stack">
        <ui-panel heading="Roles" subheading="Built-in roles cannot be changed. Custom roles combine permissions.">
          <ul class="roles">
            @for (role of roles(); track role.id) {
              <li class="role">
                <div class="role__head">
                  <strong>{{ role.name }}</strong>
                  <ui-badge [label]="role.builtIn ? 'Built-in' : 'Custom'" [tone]="role.builtIn ? 'neutral' : 'accent'" [dot]="false" />
                  @if (!role.builtIn && access.can('role.manage')) {
                    <button type="button" class="btn btn--ghost btn--sm danger" (click)="remove(role)">Delete</button>
                  }
                </div>
                @if (role.description) { <p class="text-tertiary">{{ role.description }}</p> }
                <p class="role__perms">{{ role.permissions.join(', ') || 'No permission' }}</p>
              </li>
            }
          </ul>
        </ui-panel>

        @if (access.can('role.manage')) {
          <ui-panel heading="New custom role" subheading="Only permissions you hold yourself can be granted.">
            <form class="stack" [formGroup]="form" (ngSubmit)="create()" novalidate>
              <div class="form-grid">
                <div class="field">
                  <label class="field__label" for="role-name">Name</label>
                  <input id="role-name" class="input" formControlName="name" maxlength="60" />
                </div>
                <div class="field">
                  <label class="field__label" for="role-description">Description</label>
                  <input id="role-description" class="input" formControlName="description" maxlength="300" />
                </div>
              </div>
              <fieldset class="perms">
                <legend class="field__label">Permissions</legend>
                @for (permission of grantable(); track permission.code) {
                  <label class="perm">
                    <input type="checkbox" [checked]="selected().has(permission.code)" (change)="toggle(permission.code)" />
                    <span><code>{{ permission.code }}</code> <span class="text-tertiary">{{ permission.description }}</span></span>
                  </label>
                }
              </fieldset>
              <div class="actions">
                <button type="submit" class="btn btn--primary" [disabled]="busy() || form.invalid">Create role</button>
              </div>
            </form>
          </ui-panel>
        }
      </div>
    }
  `,
  styles: `
    .roles { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--space-3); }
    .role__head { display: flex; align-items: center; gap: var(--space-2); }
    .role__head .btn { margin-left: auto; }
    .role p { margin: var(--space-1) 0 0; }
    .role__perms { font-family: var(--font-mono); font-size: var(--text-xs); color: var(--color-text-secondary); }
    .perms { border: 0; padding: 0; margin: 0; display: grid; gap: var(--space-1-5); }
    .perm { display: flex; gap: var(--space-2); align-items: baseline; }
    .actions { display: flex; justify-content: flex-end; }
    .danger { color: var(--color-danger); }
  `,
})
export class RolesSettings {
  protected readonly access = inject(AccessStore);
  private readonly api = inject(IdentityApi);
  private readonly workspace = inject(WorkspaceStore);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  private readonly roleList = resource({
    params: () => (this.access.can('role.read') ? (this.workspace.currentOrganizationId() ?? undefined) : undefined),
    loader: ({ params }) => this.api.listRoles(params),
  });
  private readonly catalog = resource({ loader: () => this.api.listPermissions() });

  protected readonly roles = computed<readonly RoleDto[]>(() => (this.roleList.hasValue() ? this.roleList.value() : []));
  protected readonly grantable = computed(() =>
    (this.catalog.hasValue() ? this.catalog.value() : []).filter(
      (p) => p.code !== 'ownership.manage' && this.access.can(p.code),
    ),
  );
  protected readonly selected = signal<ReadonlySet<PermissionCode>>(new Set());
  protected readonly busy = signal(false);
  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(60), Validators.pattern(/\S/)]],
    description: [''],
  });

  protected toggle(code: PermissionCode): void {
    const next = new Set(this.selected());
    if (!next.delete(code)) {
      next.add(code);
    }
    this.selected.set(next);
  }

  protected async create(): Promise<void> {
    const organizationId = this.workspace.currentOrganizationId();
    if (!organizationId || this.form.invalid) {
      return;
    }
    this.busy.set(true);
    try {
      const value = this.form.getRawValue();
      await this.api.createRole(organizationId, {
        name: value.name.trim(),
        description: value.description.trim(),
        permissions: [...this.selected()],
      });
      this.form.reset();
      this.selected.set(new Set());
      this.roleList.reload();
      this.toast.success('Role created');
    } catch (error) {
      this.toast.error('Could not create the role', error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async remove(role: RoleDto): Promise<void> {
    const organizationId = this.workspace.currentOrganizationId();
    if (!organizationId) {
      return;
    }
    const answer = await this.confirm.ask({
      title: `Delete the role “${role.name}”?`,
      message: 'Roles still assigned to members cannot be deleted.',
      confirmLabel: 'Delete',
      tone: 'danger',
    });
    if (!answer.confirmed) {
      return;
    }
    try {
      await this.api.deleteRole(organizationId, role.id);
      this.roleList.reload();
      this.toast.success('Role deleted');
    } catch (error) {
      this.toast.error('Could not delete the role', error);
    }
  }
}
