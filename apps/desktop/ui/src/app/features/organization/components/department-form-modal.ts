import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Department, MemberRef } from '../../../core/models';
import { ToastService } from '../../../core/services/toast.service';
import { DirectoryStore } from '../../../core/state/directory.store';
import { OrganizationStore } from '../../../core/state/organization.store';
import { Icon } from '../../../shared/components/icon/icon';
import { Modal } from '../../../shared/components/modal/modal';
import { errorMessage, notBlank } from '../../../shared/utils/validators';

@Component({
  selector: 'app-department-form-modal',
  imports: [ReactiveFormsModule, Modal, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-modal heading="New department" description="Departments can be nested and led by a person or an AI agent." [open]="open()" [locked]="saving()" (closed)="close()">
      <form id="department-form" class="form-grid" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div class="field">
          <label class="field__label" for="dep-name">Name<span class="field__required" aria-hidden="true">*</span></label>
          <input id="dep-name" class="input" formControlName="name" placeholder="e.g. Customer Success" autocomplete="off" />
          @if (error('name', 'Name'); as message) {
            <span class="field__error">{{ message }}</span>
          }
        </div>
        <div class="field">
          <label class="field__label" for="dep-code">Code<span class="field__required" aria-hidden="true">*</span></label>
          <input id="dep-code" class="input mono" formControlName="code" placeholder="CS" maxlength="6" autocomplete="off" (input)="uppercaseCode()" />
          @if (error('code', 'Code'); as message) {
            <span class="field__error">{{ message === 'Code has an invalid format.' ? 'Use 2 to 6 uppercase letters.' : message }}</span>
          } @else {
            <span class="field__hint">2–6 uppercase letters, unique.</span>
          }
        </div>
        <div class="field field--full">
          <label class="field__label" for="dep-description">Description</label>
          <textarea id="dep-description" class="textarea" rows="3" formControlName="description" placeholder="Mission and scope of the department"></textarea>
        </div>
        <div class="field">
          <label class="field__label" for="dep-parent">Parent department</label>
          <select id="dep-parent" class="select" formControlName="parentId">
            <option value="">None (top level)</option>
            @for (node of organization.departmentsFlat(); track node.department.id) {
              <option [value]="node.department.id">{{ node.depth > 0 ? '— ' : '' }}{{ node.department.name }}</option>
            }
          </select>
        </div>
        <div class="field">
          <label class="field__label" for="dep-manager">Manager</label>
          <select id="dep-manager" class="select" formControlName="managerKey">
            <option value="">No manager yet</option>
            <optgroup label="People">
              @for (member of humans(); track member.key) {
                <option [value]="member.key">{{ member.name }} — {{ member.roleTitle }}</option>
              }
            </optgroup>
            <optgroup label="AI agents">
              @for (member of agents(); track member.key) {
                <option [value]="member.key">{{ member.name }} (AI) — {{ member.roleTitle }}</option>
              }
            </optgroup>
          </select>
        </div>
      </form>
      @if (duplicateCode()) {
        <p class="field__error duplicate" role="alert"><ui-icon name="circle-alert" [size]="13" /> A department with code {{ form.controls.code.value }} already exists.</p>
      }
      <ng-container modalFooter>
        <button type="button" class="btn btn--secondary" [disabled]="saving()" (click)="close()">Cancel</button>
        <button type="submit" form="department-form" class="btn btn--primary" [disabled]="saving()">Create department</button>
      </ng-container>
    </ui-modal>
  `,
  styles: `
    .duplicate {
      display: flex;
      align-items: center;
      gap: 6px;
      margin-top: var(--space-3);
    }
  `,
})
export class DepartmentFormModal {
  readonly open = input(false);
  readonly closed = output<void>();
  readonly created = output<Department>();

  private readonly fb = inject(NonNullableFormBuilder);
  protected readonly organization = inject(OrganizationStore);
  private readonly directory = inject(DirectoryStore);
  private readonly toast = inject(ToastService);

  protected readonly saving = signal(false);
  protected readonly duplicateCode = signal(false);
  protected readonly humans = computed(() => this.directory.members().filter((m) => m.kind === 'human'));
  protected readonly agents = computed(() => this.directory.members().filter((m) => m.kind === 'agent'));

  protected readonly form = this.fb.group({
    name: ['', [Validators.required, notBlank, Validators.maxLength(60)]],
    code: ['', [Validators.required, Validators.pattern(/^[A-Z]{2,6}$/)]],
    description: ['', Validators.maxLength(400)],
    parentId: [''],
    managerKey: [''],
  });

  constructor() {
    effect(() => {
      if (this.open()) {
        untracked(() => {
          this.form.reset();
          this.duplicateCode.set(false);
        });
      }
    });
  }

  protected error(name: keyof typeof this.form.controls, label: string): string | null {
    return errorMessage(this.form.controls[name], label);
  }

  protected async submit(): Promise<void> {
    this.form.markAllAsTouched();
    const value = this.form.getRawValue();
    const code = value.code.trim().toUpperCase();
    this.duplicateCode.set(this.organization.departments().some((d) => d.code === code));
    if (this.form.invalid || this.duplicateCode() || this.saving()) {
      return;
    }
    this.saving.set(true);
    try {
      const department = await this.organization.createDepartment({
        name: value.name.trim(),
        code,
        description: value.description.trim(),
        parentId: value.parentId || null,
        managerRef: this.parseMember(value.managerKey),
      });
      this.toast.success('Department created', `${department.name} was added to the local mock workspace.`);
      this.created.emit(department);
    } catch (error) {
      this.toast.error('Could not create the department', error);
    } finally {
      this.saving.set(false);
    }
  }

  protected close(): void {
    if (!this.saving()) {
      this.closed.emit();
    }
  }

  protected uppercaseCode(): void {
    const control = this.form.controls.code;
    const next = control.value.toUpperCase().replace(/[^A-Z]/g, '');
    if (next !== control.value) {
      control.setValue(next);
    }
  }

  private parseMember(key: string): MemberRef | null {
    const member = this.directory.members().find((m) => m.key === key);
    return member ? { kind: member.ref.kind, id: member.ref.id } : null;
  }
}
