import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { IdentityApi } from '../../core/identity/identity.api';
import { ToastService } from '../../core/services/toast.service';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { Panel } from '../../shared/components/panel/panel';

/** Creates an organization; the caller becomes its OWNER (done atomically by the API). */
@Component({
  selector: 'app-create-organization-page',
  imports: [ReactiveFormsModule, PageHeader, Panel],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <ui-page-header heading="Create an organization" description="You will be its owner. You can invite members afterwards." />
      <ui-panel heading="Organization">
        <form class="form-grid" [formGroup]="form" (ngSubmit)="create()" novalidate>
          <div class="field">
            <label class="field__label" for="new-org-name">Name<span class="field__required" aria-hidden="true">*</span></label>
            <input id="new-org-name" class="input" formControlName="name" maxlength="100" autocomplete="organization" />
            @if (form.controls.name.touched && form.controls.name.invalid) {
              <span class="field__error">Between 2 and 100 characters.</span>
            }
          </div>
          <div class="field">
            <label class="field__label" for="new-org-timezone">Timezone</label>
            <input id="new-org-timezone" class="input" formControlName="timezone" maxlength="64" placeholder="UTC" />
          </div>
          <div class="field field--full">
            <label class="field__label" for="new-org-description">Description</label>
            <textarea id="new-org-description" class="textarea" rows="3" maxlength="500" formControlName="description"></textarea>
          </div>
          <div class="field field--full form-actions">
            <button type="submit" class="btn btn--primary" [disabled]="saving()">Create organization</button>
          </div>
        </form>
      </ui-panel>
    </div>
  `,
  styles: `
    .form-actions {
      flex-direction: row;
      justify-content: flex-end;
    }
  `,
})
export class CreateOrganizationPage {
  private readonly api = inject(IdentityApi);
  private readonly workspace = inject(WorkspaceStore);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);

  protected readonly saving = signal(false);
  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(100), Validators.pattern(/\S/)]],
    timezone: [Intl.DateTimeFormat().resolvedOptions().timeZone ?? 'UTC'],
    description: [''],
  });

  protected async create(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    try {
      const value = this.form.getRawValue();
      const created = await this.api.createOrganization({
        name: value.name.trim(),
        description: value.description.trim(),
        timezone: value.timezone.trim() || 'UTC',
      });
      this.workspace.switchOrganization(created.id);
      this.workspace.reload();
      this.toast.success('Organization created', `You are the owner of ${created.name}.`);
      void this.router.navigateByUrl('/settings/members');
    } catch (error) {
      this.toast.error('Could not create the organization', error);
    } finally {
      this.saving.set(false);
    }
  }
}
