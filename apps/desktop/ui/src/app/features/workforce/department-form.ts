import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ToastService } from '../../core/services/toast.service';
import { WorkforceApi } from '../../core/workforce/workforce.api';
import { WorkforceStore } from '../../core/workforce/workforce.store';
import { DepartmentDto } from '../../core/workforce/workforce.models';
import { Modal } from '../../shared/components/modal/modal';
import { errorMessage, notBlank } from '../../shared/utils/validators';
import { idOrNull } from './workforce-format';

/** Creates a department, or edits the name, code and description of an existing one. */
@Component({
  selector: 'app-wf-department-form',
  imports: [ReactiveFormsModule, Modal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-modal [heading]="department() ? 'Edit department' : 'New department'"
      description="Departments can be nested. Moving one and choosing its head are separate actions."
      [open]="open()" [locked]="saving()" (closed)="closed.emit()">
      <form id="wf-department-form" class="form-grid" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div class="field">
          <label class="field__label" for="wf-dep-name">Name<span class="field__required" aria-hidden="true">*</span></label>
          <input id="wf-dep-name" class="input" formControlName="name" maxlength="100" autocomplete="off" />
          @if (error('name', 'Name'); as message) { <span class="field__error">{{ message }}</span> }
        </div>
        <div class="field">
          <label class="field__label" for="wf-dep-code">Code<span class="field__required" aria-hidden="true">*</span></label>
          <input id="wf-dep-code" class="input" formControlName="code" maxlength="24" autocomplete="off" />
          @if (error('code', 'Code'); as message) {
            <span class="field__error">{{ message === 'Code has an invalid format.' ? 'Letters, digits, - and _ (24 max).' : message }}</span>
          } @else {
            <span class="field__hint">Unique in the organization, stored in upper case.</span>
          }
        </div>
        <div class="field field--full">
          <label class="field__label" for="wf-dep-description">Description</label>
          <textarea id="wf-dep-description" class="textarea" rows="3" maxlength="500" formControlName="description"></textarea>
        </div>
        @if (!department()) {
          <div class="field">
            <label class="field__label" for="wf-dep-parent">Parent department</label>
            <select id="wf-dep-parent" class="select" formControlName="parentId">
              <option value="">None (top level)</option>
              @for (row of store.activeDepartments(); track row.department.id) {
                <option [value]="row.department.id">{{ '— '.repeat(row.depth) }}{{ row.department.name }}</option>
              }
            </select>
          </div>
          <div class="field">
            <label class="field__label" for="wf-dep-head">Head</label>
            <select id="wf-dep-head" class="select" formControlName="headEmployeeId">
              <option value="">No head yet</option>
              @for (employee of store.currentEmployees(); track employee.id) {
                <option [value]="employee.id">{{ employee.displayName }}</option>
              }
            </select>
          </div>
        }
      </form>
      <ng-container modalFooter>
        <button type="button" class="btn btn--secondary" [disabled]="saving()" (click)="closed.emit()">Cancel</button>
        <button type="submit" form="wf-department-form" class="btn btn--primary" [disabled]="saving()">
          {{ department() ? 'Save changes' : 'Create department' }}
        </button>
      </ng-container>
    </ui-modal>
  `,
})
export class WfDepartmentForm {
  readonly open = input(false);
  /** The department being edited; null to create. */
  readonly department = input<DepartmentDto | null>(null);
  /** Parent preselected when creating. */
  readonly defaultParentId = input<string | null>(null);
  readonly closed = output<void>();
  readonly saved = output<DepartmentDto>();

  protected readonly store = inject(WorkforceStore);
  private readonly api = inject(WorkforceApi);
  private readonly toast = inject(ToastService);
  protected readonly saving = signal(false);

  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, notBlank, Validators.minLength(2), Validators.maxLength(100)]],
    code: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9_-]{0,23}$/)]],
    description: ['', Validators.maxLength(500)],
    parentId: [''],
    headEmployeeId: [''],
  });

  constructor() {
    effect(() => {
      if (this.open()) {
        const department = this.department();
        const parent = this.defaultParentId();
        untracked(() =>
          this.form.reset({
            name: department?.name ?? '',
            code: department?.code ?? '',
            description: department?.description ?? '',
            parentId: parent ?? '',
            headEmployeeId: '',
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
    const existing = this.department();
    this.saving.set(true);
    try {
      const saved = await this.store.mutate((organizationId) =>
        existing
          ? this.api.updateDepartment(organizationId, existing.id, {
              name: value.name.trim(),
              code: value.code.trim(),
              description: value.description.trim(),
              expectedVersion: existing.version,
            })
          : this.api.createDepartment(organizationId, {
              name: value.name.trim(),
              code: value.code.trim(),
              description: value.description.trim(),
              parentId: idOrNull(value.parentId),
              headEmployeeId: idOrNull(value.headEmployeeId),
            }),
      );
      this.toast.success(existing ? 'Department updated' : 'Department created', saved.name);
      this.saved.emit(saved);
    } catch (error) {
      this.toast.error(existing ? 'Could not update the department' : 'Could not create the department', error);
    } finally {
      this.saving.set(false);
    }
  }
}
