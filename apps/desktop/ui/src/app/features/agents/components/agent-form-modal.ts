import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, NonNullableFormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import {
  Agent,
  AgentDraft,
  AgentProviderBinding,
  AutonomyLevel,
  PermissionLevel,
  SensitiveOperation,
} from '../../../core/models';
import { ToastService } from '../../../core/services/toast.service';
import { AgentStore } from '../../../core/state/agent.store';
import { OrganizationStore } from '../../../core/state/organization.store';
import { ProjectStore } from '../../../core/state/project.store';
import { Icon } from '../../../shared/components/icon/icon';
import { Modal } from '../../../shared/components/modal/modal';
import { TagInput } from '../../../shared/components/tag-input/tag-input';
import {
  AUTONOMY_LEVEL,
  PERMISSION_LEVEL,
  PROVIDER_CONNECTION,
  SENSITIVE_OPERATION,
} from '../../../shared/utils/presentation';
import { errorMessage, minItems, notBlank } from '../../../shared/utils/validators';

const DEFAULT_APPROVALS: readonly SensitiveOperation[] = [
  'code-deployment',
  'external-communication',
  'data-deletion',
  'financial-transaction',
  'access-change',
];

const fallbackRequiresModel = (group: AbstractControl): ValidationErrors | null => {
  const provider = group.get('fallbackProviderId')?.value as string;
  const model = group.get('fallbackModelId')?.value as string;
  return provider && !model ? { fallbackModel: true } : null;
};

