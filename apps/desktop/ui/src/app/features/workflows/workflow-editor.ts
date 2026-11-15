import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AgentsStore } from '../../core/agents/agents.store';
import { ApiError } from '../../core/identity/backend-client';
import { ToastService } from '../../core/services/toast.service';
import { orgResource } from '../../core/workforce/org-resource';
import {
  BuilderIssue,
  BuilderStage,
  emptyStage,
  fromDefinition,
  hasErrors,
  isParallel,
  stepCount,
  toStepInputs,
  validateBuilder,
} from '../../core/workflows/pipeline-builder.model';
import { WorkflowsApi } from '../../core/workflows/workflows.api';
import { WorkflowsStore } from '../../core/workflows/workflows.store';
import { FailurePolicy, RecoveryMode, WorkflowDefinition, WorkflowMode } from '../../core/workflows/workflows.models';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Panel } from '../../shared/components/panel/panel';
import { SegmentOption, Segmented } from '../../shared/components/segmented/segmented';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { MODE_TEXT, POLICY_HELP, POLICY_TEXT, RECOVERY_TEXT, safeText } from './workflow-labels';
import { PipelineBuilder } from './pipeline-builder';

const MODES: readonly SegmentOption<WorkflowMode>[] = [
  { value: 'PIPELINE', label: 'Pipeline' },
  { value: 'COORDINATED', label: 'Coordinated' },
];
const POLICIES: readonly FailurePolicy[] = ['FAIL_FAST', 'CONTINUE', 'RETRY'];

/**
 * Creates or edits a workflow definition. Every field maps to a field the Core API supports and validates again; nothing here is a
 * frontend-only capability. Editing creates a NEW version of the definition: a run already started keeps its own immutable snapshot.
 * The user reviews the whole configuration (participants, steps or coordinator, limits) before saving.
 */
