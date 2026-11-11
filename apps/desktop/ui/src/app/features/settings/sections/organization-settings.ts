import { ChangeDetectionStrategy, Component, effect, inject, signal, untracked } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ToastService } from '../../../core/services/toast.service';
import { WorkspaceStore } from '../../../core/state/workspace.store';
import { Panel } from '../../../shared/components/panel/panel';
import { errorMessage, notBlank } from '../../../shared/utils/validators';

const TIMEZONES = [
  'Europe/Paris',
  'Europe/London',
  'Europe/Lisbon',
  'Europe/Berlin',
  'Africa/Tunis',
  'America/New_York',
  'America/Los_Angeles',
  'Asia/Tokyo',
  'UTC',
];

@Component({
  selector: 'app-organization-settings',
  imports: [ReactiveFormsModule, Panel],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (workspace.currentOrganization(); as organization) {
      <div class="stack">
        <ui-panel heading="Organization profile" [subheading]="organization.slug + ' · created ' + (organization.createdAt.slice(0, 10))">
          <form class="form-grid" [formGroup]="form" (ngSubmit)="save()" novalidate>
            <div class="field">
              <label class="field__label" for="org-name">Name<span class="field__required" aria-hidden="true">*</span></label>
              <input id="org-name" class="input" formControlName="name" autocomplete="organization" />
              @if (error('name', 'Name'); as message) {
                <span class="field__error">{{ message }}</span>
              }
            </div>
            <div class="field">
              <label class="field__label" for="org-industry">Industry</label>
              <input id="org-industry" class="input" formControlName="industry" />
            </div>
            <div class="field field--full">
              <label class="field__label" for="org-description">Description</label>
              <textarea id="org-description" class="textarea" rows="3" formControlName="description"></textarea>
            </div>
            <div class="field">
              <label class="field__label" for="org-timezone">Timezone</label>
              <select id="org-timezone" class="select" formControlName="timezone">
                @for (zone of timezones; track zone) {
                  <option [value]="zone">{{ zone }}</option>
                }
              </select>
            </div>
            <div class="field form-actions">
              <button type="button" class="btn btn--secondary" [disabled]="form.pristine || saving()" (click)="reset()">Discard</button>
              <button type="submit" class="btn btn--primary" [disabled]="form.pristine || saving()">Save changes</button>
            </div>
          </form>
        </ui-panel>
        <ui-panel heading="Plan & deployment">
          <dl class="meta-list">
            <dt>Plan</dt>
            <dd class="capitalize">{{ organization.plan }}</dd>
            <dt>Deployment</dt>
            <dd class="capitalize">{{ organization.deployment }} <span class="text-tertiary">— local runner for sensitive workloads, cloud for the rest</span></dd>
            <dt>Agent autonomy</dt>
            <dd>Supervised: sensitive operations always require a human decision.</dd>
          </dl>
        </ui-panel>
      </div>
    }
  `,
  styles: `
    .form-actions {
      flex-direction: row;
      align-items: flex-end;
      justify-content: flex-end;
      gap: var(--space-2);
    }
    .capitalize {
      text-transform: capitalize;
    }
  `,
})
export class OrganizationSettings {
  protected readonly workspace = inject(WorkspaceStore);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly toast = inject(ToastService);

  protected readonly timezones = TIMEZONES;
  protected readonly saving = signal(false);
  protected readonly form = this.fb.group({
    name: ['', [Validators.required, notBlank, Validators.maxLength(80)]],
    industry: ['', Validators.maxLength(80)],
    description: ['', Validators.maxLength(400)],
    timezone: ['UTC'],
  });

  constructor() {
    effect(() => {
      this.workspace.currentOrganization();
      untracked(() => this.reset());
    });
  }

  protected error(name: keyof typeof this.form.controls, label: string): string | null {
    return errorMessage(this.form.controls[name], label);
  }

  protected reset(): void {
    const organization = this.workspace.currentOrganization();
    if (organization) {
      this.form.reset({
        name: organization.name,
        industry: organization.industry,
        description: organization.description,
        timezone: organization.timezone,
      });
    }
  }

  protected async save(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.saving()) {
      return;
    }
    this.saving.set(true);
    try {
      const value = this.form.getRawValue();
      await this.workspace.updateOrganization({
        name: value.name.trim(),
        industry: value.industry.trim(),
        description: value.description.trim(),
        timezone: value.timezone,
      });
      this.toast.success('Organization updated', 'Saved in the local mock workspace.');
    } catch (error) {
      this.toast.error('Could not update the organization', error);
    } finally {
      this.saving.set(false);
    }
  }
}