@Component({
  selector: 'app-agent-form-modal',
  imports: [ReactiveFormsModule, Modal, TagInput, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './agent-form-modal.html',
  styleUrl: './agent-form-modal.scss',
})
export class AgentFormModal {
  readonly open = input(false);
  /** Agent to edit; `null` creates a new agent. */
  readonly agent = input<Agent | null>(null);
  readonly closed = output<void>();
  readonly saved = output<Agent>();

  private readonly fb = inject(NonNullableFormBuilder);
  protected readonly agentStore = inject(AgentStore);
  protected readonly organization = inject(OrganizationStore);
  protected readonly projectStore = inject(ProjectStore);
  private readonly toast = inject(ToastService);

  protected readonly permissionLevels = Object.entries(PERMISSION_LEVEL) as [PermissionLevel, { label: string; description: string }][];
  protected readonly autonomyLevels = Object.entries(AUTONOMY_LEVEL) as [AutonomyLevel, { label: string; description: string }][];
  protected readonly operations = Object.entries(SENSITIVE_OPERATION) as [SensitiveOperation, string][];
  protected readonly connection = PROVIDER_CONNECTION;
  protected readonly saving = signal(false);
  protected readonly submitted = signal(false);

  protected readonly form = this.fb.group(
    {
      fullName: ['', [Validators.required, notBlank, Validators.minLength(2), Validators.maxLength(60)]],
      departmentId: ['', Validators.required],
      roleId: ['', Validators.required],
      roleDescription: ['', [Validators.required, notBlank, Validators.minLength(20), Validators.maxLength(600)]],
      responsibilities: this.fb.control<readonly string[]>([], minItems(1)),
      skills: this.fb.control<readonly string[]>([], minItems(1)),
      providerId: ['', Validators.required],
      modelId: ['', Validators.required],
      fallbackProviderId: [''],
      fallbackModelId: [''],
      systemInstructions: ['', [Validators.required, notBlank, Validators.minLength(20), Validators.maxLength(4000)]],
      permissionLevel: this.fb.control<PermissionLevel>('contributor'),
      autonomyLevel: this.fb.control<AutonomyLevel>('supervised'),
      requireApprovalFor: this.fb.control<readonly SensitiveOperation[]>(DEFAULT_APPROVALS),
      maxConcurrentTasks: [3, [Validators.required, Validators.min(1), Validators.max(10)]],
      assignedProjectIds: this.fb.control<readonly string[]>([]),
    },
    { validators: fallbackRequiresModel },
  );

  private readonly formValue = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });

  protected readonly roles = computed(() => this.organization.rolesForDepartment(this.formValue().departmentId || null));
  protected readonly models = computed(() => this.agentStore.modelsForProvider(this.formValue().providerId || null));
  protected readonly fallbackModels = computed(() =>
    this.agentStore.modelsForProvider(this.formValue().fallbackProviderId || null),
  );
  protected readonly selectedProvider = computed(() =>
    this.agentStore.providerById().get(this.formValue().providerId ?? ''),
  );
  protected readonly isEdit = computed(() => this.agent() !== null);
  protected readonly invalidCount = computed(() => {
    this.formValue();
    return Object.values(this.form.controls).filter((c) => c.invalid).length + (this.form.hasError('fallbackModel') ? 1 : 0);
  });

  constructor() {
    // Reset the form each time the modal opens.
    effect(() => {
      if (this.open()) {
        const agent = this.agent();
        untracked(() => this.reset(agent));
      }
    });

    // Dependent fields: keep role/model consistent with their parent selection.
    this.form.controls.departmentId.valueChanges.pipe(takeUntilDestroyed()).subscribe((departmentId) => {
      const roleId = this.form.controls.roleId.value;
      if (roleId && !this.organization.rolesForDepartment(departmentId).some((r) => r.id === roleId)) {
        this.form.controls.roleId.setValue('');
      }
    });
    this.form.controls.providerId.valueChanges.pipe(takeUntilDestroyed()).subscribe((providerId) => {
      if (!this.agentStore.modelsForProvider(providerId).some((m) => m.id === this.form.controls.modelId.value)) {
        this.form.controls.modelId.setValue(this.agentStore.modelsForProvider(providerId)[0]?.id ?? '');
      }
    });
    this.form.controls.fallbackProviderId.valueChanges.pipe(takeUntilDestroyed()).subscribe((providerId) => {
      const models = this.agentStore.modelsForProvider(providerId || null);
      if (!models.some((m) => m.id === this.form.controls.fallbackModelId.value)) {
        this.form.controls.fallbackModelId.setValue(models[0]?.id ?? '');
      }
    });
  }

  protected error(name: keyof typeof this.form.controls, label: string): string | null {
    this.formValue();
    return errorMessage(this.form.controls[name], label);
  }

  protected isOperationChecked(operation: SensitiveOperation): boolean {
    return this.form.controls.requireApprovalFor.value.includes(operation);
  }

  protected toggleOperation(operation: SensitiveOperation, checked: boolean): void {
    const control = this.form.controls.requireApprovalFor;
    const current = control.value.filter((o) => o !== operation);
    control.setValue(checked ? [...current, operation] : current);
    control.markAsDirty();
  }

  protected isProjectChecked(projectId: string): boolean {
    return this.form.controls.assignedProjectIds.value.includes(projectId);
  }

  protected toggleProject(projectId: string, checked: boolean): void {
    const control = this.form.controls.assignedProjectIds;
    const current = control.value.filter((id) => id !== projectId);
    control.setValue(checked ? [...current, projectId] : current);
    control.markAsDirty();
  }

  protected async submit(): Promise<void> {
    this.submitted.set(true);
    this.form.markAllAsTouched();
    if (this.form.invalid || this.saving()) {
      return;
    }
    this.saving.set(true);
    try {
      const draft = this.toDraft();
      const existing = this.agent();
      const result = existing
        ? await this.agentStore.updateAgent(existing.id, draft)
        : await this.agentStore.createAgent(draft);
      this.toast.success(
        existing ? 'Agent updated' : 'Agent registered',
        existing
          ? `${result.identity.fullName}'s configuration was saved in the local mock workspace.`
          : `${result.identity.fullName} was added to the local mock workspace. It stays offline until the orchestration engine is connected.`,
      );
      this.saved.emit(result);
    } catch (error) {
      this.toast.error('Could not save the agent', error);
    } finally {
      this.saving.set(false);
    }
  }

  protected close(): void {
    if (!this.saving()) {
      this.closed.emit();
    }
  }

  private toDraft(): AgentDraft {
    const v = this.form.getRawValue();
    const providers: AgentProviderBinding[] = [{ providerId: v.providerId, modelId: v.modelId, priority: 'primary' }];
    if (v.fallbackProviderId && v.fallbackModelId) {
      providers.push({ providerId: v.fallbackProviderId, modelId: v.fallbackModelId, priority: 'fallback' });
    }
    return {
      identity: {
        fullName: v.fullName.trim(),
        departmentId: v.departmentId,
        roleId: v.roleId,
        roleDescription: v.roleDescription.trim(),
        responsibilities: v.responsibilities,
        skills: v.skills,
      },
      providers,
      systemInstructions: v.systemInstructions.trim(),
      permissionLevel: v.permissionLevel,
      autonomy: {
        level: v.autonomyLevel,
        requireApprovalFor: v.requireApprovalFor,
        maxConcurrentTasks: v.maxConcurrentTasks,
      },
      assignedProjectIds: v.assignedProjectIds,
    };
  }

  private reset(agent: Agent | null): void {
    this.submitted.set(false);
    if (!agent) {
      const providerId = this.agentStore.providers()[0]?.id ?? '';
      this.form.reset({
        providerId,
        modelId: this.agentStore.modelsForProvider(providerId)[0]?.id ?? '',
        requireApprovalFor: DEFAULT_APPROVALS,
      });
      return;
    }
    const primary = agent.providers.find((p) => p.priority === 'primary') ?? agent.providers[0];
    const fallback = agent.providers.find((p) => p.priority === 'fallback');
    this.form.reset({
      fullName: agent.identity.fullName,
      departmentId: agent.identity.departmentId,
      roleId: agent.identity.roleId,
      roleDescription: agent.identity.roleDescription,
      responsibilities: agent.identity.responsibilities,
      skills: agent.identity.skills,
      providerId: primary?.providerId ?? '',
      modelId: primary?.modelId ?? '',
      fallbackProviderId: fallback?.providerId ?? '',
      fallbackModelId: fallback?.modelId ?? '',
      systemInstructions: agent.systemInstructions,
      permissionLevel: agent.permissionLevel,
      autonomyLevel: agent.autonomy.level,
      requireApprovalFor: agent.autonomy.requireApprovalFor,
      maxConcurrentTasks: agent.autonomy.maxConcurrentTasks,
      assignedProjectIds: agent.assignedProjectIds,
    });
  }
}