@Component({
  selector: 'app-workflow-editor',
  imports: [RouterLink, Panel, EmptyState, Skeleton, Segmented, PipelineBuilder],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <a class="back text-tertiary" [routerLink]="existing() ? ['/workflows', id()] : ['/workflows']">← {{ existing() ? 'Workflow' : 'Workflows' }}</a>
      <h1 class="title">{{ id() ? 'Edit workflow' : 'New workflow' }}</h1>
      @if (!store.can('workflow.manage')) {
        <ui-empty-state icon="lock" heading="Not available" description="Your role does not allow creating or changing workflows." />
      } @else if (!store.can('agent.read')) {
        <ui-empty-state icon="lock" heading="Agents unavailable" description="Choosing participants needs permission to read agents." />
      } @else if (id() && definition.loading() && !definition.value()) {
        <ui-skeleton [rows]="5" />
      } @else if (id() && definition.error(); as message) {
        <ui-empty-state icon="circle-alert" tone="danger" heading="Workflow unavailable" [description]="message" />
      } @else {
        @if (existing()) {
          <p class="text-tertiary" data-testid="version-note">Saving creates version {{ existing()!.version + 1 }}. Runs already started keep the version they started with.</p>
        }

        <ui-panel heading="General">
          <label class="field__label" for="wf-name">Name</label>
          <input id="wf-name" class="input" maxlength="120" [value]="name()" (input)="name.set(val($event))" />
          <label class="field__label" for="wf-desc">Description / objective</label>
          <textarea id="wf-desc" class="textarea" rows="2" maxlength="2000" [value]="description()" (input)="description.set(val($event))"
            placeholder="What this workflow is for. The objective of each run is given when it is launched."></textarea>
          <span class="field__label" id="mode-label">Mode</span>
          <ui-segmented [options]="modes" [value]="mode()" (valueChange)="setMode($event)" label="Workflow mode" />
          <p class="text-tertiary" data-testid="mode-help">{{ modeHelp() }}</p>
          @if (existing()) { <p class="text-tertiary">The mode of an existing workflow cannot be changed.</p> }
        </ui-panel>

        <ui-panel heading="Participants" subheading="Only agents chosen here can work in this workflow. A team never adds agents by itself.">
          @if (agents.loading() && !agents.agents().length) {
            <ui-skeleton [rows]="2" />
          } @else {
            <ul class="plain checklist" data-testid="participants">
              @for (agent of agents.agents(); track agent.id) {
                <li>
                  <label class="check">
                    <input type="checkbox" class="checkbox" [checked]="participants().includes(agent.id)" [disabled]="agent.status !== 'ACTIVE'" (change)="toggle(agent.id)" />
                    {{ agent.name }}
                    <span class="text-tertiary">{{ agent.status === 'ACTIVE' ? agent.title : 'Not active: activate it to use it here' }}</span>
                  </label>
                </li>
              } @empty {
                <li class="text-tertiary">No agent yet. Create and activate agents first.</li>
              }
            </ul>
          }
        </ui-panel>

        @if (mode() === 'COORDINATED') {
          <ui-panel heading="Coordinator" subheading="The coordinator plans and delegates. It does not bypass authorization or tool approval.">
            <label class="field__label" for="wf-coordinator">Coordinator agent</label>
            <select id="wf-coordinator" class="select" [value]="coordinator()" (change)="coordinator.set(val($event))">
              <option value="">Choose the coordinator…</option>
              @for (agent of selectedAgents(); track agent.id) { <option [value]="agent.id" [selected]="agent.id === coordinator()">{{ agent.name }}</option> }
            </select>
            <ul class="plain" data-testid="responsibilities">
              @for (agent of selectedAgents(); track agent.id) {
                <li><strong>{{ agent.name }}</strong> <span class="text-tertiary">{{ agent.id === coordinator() ? '— coordinator: decides what to delegate and when to finish' : '— worker: runs the tasks delegated to it' }}</span></li>
              }
            </ul>
            <p class="text-tertiary">After the coordinator delegates independent tasks, the workers can run in parallel (up to the limit below). The coordinator then receives all results and decides what happens next. Each worker keeps its own tools and approvals.</p>
          </ui-panel>
        } @else {
          <ui-panel heading="Pipeline" subheading="Stages run left to right; steps of the same stage run in parallel; a stage waits for the stage before it.">
            <app-pipeline-builder [(stages)]="stages" [agents]="agentOptions()" [maxParallel]="maxParallel()" [issues]="stageIssues()" />
          </ui-panel>
        }

        <ui-panel heading="Failure handling and limits">
          <label class="field__label" for="wf-policy">When a step fails</label>
          <select id="wf-policy" class="select" [value]="failurePolicy()" (change)="failurePolicy.set(asPolicy(val($event)))">
            @for (p of policies; track p) { <option [value]="p" [selected]="p === failurePolicy()">{{ policyText[p] }}</option> }
          </select>
          <p class="text-tertiary" data-testid="policy-help">{{ policyHelp[failurePolicy()] }}</p>
          <div class="grid">
            <div>
              <label class="field__label" for="wf-parallel">Parallel workers</label>
              <input id="wf-parallel" class="input" type="number" min="1" max="3" [value]="maxParallel()" (input)="maxParallel.set(num($event, 1))" />
              <span class="text-tertiary hint">1 = one step at a time. At most 3 (the organization allows 3 open executions).</span>
            </div>
            <div>
              <label class="field__label" for="wf-retries">Automatic retries per step</label>
              <input id="wf-retries" class="input" type="number" min="0" max="2" [value]="retries()" (input)="retries.set(num($event, 0))" />
              <span class="text-tertiary hint">Only temporary provider failures, before any external action.</span>
            </div>
            <div>
              <label class="field__label" for="wf-budget">Token budget</label>
              <input id="wf-budget" class="input" type="number" min="1000" max="10000000" [value]="tokenBudget()" (input)="tokenBudget.set(num($event, 200000))" />
              <span class="text-tertiary hint">Tokens, not money.</span>
            </div>
            <div>
              <label class="field__label" for="wf-deadline">Total time limit (seconds)</label>
              <input id="wf-deadline" class="input" type="number" min="30" max="86400" [value]="deadlineSeconds()" (input)="deadlineSeconds.set(num($event, 1800))" />
            </div>
            <div>
              <label class="field__label" for="wf-step-timeout">Time limit per step (seconds, optional)</label>
              <input id="wf-step-timeout" class="input" type="number" min="0" max="86400" [value]="stepTimeout()" (input)="stepTimeout.set(num($event, 0))" />
              <span class="text-tertiary hint">0 = no per-step limit.</span>
            </div>
          </div>
          <label class="field__label" for="wf-recovery">After a safe failure</label>
          <select id="wf-recovery" class="select" [value]="recoveryMode()" (change)="recoveryMode.set(asRecovery(val($event)))" [disabled]="maxParallel() > 1">
            <option value="NONE" [selected]="recoveryMode() === 'NONE'">{{ recoveryText.NONE }}</option>
            <option value="MANUAL" [selected]="recoveryMode() === 'MANUAL'">{{ recoveryText.MANUAL }}</option>
          </select>
          @if (maxParallel() > 1) { <p class="text-tertiary" data-testid="recovery-parallel">Manual recovery is not available with parallel workers.</p> }
        </ui-panel>

        <ui-panel heading="Review" subheading="Check the configuration before saving.">
          <ul class="plain" data-testid="review">
            @for (line of reviewLines(); track line) { <li>{{ line }}</li> }
          </ul>
          @if (issues().length) {
            <ul class="issues" role="alert" data-testid="form-issues">
              @for (issue of issues(); track issue.message) { <li>{{ issue.level === 'error' ? 'Error' : 'Note' }}: {{ issue.message }}</li> }
            </ul>
          }
          @if (saveError(); as message) { <p class="error" role="alert" data-testid="save-error">{{ message }}</p> }
          <div class="controls">
            <button type="button" class="btn btn--primary" data-testid="save" [disabled]="saving() || blocked()" (click)="save()">{{ saving() ? 'Saving…' : id() ? 'Save new version' : 'Create workflow' }}</button>
            <a class="btn btn--ghost" [routerLink]="existing() ? ['/workflows', id()] : ['/workflows']">Cancel</a>
          </div>
        </ui-panel>
      }
    </div>
  `,
  styles: `
    .page { display: flex; flex-direction: column; gap: var(--space-4); max-width: 1000px; }
    .back { text-decoration: none; }
    .title { margin: 0; font-size: var(--text-xl); }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: var(--space-3); margin: var(--space-3) 0; }
    .hint { display: block; font-size: var(--text-xs); margin-top: var(--space-1); }
    .checklist { display: grid; gap: var(--space-2); }
    .check { display: flex; gap: var(--space-2); align-items: center; }
    .controls { display: flex; gap: var(--space-2); margin-top: var(--space-3); }
    .issues { color: var(--color-danger); padding-left: var(--space-4); }
    .error { color: var(--color-danger); }
  `,
})
export class WorkflowEditor {
  /** Route parameter `:id` when editing; absent when creating. */
  readonly id = input<string | undefined>(undefined);

  protected readonly store = inject(WorkflowsStore);
  protected readonly agents = inject(AgentsStore);
  private readonly api = inject(WorkflowsApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);

  protected readonly modes = MODES;
  protected readonly policies = POLICIES;
  protected readonly policyText = POLICY_TEXT;
  protected readonly policyHelp = POLICY_HELP;
  protected readonly recoveryText = RECOVERY_TEXT;

  protected readonly definition = orgResource(
    this.store.organizationId,
    () => !!this.id() && this.store.can('workflow.read'),
    () => this.id() ?? '',
    (org, id) => this.api.getDefinition(org, id),
  );
  protected readonly existing = computed<WorkflowDefinition | null>(() => this.definition.value());

  protected readonly name = signal('');
  protected readonly description = signal('');
  protected readonly mode = signal<WorkflowMode>('PIPELINE');
  protected readonly failurePolicy = signal<FailurePolicy>('FAIL_FAST');
  protected readonly recoveryMode = signal<RecoveryMode>('NONE');
  protected readonly maxParallel = signal(1);
  protected readonly retries = signal(0);
  protected readonly tokenBudget = signal(200000);
  protected readonly deadlineSeconds = signal(1800);
  protected readonly stepTimeout = signal(0);
  protected readonly participants = signal<readonly string[]>([]);
  protected readonly coordinator = signal('');
  protected readonly stages = signal<readonly BuilderStage[]>([emptyStage()]);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly selectedAgents = computed(() => this.agents.agents().filter((a) => this.participants().includes(a.id)));
  protected readonly agentOptions = computed(() => this.selectedAgents().map((a) => ({ id: a.id, name: a.name })));
  protected readonly modeHelp = computed(() => MODE_TEXT[this.mode()].help);

  /** Mirrors the Core API validation so mistakes show before saving; the Core API validates again and decides. */
  protected readonly stageIssues = computed<readonly BuilderIssue[]>(() =>
    this.mode() === 'PIPELINE' ? validateBuilder(this.stages(), this.participants(), this.maxParallel()) : [],
  );
  protected readonly issues = computed<readonly BuilderIssue[]>(() => {
    const out: BuilderIssue[] = [];
    const error = (message: string) => out.push({ level: 'error', message });
    if (!this.name().trim()) error('A name is required.');
    if (this.name().trim().length > 120) error('The name is too long (120 characters at most).');
    if (this.description().length > 2000) error('The description is too long (2000 characters at most).');
    if (this.maxParallel() < 1 || this.maxParallel() > 3) error('Parallel workers must be between 1 and 3.');
    if (this.retries() < 0 || this.retries() > 2) error('Automatic retries must be between 0 and 2.');
    if (this.tokenBudget() < 1000 || this.tokenBudget() > 10_000_000) error('The token budget must be between 1,000 and 10,000,000.');
    if (this.deadlineSeconds() < 30 || this.deadlineSeconds() > 86_400) error('The total time limit must be between 30 and 86,400 seconds.');
    if (this.stepTimeout() !== 0 && (this.stepTimeout() < 10 || this.stepTimeout() > this.deadlineSeconds())) error('The per-step limit must be 0, or between 10 seconds and the total time limit.');
    if (this.failurePolicy() === 'RETRY' && this.retries() < 1) error('The retry policy needs at least 1 automatic retry per step.');
    if (this.recoveryMode() === 'MANUAL' && (this.failurePolicy() === 'CONTINUE' || this.maxParallel() > 1)) error('Manual recovery cannot be combined with the continue policy or parallel workers.');
    if (this.participants().length === 0) error('Choose at least one participant.');
    if (this.mode() === 'COORDINATED') {
      if (!this.coordinator() || !this.participants().includes(this.coordinator())) error('Choose a coordinator among the participants.');
      else if (this.participants().length < 2) error('A coordinated workflow needs at least one worker besides the coordinator.');
    } else {
      out.push(...this.stageIssues());
    }
    return out;
  });
  protected readonly blocked = computed(() => hasErrors(this.issues()));

  protected readonly reviewLines = computed<readonly string[]>(() => {
    const lines = [
      `${this.name().trim() || '(no name)'} — ${MODE_TEXT[this.mode()].label}`,
      `Participants: ${this.selectedAgents().map((a) => a.name).join(', ') || 'none'}`,
    ];
    if (this.mode() === 'COORDINATED') {
      lines.push(`Coordinator: ${this.selectedAgents().find((a) => a.id === this.coordinator())?.name ?? 'not chosen'}`);
    } else {
      lines.push(`${this.stages().length} stage(s), ${stepCount(this.stages())} step(s)${isParallel(this.stages()) ? ', with parallel stages' : ', sequential'}`);
    }
    lines.push(`Failure handling: ${POLICY_TEXT[this.failurePolicy()]}; ${RECOVERY_TEXT[this.recoveryMode()].toLowerCase()}`);
    lines.push(`Up to ${this.maxParallel()} worker(s) at once; ${this.retries()} automatic retry(ies); ${this.tokenBudget().toLocaleString('en-US')} tokens; ${this.deadlineSeconds()} s total${this.stepTimeout() ? `; ${this.stepTimeout()} s per step` : ''}`);
    return lines;
  });

  private hydrated: string | null = null;

  constructor() {
    effect(() => {
      const def = this.existing();
      if (def && def.id !== this.hydrated) {
        this.hydrated = def.id;
        untracked(() => this.hydrate(def));
      }
    });
  }

  private hydrate(def: WorkflowDefinition): void {
    this.name.set(def.name);
    this.description.set(def.description);
    this.mode.set(def.mode);
    this.failurePolicy.set(def.failurePolicy);
    this.recoveryMode.set(def.recoveryMode);
    this.maxParallel.set(def.maxParallelWorkers);
    this.retries.set(def.maxRetriesPerStep);
    this.tokenBudget.set(def.tokenBudget);
    this.deadlineSeconds.set(def.deadlineSeconds);
    this.stepTimeout.set(def.stepTimeoutSeconds ?? 0);
    this.participants.set(def.participants.map((p) => p.agentId));
    this.coordinator.set(def.coordinatorAgentId ?? '');
    this.stages.set(def.steps.length ? fromDefinition(def.steps) : [emptyStage()]);
  }

  protected val(event: Event): string {
    return (event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement).value;
  }

  protected num(event: Event, fallback: number): number {
    const n = Number.parseInt(this.val(event), 10);
    return Number.isFinite(n) ? n : fallback;
  }

  protected asPolicy(value: string): FailurePolicy {
    return (POLICIES as readonly string[]).includes(value) ? (value as FailurePolicy) : 'FAIL_FAST';
  }

  protected asRecovery(value: string): RecoveryMode {
    return value === 'MANUAL' ? 'MANUAL' : 'NONE';
  }

  protected setMode(mode: WorkflowMode): void {
    if (!this.existing()) {
      this.mode.set(mode);
    }
  }

  protected toggle(agentId: string): void {
    this.participants.update((list) => (list.includes(agentId) ? list.filter((x) => x !== agentId) : [...list, agentId]));
    if (!this.participants().includes(this.coordinator())) {
      this.coordinator.set('');
    }
  }

  /**
   * Saves through the Core API in an order that respects its rules: participants are first ADDED (an agent used by a step cannot be removed),
   * then the steps are set, then agents no longer wanted are removed; a lowered parallel bound is applied after the steps are narrowed.
   * Every call is validated by the Core API; a refusal is shown as it is and nothing is assumed saved.
   */
  protected async save(): Promise<void> {
    if (this.blocked() || this.saving()) return;
    this.saving.set(true);
    this.saveError.set(null);
    try {
      const saved = await this.store.mutate((org) => this.persist(org));
      this.toast.success(this.id() ? 'New version saved' : 'Workflow created', null);
      await this.router.navigate(['/workflows', saved.id]);
    } catch (e) {
      this.saveError.set(e instanceof ApiError ? safeText(e.problem?.detail ?? e.message, 300) : 'The workflow could not be saved.');
    } finally {
      this.saving.set(false);
    }
  }

  private async persist(org: string): Promise<WorkflowDefinition> {
    const wanted = this.participants();
    const settings = {
      name: this.name().trim(),
      description: this.description().trim(),
      maxRetriesPerStep: this.retries(),
      tokenBudget: this.tokenBudget(),
      deadlineSeconds: this.deadlineSeconds(),
      failurePolicy: this.failurePolicy(),
      recoveryMode: this.recoveryMode(),
      stepTimeoutSeconds: this.stepTimeout(),
      maxParallelWorkers: this.maxParallel(),
    };
    const coordinatorId = this.mode() === 'COORDINATED' ? this.coordinator() : undefined;
    const current = this.existing();
    let definition: WorkflowDefinition;
    if (!current) {
      definition = await this.api.createDefinition(org, { ...settings, mode: this.mode() });
    } else {
      const lowering = settings.maxParallelWorkers < current.maxParallelWorkers;
      if (!lowering) definition = await this.api.updateDefinition(org, current.id, settings);
      else definition = current;
    }
    const existingIds = definition.participants.map((p) => p.agentId);
    const union = [...new Set([...existingIds, ...wanted])];
    definition = await this.api.setParticipants(org, definition.id, union, coordinatorId && union.includes(coordinatorId) ? coordinatorId : undefined);
    if (this.mode() === 'PIPELINE') {
      definition = await this.api.setSteps(org, definition.id, toStepInputs(this.stages()));
    }
    if (current && settings.maxParallelWorkers < current.maxParallelWorkers) {
      definition = await this.api.updateDefinition(org, definition.id, settings);
    }
    if (union.length !== wanted.length) {
      definition = await this.api.setParticipants(org, definition.id, wanted, coordinatorId);
    }
    return definition;
  }
}
