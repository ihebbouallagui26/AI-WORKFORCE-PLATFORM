import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiError } from '../../core/identity/backend-client';
import { ConfirmService } from '../../core/services/confirm.service';
import { ToastService } from '../../core/services/toast.service';
import { orgResource } from '../../core/workforce/org-resource';
import { fromDefinition } from '../../core/workflows/pipeline-builder.model';
import { RUN_STATUS_LABEL } from '../../core/workflows/workflow-view.model';
import { WorkflowsApi } from '../../core/workflows/workflows.api';
import { WorkflowsStore } from '../../core/workflows/workflows.store';
import { Badge } from '../../shared/components/badge/badge';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Panel } from '../../shared/components/panel/panel';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { MODE_TEXT, POLICY_TEXT, RECOVERY_TEXT, safeText } from './workflow-labels';

export const MAX_OBJECTIVE = 2000;

/** One workflow definition: what it is, who takes part, how it runs, and the launch of a new run. */
@Component({
  selector: 'app-workflow-detail',
  imports: [RouterLink, Panel, Badge, EmptyState, Skeleton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <a class="back text-tertiary" routerLink="/workflows">← Workflows</a>
      @if (!store.can('workflow.read')) {
        <ui-empty-state icon="lock" heading="Not available" description="Your role does not allow viewing workflows." />
      } @else if (definition.loading() && !definition.value()) {
        <ui-skeleton [rows]="5" />
      } @else if (definition.error(); as message) {
        <ui-empty-state icon="circle-alert" tone="danger" heading="Workflow unavailable" [description]="message" />
      } @else if (definition.value(); as def) {
        <header class="head">
          <div>
            <h1 class="title" data-testid="name">{{ def.name }}</h1>
            <p class="text-tertiary">{{ def.description || 'No description.' }}</p>
          </div>
          <div class="actions">
            <ui-badge [label]="modeText[def.mode].label" [tone]="def.mode === 'COORDINATED' ? 'ai' : 'info'" />
            <ui-badge [label]="def.status === 'ACTIVE' ? 'Active' : 'Archived'" [tone]="def.status === 'ACTIVE' ? 'success' : 'neutral'" />
            @if (store.can('workflow.manage') && def.status === 'ACTIVE') {
              <a class="btn btn--sm" data-testid="edit" [routerLink]="['/workflows', def.id, 'edit']">Edit</a>
              <button type="button" class="btn btn--ghost btn--sm" data-testid="archive" (click)="archive()">Archive</button>
            }
          </div>
        </header>

        <ui-panel heading="Launch a run" subheading="The objective is given to the workflow when it starts. Runs use the definition as it is now and keep it unchanged.">
          @if (def.status !== 'ACTIVE') {
            <p class="text-tertiary" data-testid="launch-archived">An archived workflow cannot be launched.</p>
          } @else if (!store.can('workflow.execute') || !store.can('agent.execute')) {
            <p class="text-tertiary" data-testid="launch-denied">Launching needs permission to execute workflows and agents.</p>
          } @else {
            <label class="field__label" for="wf-objective">Objective</label>
            <textarea id="wf-objective" class="textarea" rows="3" [attr.maxlength]="maxObjective" [value]="objective()" [disabled]="launching()"
              (input)="objective.set(val($event))" placeholder="Describe what this run must achieve"></textarea>
            <div class="controls">
              <button type="button" class="btn btn--primary btn--sm" data-testid="launch" [disabled]="launching() || !objective().trim()" (click)="launch(def.id)">
                {{ launching() ? 'Launching…' : 'Launch workflow' }}
              </button>
              <span class="text-tertiary">{{ objective().length }} / {{ maxObjective }}</span>
            </div>
            @if (launchError(); as message) { <p class="error" role="alert" data-testid="launch-error">{{ message }}</p> }
          }
        </ui-panel>

        <ui-panel heading="Participants">
          <ul class="plain" data-testid="participants">
            @for (p of def.participants; track p.agentId) {
              <li>
                {{ p.agentName ?? 'Unknown agent' }}
                @if (p.coordinator) { <ui-badge label="Coordinator" tone="ai" /> }
                @if (p.agentStatus && p.agentStatus !== 'ACTIVE') { <ui-badge [label]="'Agent ' + p.agentStatus.toLowerCase()" tone="warning" /> }
              </li>
            } @empty { <li class="text-tertiary">No participant yet.</li> }
          </ul>
        </ui-panel>

        @if (def.mode === 'PIPELINE') {
          <ui-panel heading="Pipeline" subheading="Stages run left to right; steps in the same stage run in parallel.">
            @if (!stages().length) {
              <p class="text-tertiary">No step yet.</p>
            } @else {
              <ol class="stages" data-testid="stages">
                @for (stage of stages(); track stage.key; let i = $index) {
                  <li class="stage" [class.stage--parallel]="stage.steps.length > 1" [attr.data-testid]="'stage-' + (i + 1)">
                    <strong>Stage {{ i + 1 }}</strong>
                    <span class="text-tertiary">{{ stage.steps.length > 1 ? stage.steps.length + ' steps in parallel' : 'one step' }}{{ i > 0 ? ' · after stage ' + i : '' }}</span>
                    <ul class="plain">
                      @for (s of stage.steps; track s.key) { <li><strong>{{ s.name }}</strong> <span class="text-tertiary">· {{ agentName(def, s.agentId) }}</span><br /><span class="task">{{ s.instruction }}</span></li> }
                    </ul>
                  </li>
                }
              </ol>
            }
          </ui-panel>
        } @else {
          <ui-panel heading="Coordination">
            <p>The coordinator ({{ coordinatorName(def) }}) decides what to delegate. Up to {{ def.maxParallelWorkers }} independent task(s) can run at once; the Core API validates every delegation.</p>
          </ui-panel>
        }

        <ui-panel heading="Limits and failure handling">
          <dl class="dl" data-testid="limits">
            <div><dt>When a step fails</dt><dd>{{ policyText[def.failurePolicy] }}</dd></div>
            <div><dt>After a safe failure</dt><dd>{{ recoveryText[def.recoveryMode] }}</dd></div>
            <div><dt>Automatic retries per step</dt><dd>{{ def.maxRetriesPerStep }}</dd></div>
            <div><dt>Parallel workers</dt><dd>{{ def.maxParallelWorkers }}</dd></div>
            <div><dt>Token budget</dt><dd>{{ def.tokenBudget.toLocaleString('en-US') }} tokens</dd></div>
            <div><dt>Total time limit</dt><dd>{{ def.deadlineSeconds }} s</dd></div>
            <div><dt>Time limit per step</dt><dd>{{ def.stepTimeoutSeconds ? def.stepTimeoutSeconds + ' s' : 'none' }}</dd></div>
            <div><dt>Version</dt><dd>{{ def.version }}</dd></div>
          </dl>
        </ui-panel>

        <ui-panel heading="Runs of this workflow">
          @if (runs().length) {
            <ul class="plain" data-testid="runs">
              @for (run of runs(); track run.id) {
                <li>
                  <a [routerLink]="['/workflows/runs', run.id]">{{ shorten(run.objective) }}</a>
                  <ui-badge [label]="runStatus[run.status].label" [tone]="runStatus[run.status].tone" />
                  <span class="text-tertiary">{{ run.createdAt }}</span>
                </li>
              }
            </ul>
          } @else {
            <p class="text-tertiary">No run yet.</p>
          }
        </ui-panel>
      }
    </div>
  `,
  styles: `
    .page { display: flex; flex-direction: column; gap: var(--space-4); }
    .back { text-decoration: none; }
    .head { display: flex; justify-content: space-between; gap: var(--space-4); flex-wrap: wrap; align-items: flex-start; }
    .title { margin: 0; font-size: var(--text-xl); overflow-wrap: anywhere; }
    .actions { display: flex; gap: var(--space-2); align-items: center; flex-wrap: wrap; }
    .controls { display: flex; gap: var(--space-3); align-items: center; margin-top: var(--space-2); }
    .error { color: var(--color-danger); }
    .stages { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--space-3); }
    .stage { padding: var(--space-3); border: 1px solid var(--color-border); border-radius: var(--radius-md); }
    .stage--parallel { border-style: dashed; background: var(--color-surface-2); }
    .task { overflow-wrap: anywhere; white-space: pre-wrap; }
    .dl { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: var(--space-2) var(--space-4); margin: 0; }
    .dl dt { color: var(--color-text-tertiary); font-size: var(--text-xs); }
    .dl dd { margin: 0; }
  `,
})
export class WorkflowDetail {
  readonly id = input.required<string>();

  protected readonly store = inject(WorkflowsStore);
  private readonly api = inject(WorkflowsApi);
  private readonly router = inject(Router);
  private readonly confirm = inject(ConfirmService);
  private readonly toast = inject(ToastService);

  protected readonly maxObjective = MAX_OBJECTIVE;
  protected readonly modeText = MODE_TEXT;
  protected readonly policyText = POLICY_TEXT;
  protected readonly recoveryText = RECOVERY_TEXT;
  protected readonly runStatus = RUN_STATUS_LABEL;

  protected readonly definition = orgResource(
    this.store.organizationId,
    () => this.store.can('workflow.read'),
    () => this.id(),
    (org, id) => this.api.getDefinition(org, id),
  );
  protected readonly stages = computed(() => fromDefinition(this.definition.value()?.steps ?? []));
  protected readonly runs = computed(() => this.store.runs().filter((r) => r.definitionId === this.id()).slice(0, 10));
  protected readonly objective = signal('');
  protected readonly launching = signal(false);
  protected readonly launchError = signal<string | null>(null);

  /** One idempotency key per launch intent: pressing the button twice never creates two runs. */
  private requestId: string | null = null;

  protected val(event: Event): string {
    return (event.target as HTMLTextAreaElement).value;
  }

  protected shorten(text: string): string {
    return safeText(text, 90);
  }

  protected agentName(def: { participants: readonly { agentId: string; agentName: string | null }[] }, id: string): string {
    return def.participants.find((p) => p.agentId === id)?.agentName ?? 'Unknown agent';
  }

  protected coordinatorName(def: { participants: readonly { coordinator: boolean; agentName: string | null }[] }): string {
    return def.participants.find((p) => p.coordinator)?.agentName ?? 'not chosen';
  }

  protected async launch(definitionId: string): Promise<void> {
    const objective = this.objective().trim();
    if (!objective || this.launching()) return;
    this.requestId ??= crypto.randomUUID();
    this.launching.set(true);
    this.launchError.set(null);
    try {
      const run = await this.store.mutate((org) => this.api.startRun(org, definitionId, objective, this.requestId as string));
      this.requestId = null;
      this.toast.success('Workflow launched', null);
      await this.router.navigate(['/workflows/runs', run.id]);
    } catch (e) {
      this.launchError.set(e instanceof ApiError ? safeText(e.problem?.detail ?? e.message, 300) : 'The workflow could not be launched.');
    } finally {
      this.launching.set(false);
    }
  }

  protected async archive(): Promise<void> {
    const result = await this.confirm.ask({
      title: 'Archive this workflow?',
      message: 'It can no longer be launched or edited. Runs already started keep going.',
      confirmLabel: 'Archive',
      tone: 'danger',
    });
    if (!result.confirmed) return;
    try {
      await this.store.mutate((org) => this.api.archiveDefinition(org, this.id()));
      this.definition.reload();
      this.toast.success('Workflow archived', null);
    } catch (e) {
      this.toast.error('The workflow could not be archived.', e instanceof ApiError ? safeText(e.message, 200) : null);
    }
  }
}
