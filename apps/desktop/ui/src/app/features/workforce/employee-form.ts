import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { IdentityApi } from '../../core/identity/identity.api';
import { MemberDto } from '../../core/identity/identity.models';
import { ToastService } from '../../core/services/toast.service';
import { EmployeeDto } from '../../core/workforce/workforce.models';
import { WorkforceApi } from '../../core/workforce/workforce.api';
import { WorkforceStore } from '../../core/workforce/workforce.store';
import { Modal } from '../../shared/components/modal/modal';
import { errorMessage, notBlank } from '../../shared/utils/validators';
import { idOrNull } from './workforce-format';

/**
 * Creates an employee profile (optionally linked to a platform member, with department,
 * position and manager), or edits the scalar fields of an existing one. Assignments of an
 * existing employee are changed from the employee panel.
 */
@Component({
  selector: 'app-wf-employee-form',
  imports: [ReactiveFormsModule, Modal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-modal [heading]="employee() ? 'Edit employee' : 'New employee'" size="lg"
      description="The professional record of a person. It does not need a platform login and never contains credentials."
      [open]="open()" [locked]="saving()" (closed)="closed.emit()">
      <form id="wf-employee-form" class="form-grid" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div class="field">
          <label class="field__label" for="wf-emp-name">Display name<span class="field__required" aria-hidden="true">*</span></label>
          <input id="wf-emp-name" class="input" formControlName="displayName" maxlength="200" autocomplete="off" />
          @if (error('displayName', 'Display name'); as message) { <span class="field__error">{{ message }}</span> }
        </div>
        <div class="field">
          <label class="field__label" for="wf-emp-number">Employee number</label>
          <input id="wf-emp-number" class="input" formControlName="employeeNumber" maxlength="50" autocomplete="off" />
        </div>
        <div class="field">
          <label class="field__label" for="wf-emp-email">Professional e-mail</label>
          <input id="wf-emp-email" type="email" class="input" formControlName="professionalEmail" maxlength="254" autocomplete="off" />
          @if (error('email', 'E-mail'); as message) { <span class="field__error">{{ message }}</span> }
        </div>
        <div class="field">
          <label class="field__label" for="wf-emp-start">Start date</label>
          <input id="wf-emp-start" type="date" class="input" formControlName="startDate" />
        </div>
        @if (!employee()) {
          <div class="field">
            <label class="field__label" for="wf-emp-department">Department</label>
            <select id="wf-emp-department" class="select" formControlName="departmentId">
              <option value="">None</option>
              @for (row of store.activeDepartments(); track row.department.id) {
                <option [value]="row.department.id">{{ '— '.repeat(row.depth) }}{{ row.department.name }}</option>
              }
            </select>
          </div>
          <div class="field">
            <label class="field__label" for="wf-emp-position">Position</label>
            <select id="wf-emp-position" class="select" formControlName="positionId">
              <option value="">None</option>
              @for (position of store.activePositions(); track position.id) {
                <option [value]="position.id">{{ position.title }}</option>
              }
            </select>
          </div>
          <div class="field">
            <label class="field__label" for="wf-emp-manager">Manager</label>
            <select id="wf-emp-manager" class="select" formControlName="managerId">
              <option value="">No manager</option>
              @for (candidate of store.currentEmployees(); track candidate.id) {
                <option [value]="candidate.id">{{ candidate.displayName }}</option>
              }
            </select>
          </div>
          @if (store.can('member.read')) {
            <div class="field">
              <label class="field__label" for="wf-emp-member">Platform member</label>
              <select id="wf-emp-member" class="select" formControlName="membershipId">
                <option value="">No platform login</option>
                @for (member of members(); track member.membershipId) {
                  <option [value]="member.membershipId">{{ member.displayName ?? member.email }}{{ member.email && member.displayName ? ' — ' + member.email : '' }}</option>
                }
              </select>
              <span class="field__hint">Link the profile to a member of this organization (invite people from Settings).</span>
            </div>
          }
        }
        <div class="field field--full">
          <label class="field__label" for="wf-emp-description">Description</label>
          <textarea id="wf-emp-description" class="textarea" rows="3" maxlength="1000" formControlName="description"></textarea>
        </div>
      </form>
      <ng-container modalFooter>
        <button type="button" class="btn btn--secondary" [disabled]="saving()" (click)="closed.emit()">Cancel</button>
        <button type="submit" form="wf-employee-form" class="btn btn--primary" [disabled]="saving()">
          {{ employee() ? 'Save changes' : 'Create employee' }}
        </button>
      </ng-container>
    </ui-modal>
  `,
})
export class WfEmployeeForm {
  readonly open = input(false);
  readonly employee = input<EmployeeDto | null>(null);
  readonly closed = output<void>();
  readonly saved = output<EmployeeDto>();

  protected readonly store = inject(WorkforceStore);
  private readonly api = inject(WorkforceApi);
  private readonly identity = inject(IdentityApi);
  private readonly toast = inject(ToastService);
  protected readonly saving = signal(false);
  protected readonly members = signal<readonly MemberDto[]>([]);

  protected readonly form = inject(NonNullableFormBuilder).group({
    displayName: ['', [Validators.required, notBlank, Validators.maxLength(200)]],
    employeeNumber: ['', Validators.maxLength(50)],
    professionalEmail: ['', [Validators.email, Validators.maxLength(254)]],
    startDate: [''],
    departmentId: [''],
    positionId: [''],
    managerId: [''],
    membershipId: [''],
    description: ['', Validators.maxLength(1000)],
  });

  constructor() {
    effect(() => {
      if (!this.open()) {
        return;
      }
      const employee = this.employee();
      untracked(() => {
        this.form.reset({
          displayName: employee?.displayName ?? '',
          employeeNumber: employee?.employeeNumber ?? '',
          professionalEmail: employee?.professionalEmail ?? '',
          startDate: employee?.startDate ?? '',
          description: employee?.description ?? '',
        });
        if (!employee) {
          void this.loadMembers();
        }
      });
    });
  }

  protected error(name: 'displayName' | 'email', label: string): string | null {
    return errorMessage(name === 'email' ? this.form.controls.professionalEmail : this.form.controls.displayName, label);
  }

  private async loadMembers(): Promise<void> {
    const organizationId = this.store.organizationId();
    if (!organizationId || !this.store.can('member.read')) {
      this.members.set([]);
      return;
    }
    try {
      this.members.set((await this.identity.listMembers(organizationId, 0, 100, true)).content);
    } catch {
      this.members.set([]);
    }
  }

  protected async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.saving()) {
      return;
    }
    const value = this.form.getRawValue();
    const existing = this.employee();
    this.saving.set(true);
    try {
      const saved = await this.store.mutate((organizationId) =>
        existing
          ? this.api.updateEmployee(organizationId, existing.id, {
              displayName: value.displayName.trim(),
              employeeNumber: value.employeeNumber.trim(),
              professionalEmail: value.professionalEmail.trim(),
              ...(value.startDate ? { startDate: value.startDate } : {}),
              description: value.description.trim(),
              expectedVersion: existing.version,
            })
          : this.api.createEmployee(organizationId, {
              displayName: value.displayName.trim(),
              employeeNumber: value.employeeNumber.trim(),
              professionalEmail: value.professionalEmail.trim(),
              startDate: value.startDate || null,
              departmentId: idOrNull(value.departmentId),
              positionId: idOrNull(value.positionId),
              managerId: idOrNull(value.managerId),
              membershipId: idOrNull(value.membershipId),
              description: value.description.trim(),
            }),
      );
      this.toast.success(existing ? 'Employee updated' : 'Employee created', saved.displayName);
      this.saved.emit(saved);
    } catch (error) {
      this.toast.error(existing ? 'Could not update the employee' : 'Could not create the employee', error);
    } finally {
      this.saving.set(false);
    }
  }
}
