import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ToastService } from '../../core/services/toast.service';
import { TeamDto } from '../../core/workforce/workforce.models';
import { WorkforceApi } from '../../core/workforce/workforce.api';
import { WorkforceStore } from '../../core/workforce/workforce.store';
import { Modal } from '../../shared/components/modal/modal';
import { errorMessage, notBlank } from '../../shared/utils/validators';
import { idOrNull } from './workforce-format';

/** Creates a team (name, description, department, leader) or edits its name and description. */
@Component({
  selector: 'app-wf-team-form',
  imports: [ReactiveFormsModule, Modal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-modal [heading]="team() ? 'Edit team' : 'New team'"
      description="Teams can mix departments. A team leader is always a member of the team."
      [open]="open()" [locked]="saving()" (closed)="closed.emit()">
      <form id="wf-team-form" class="form-grid" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div class="field field--full">
          <label class="field__label" for="wf-team-name">Name<span class="field__required" aria-hidden="true">*</span></label>
          <input id="wf-team-name" class="input" formControlName="name" maxlength="100" autocomplete="off" />
          @if (error('name', 'Name'); as message) { <span class="field__error">{{ message }}</span> }
        </div>
        <div class="field field--full">
          <label class="field__label" for="wf-team-description">Description</label>
          <textarea id="wf-team-description" class="textarea" rows="3" maxlength="500" formControlName="description"></textarea>
        </div>
        @if (!team()) {
          <div class="field">
            <label class="field__label" for="wf-team-department">Department</label>
            <select id="wf-team-department" class="select" formControlName="departmentId">
              <option value="">None</option>
              @for (row of store.activeDepartments(); track row.department.id) {
                <option [value]="row.department.id">{{ '— '.repeat(row.depth) }}{{ row.department.name }}</option>
              }
            </select>
          </div>
          <div class="field">
            <label class="field__label" for="wf-team-leader">Leader</label>
            <select id="wf-team-leader" class="select" formControlName="leaderEmployeeId">
              <option value="">No leader yet</option>
              @for (employee of store.currentEmployees(); track employee.id) {
                <option [value]="employee.id">{{ employee.displayName }}</option>
              }
            </select>
            <span class="field__hint">The leader joins the team as its first member.</span>
          </div>
        }
      </form>
      <ng-container modalFooter>
        <button type="button" class="btn btn--secondary" [disabled]="saving()" (click)="closed.emit()">Cancel</button>
        <button type="submit" form="wf-team-form" class="btn btn--primary" [disabled]="saving()">
          {{ team() ? 'Save changes' : 'Create team' }}
        </button>
      </ng-container>
    </ui-modal>
  `,
})
export class WfTeamForm {
  readonly open = input(false);
  readonly team = input<TeamDto | null>(null);
  readonly closed = output<void>();
  readonly saved = output<TeamDto>();

  protected readonly store = inject(WorkforceStore);
  private readonly api = inject(WorkforceApi);
  private readonly toast = inject(ToastService);
  protected readonly saving = signal(false);

  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, notBlank, Validators.minLength(2), Validators.maxLength(100)]],
    description: ['', Validators.maxLength(500)],
    departmentId: [''],
    leaderEmployeeId: [''],
  });

  constructor() {
    effect(() => {
      if (this.open()) {
        const team = this.team();
        untracked(() =>
          this.form.reset({ name: team?.name ?? '', description: team?.description ?? '', departmentId: '', leaderEmployeeId: '' }),
        );
      }
    });
  }

  protected error(name: 'name', label: string): string | null {
    return errorMessage(this.form.controls[name], label);
  }

  protected async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.saving()) {
      return;
    }
    const value = this.form.getRawValue();
    const existing = this.team();
    this.saving.set(true);
    try {
      const saved = await this.store.mutate((organizationId) =>
        existing
          ? this.api.updateTeam(organizationId, existing.id, {
              name: value.name.trim(),
              description: value.description.trim(),
              expectedVersion: existing.version,
            })
          : this.api.createTeam(organizationId, {
              name: value.name.trim(),
              description: value.description.trim(),
              departmentId: idOrNull(value.departmentId),
              leaderEmployeeId: idOrNull(value.leaderEmployeeId),
            }),
      );
      this.toast.success(existing ? 'Team updated' : 'Team created', saved.name);
      this.saved.emit(saved);
    } catch (error) {
      this.toast.error(existing ? 'Could not update the team' : 'Could not create the team', error);
    } finally {
      this.saving.set(false);
    }
  }
}
