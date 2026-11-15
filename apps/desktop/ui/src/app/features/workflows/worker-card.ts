import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { ExecutionStatus } from '../../core/agents/agents.models';
import { FAILURE_LABEL, formatDuration, formatTokens, presentStep, stepDurationMs, WorkerCard } from '../../core/workflows/workflow-view.model';
import { AttemptUsage } from '../../core/workflows/workflows.models';
import { Badge } from '../../shared/components/badge/badge';

/**
 * One worker (or one coordinator turn) of a run: its CURRENT attempt, with the number of attempts made. The status wording is exact: a
 * queued worker is "Waiting for capacity", a dispatched one is not called running until the execution is confirmed running, and a failed
 * attempt is never shown as a result.
 */
@Component({
  selector: 'app-worker-card',
  imports: [Badge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button type="button" class="card" [class.is-selected]="selected()" [attr.aria-pressed]="selected()" [attr.data-testid]="'worker-' + card().rank"
      [attr.data-phase]="presentation().phase" (click)="select.emit(card().rank)">
      <span class="top">
        <strong class="name">{{ card().latest.name }}</strong>
        <ui-badge [label]="presentation().label" [tone]="presentation().tone" />
      </span>
      <span class="agent text-tertiary">
        {{ card().latest.role === 'COORDINATOR' ? 'Coordinator' : 'Worker' }}@if (card().latest.agentName) { · {{ card().latest.agentName }} }
      </span>
      <span class="meta text-tertiary">
        @if (duration() !== null) { <span>{{ formatDuration(duration()) }}</span> }
        @if (tokens() > 0) { <span>{{ formatTokens(tokens()) }} tokens</span> }
        @if (card().attempts.length > 1) { <span data-testid="attempts">Attempt {{ card().latest.attempt }} of {{ card().attempts.length }}</span> }
      </span>
      @if (card().latest.status === 'FAILED') {
        <span class="failure" data-testid="failure">
          {{ failureLabel() }}@if (card().latest.errorCode) { <span class="mono"> · {{ card().latest.errorCode }}</span> }
        </span>
      }
    </button>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .card {
      display: flex; flex-direction: column; gap: var(--space-1); width: 100%; text-align: left;
      padding: var(--space-3); background: var(--color-surface-1); border: 1px solid var(--color-border);
      border-radius: var(--radius-md); cursor: pointer; font: inherit; color: inherit;
    }
    .card:hover { border-color: var(--color-border-strong, var(--color-border)); }
    .card:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 2px; }
    .card.is-selected { border-color: var(--color-accent); box-shadow: 0 0 0 1px var(--color-accent); }
    .card[data-phase='queued'] { border-style: dashed; }
    .top { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); }
    .name { overflow-wrap: anywhere; }
    .meta { display: flex; gap: var(--space-3); flex-wrap: wrap; font-size: var(--text-xs); }
    .failure { color: var(--color-danger); font-size: var(--text-xs); }
  `,
})
export class WorkerCardView {
  readonly card = input.required<WorkerCard>();
  readonly live = input<ReadonlyMap<string, ExecutionStatus>>(new Map());
  readonly usage = input<readonly AttemptUsage[]>([]);
  readonly selected = input(false);
  readonly select = output<number>();

  protected readonly formatDuration = formatDuration;
  protected readonly formatTokens = formatTokens;
  protected readonly presentation = computed(() => {
    const step = this.card().latest;
    return presentStep(step, step.executionId ? (this.live().get(step.executionId) ?? null) : null);
  });
  protected readonly duration = computed(() => stepDurationMs(this.card().latest));
  /** Tokens of every attempt of this rank: a failed attempt that consumed tokens still counts. */
  protected readonly tokens = computed(() =>
    this.usage().filter((u) => u.rank === this.card().rank).reduce((sum, u) => sum + u.inputTokens + u.outputTokens, 0),
  );
  protected readonly failureLabel = computed(() => {
    const category = this.card().latest.failureCategory;
    return category ? FAILURE_LABEL[category] : 'Failed';
  });
}
