import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ToastService } from '../../core/services/toast.service';
import { PositionDto } from '../../core/workforce/workforce.models';
import { WorkforceApi } from '../../core/workforce/workforce.api';
import { WorkforceStore } from '../../core/workforce/workforce.store';
import { Modal } from '../../shared/components/modal/modal';
import { errorMessage, notBlank } from '../../shared/utils/validators';
import { idOrNull } from './workforce-format';

/** Creates or edits a position (professional job title — it never grants permissions). */
@Component({
  selector: 'app-wf-position-form',
  imports: [ReactiveFormsModule, Modal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-modal [heading]="position() ? 'Edit position' : 'New position'"
      description="A position is a job title. Access rights are managed separately, through roles."
      [open]="open()" [locked]="saving()" (closed)="closed.emit()">
      <form id="wf-position-form" class="form-grid" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div class="field">
          <label class="field__label" for="wf-pos-title">Title<span class="field__required" aria-hidden="true">*</span></label>
          <input id="wf-pos-title" class="input" formControlName="title" maxlength="100" autocomplete="off" />
          @if (error('title', 'Title'); as message) { <span class="field__error">{{ message }}</span> }
        </div>
        <div class="field">
          <label class="field__label" for="wf-pos-code">Code<span class="field__required" aria-hidden="true">*</span></label>
          <input id="wf-pos-code" class="input" formControlName="code" maxlength="24" autocomplete="off" />
          @if (error('code', 'Code'); as message) { <span class="field__error">{{ message }}</span> }
        </div>
        <div class="field field--full">
          <label class="field__label" for="wf-pos-description">Description</label>
          <textarea id="wf-pos-description" class="textarea" rows="3" maxlength="500" formControlName="description"></textarea>
        </div>
        @if (!position()) {
          <div class="field">
            <label class="field__label" for="wf-pos-department">Department</label>
            <select id="wf-pos-department" class="select" formControlName="departmentId">
              <option value="">Any department</option>
              @for (row of store.activeDepartments(); track row.department.id) {
                <option [value]="row.department.id">{{ '— '.repeat(row.depth) }}{{ row.department.name }}</option>
              }
            </select>
          </div>
        }
      </form>
      <ng-container modalFooter>
        <button type="button" class="btn btn--secondary" [disabled]="saving()" (click)="closed.emit()">Cancel</button>
        <button type="submit" form="wf-position-form" class="btn btn--primary" [disabled]="saving()">
          {{ position() ? 'Save changes' : 'Create position' }}
        </button>
      </ng-container>
    </ui-modal>
  `,
})
export class WfPositionForm {
  readonly open = input(false);
  readonly position = input<PositionDto | null>(null);
  readonly closed = output<void>();
  readonly saved = output<PositionDto>();

  protected readonly store = inject(WorkforceStore);
  private readonly api = inject(WorkforceApi);
  private readonly toast = inject(ToastService);
  protected readonly saving = signal(false);

  protected readonly form = inject(NonNullableFormBuilder).group({
    title: ['', [Validators.required, notBlank, Validators.minLength(2), Validators.maxLength(100)]],
    code: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9_-]{0,23}$/)]],
    description: ['', Validators.maxLength(500)],
    departmentId: [''],
  });

  constructor() {
    effect(() => {
      if (this.open()) {
        const position = this.position();
        untracked(() =>
          this.form.reset({
            title: position?.title ?? '',
            code: position?.code ?? '',
            description: position?.description ?? '',
            departmentId: '',
          }),
        );
      }
    });
  }

  protected error(name: keyof typeof this.form.controls, label: string): string | null {
    return errorMessage(this.form.controls[name], label);
  }

  protected async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.saving()) {
      return;
    }
    const value = this.form.getRawValue();
    const existing = this.position();
    this.saving.set(true);
    try {
      const saved = await this.store.mutate((organizationId) =>
        existing
          ? this.api.updatePosition(organizationId, existing.id, {
              title: value.title.trim(),
              code: value.code.trim(),
              description: value.description.trim(),
              expectedVersion: existing.version,
            })
          : this.api.createPosition(organizationId, {
              title: value.title.trim(),
              code: value.code.trim(),
              description: value.description.trim(),
              departmentId: idOrNull(value.departmentId),
            }),
      );
      this.toast.success(existing ? 'Position updated' : 'Position created', saved.title);
      this.saved.emit(saved);
    } catch (error) {
      this.toast.error(existing ? 'Could not update the position' : 'Could not create the position', error);
    } finally {
      this.saving.set(false);
    }
  }
}
