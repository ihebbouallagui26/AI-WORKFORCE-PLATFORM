import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';
import {
  addStage,
  addStep,
  BuilderIssue,
  BuilderStage,
  isParallel,
  moveStage,
  removeStage,
  removeStep,
  updateStep,
} from '../../core/workflows/pipeline-builder.model';
import { Icon } from '../../shared/components/icon/icon';

export interface AgentOption {
  readonly id: string;
  readonly name: string;
}

/**
 * Visual editor of a pipeline: a list of stages shown left to right. Steps inside one stage are cards grouped together and run in PARALLEL;
 * a stage depends on the one before it (fan-in), which is drawn as an arrow. Only participants can be assigned. Everything is plain,
 * keyboard-reachable form controls (no drag and drop): add, remove and reorder with buttons. The structure cannot express a cycle.
 */
@Component({
  selector: 'app-pipeline-builder',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol class="stages" aria-label="Pipeline stages">
      @for (stage of stages(); track stage.key; let i = $index; let last = $last) {
        <li class="stage" [class.stage--parallel]="stage.steps.length > 1" [attr.data-testid]="'stage-' + (i + 1)">
          <header class="stage__head">
            <strong>Stage {{ i + 1 }}</strong>
            <span class="text-tertiary" data-testid="stage-mode">
              {{ stage.steps.length > 1 ? stage.steps.length + ' steps in parallel' : i === 0 ? 'Starts first' : 'Starts after stage ' + i }}
            </span>
            <span class="head-actions">
              <button type="button" class="btn btn--ghost btn--sm" [disabled]="disabled() || i === 0" [attr.aria-label]="'Move stage ' + (i + 1) + ' earlier'" (click)="apply(move(stage.key, -1))">↑</button>
              <button type="button" class="btn btn--ghost btn--sm" [disabled]="disabled() || last" [attr.aria-label]="'Move stage ' + (i + 1) + ' later'" (click)="apply(move(stage.key, 1))">↓</button>
              <button type="button" class="btn btn--ghost btn--sm" [disabled]="disabled() || stages().length === 1" [attr.aria-label]="'Remove stage ' + (i + 1)" (click)="apply(drop(stage.key))">Remove stage</button>
            </span>
          </header>
          @if (i > 0) { <p class="dep text-tertiary" data-testid="dependency">Receives the results of stage {{ i }} (all of its steps).</p> }
          <div class="steps">
            @for (step of stage.steps; track step.key; let j = $index) {
              <div class="step" [attr.data-testid]="'step-' + (i + 1) + '-' + (j + 1)">
                <label class="field__label" [for]="'n-' + step.key">Name</label>
                <input class="input" [id]="'n-' + step.key" maxlength="120" [value]="step.name" [disabled]="disabled()" (input)="edit(step.key, { name: val($event) })" />
                <label class="field__label" [for]="'a-' + step.key">Agent</label>
                <select class="select" [id]="'a-' + step.key" [value]="step.agentId" [disabled]="disabled()" (change)="edit(step.key, { agentId: val($event) })">
                  <option value="">Choose an agent…</option>
                  @for (agent of agents(); track agent.id) { <option [value]="agent.id" [selected]="agent.id === step.agentId">{{ agent.name }}</option> }
                </select>
                <label class="field__label" [for]="'i-' + step.key">Task</label>
                <textarea class="textarea" rows="2" [id]="'i-' + step.key" maxlength="2000" [value]="step.instruction" [disabled]="disabled()" (input)="edit(step.key, { instruction: val($event) })"></textarea>
                <button type="button" class="btn btn--ghost btn--sm" [disabled]="disabled() || (stages().length === 1 && stage.steps.length === 1)" (click)="apply(dropStep(stage.key, step.key))">Remove step</button>
              </div>
            }
          </div>
          <button type="button" class="btn btn--sm" [disabled]="disabled() || stage.steps.length >= maxParallel()" data-testid="add-step" (click)="apply(plusStep(stage.key))">
            {{ stage.steps.length >= maxParallel() ? 'Parallel limit reached' : 'Add a parallel step' }}
          </button>
          @if (!last) { <span class="arrow" aria-hidden="true"><ui-icon name="chevron-right" [size]="16" /></span> }
        </li>
      }
    </ol>
    <button type="button" class="btn btn--sm" data-testid="add-stage" [disabled]="disabled()" (click)="apply(plusStage())">Add a stage</button>
    @if (stageIssues().length) {
      <ul class="issues" data-testid="issues" aria-live="polite">
        @for (issue of stageIssues(); track issue.message) { <li [class.warn]="issue.level === 'warning'">{{ issue.level === 'error' ? 'Error' : 'Note' }}: {{ issue.message }}</li> }
      </ul>
    }
  `,
  styles: `
    .stages { list-style: none; margin: 0 0 var(--space-3); padding: 0; display: flex; flex-direction: column; gap: var(--space-3); }
    .stage { position: relative; padding: var(--space-3); border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface-1); }
    .stage--parallel { border-style: dashed; background: var(--color-surface-2); }
    .stage__head { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; margin-bottom: var(--space-2); }
    .head-actions { margin-left: auto; display: flex; gap: var(--space-1); }
    .dep { margin: 0 0 var(--space-2); font-size: var(--text-xs); }
    .steps { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: var(--space-3); margin-bottom: var(--space-2); }
    .step { display: flex; flex-direction: column; gap: var(--space-1); padding: var(--space-3); border: 1px solid var(--color-border-subtle); border-radius: var(--radius-md); background: var(--color-surface-1); }
    .arrow { position: absolute; left: 50%; bottom: -18px; transform: rotate(90deg); color: var(--color-text-tertiary); }
    .issues { margin: var(--space-3) 0 0; padding-left: var(--space-4); color: var(--color-danger); }
    .issues .warn { color: var(--color-warning, var(--color-text-secondary)); }
  `,
})
export class PipelineBuilder {
  /** Two-way: the builder applies each edit to its own value immediately, so quick successive edits are never lost to a stale input. */
  readonly stages = model.required<readonly BuilderStage[]>();
  readonly agents = input.required<readonly AgentOption[]>();
  readonly maxParallel = input(1);
  readonly issues = input<readonly BuilderIssue[]>([]);
  readonly disabled = input(false);

  protected readonly parallel = computed(() => isParallel(this.stages()));
  protected readonly stageIssues = computed(() => this.issues());

  protected val(event: Event): string {
    return (event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement).value;
  }

  protected edit(stepKey: string, patch: { name?: string; agentId?: string; instruction?: string }): void {
    this.stages.set(updateStep(this.stages(), stepKey, patch));
  }

  protected apply(next: readonly BuilderStage[]): void {
    this.stages.set(next);
  }

  protected plusStage = () => addStage(this.stages());
  protected plusStep = (stageKey: string) => addStep(this.stages(), stageKey);
  protected drop = (stageKey: string) => removeStage(this.stages(), stageKey);
  protected dropStep = (stageKey: string, stepKey: string) => removeStep(this.stages(), stageKey, stepKey);
  protected move = (stageKey: string, delta: -1 | 1) => moveStage(this.stages(), stageKey, delta);
}
