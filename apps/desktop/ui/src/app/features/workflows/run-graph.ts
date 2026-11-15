import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { ExecutionStatus } from '../../core/agents/agents.models';
import { GROUP_STATUS_LABEL, RunColumn } from '../../core/workflows/workflow-view.model';
import { AttemptUsage } from '../../core/workflows/workflows.models';
import { Badge } from '../../shared/components/badge/badge';
import { Icon } from '../../shared/components/icon/icon';
import { WorkerCardView } from './worker-card';

/**
 * The execution as a left-to-right flow: each column is a stage (or a coordinator turn). A column with several workers is a PARALLEL
 * group: its workers sit side by side between a fan-out from the previous column and a fan-in to the next one, and the next column only
 * begins when the whole group is resolved. Rendered from persisted records at each refresh.
 */
@Component({
  selector: 'app-run-graph',
  imports: [WorkerCardView, Badge, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol class="flow" aria-label="Workflow execution stages">
      @for (column of columns(); track column.key; let first = $first) {
        <li class="column" [class.column--parallel]="column.parallel" [attr.data-testid]="'column-' + column.key" [attr.data-parallel]="column.parallel">
          @if (!first) {
            <span class="link" aria-hidden="true">
              <ui-icon name="chevron-right" [size]="16" />
              @if (column.parallel) { <span class="link__text">fan-out</span> }
            </span>
          }
          <section class="stage" [attr.aria-label]="column.label">
            <header class="stage__head">
              <strong>{{ column.label }}</strong>
              @if (column.parallel) {
                <span class="text-tertiary" data-testid="parallel-label">{{ column.workers.length }} workers in parallel</span>
                <ui-badge [label]="groupLabel(column).label" [tone]="groupLabel(column).tone" />
              }
            </header>
            <div class="workers">
              @for (worker of column.workers; track worker.rank) {
                <app-worker-card [card]="worker" [live]="live()" [usage]="usage()" [selected]="selectedRank() === worker.rank" (select)="selectWorker.emit($event)" />
              }
            </div>
          </section>
        </li>
      }
    </ol>
    @if (hasFanIn()) { <p class="text-tertiary note">After a parallel group, the next stage starts only when every worker of the group has a resolved outcome (completed, failed or cancelled).</p> }
  `,
  styles: `
    :host { display: block; }
    .flow { list-style: none; margin: 0; padding: 0; display: flex; align-items: flex-start; gap: var(--space-2); overflow-x: auto; padding-bottom: var(--space-2); }
    .column { display: flex; align-items: flex-start; gap: var(--space-2); min-width: 220px; }
    .column--parallel { min-width: 260px; }
    .link { display: flex; flex-direction: column; align-items: center; color: var(--color-text-tertiary); padding-top: var(--space-6); }
    .link__text { font-size: var(--text-xs); }
    .stage { flex: 1; min-width: 0; padding: var(--space-2); border-radius: var(--radius-lg); }
    .column--parallel .stage { background: var(--color-surface-2); border: 1px dashed var(--color-border); }
    .stage__head { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; margin-bottom: var(--space-2); }
    .workers { display: flex; flex-direction: column; gap: var(--space-2); }
    .note { margin-top: var(--space-2); font-size: var(--text-xs); }
  `,
})
export class RunGraph {
  readonly columns = input.required<readonly RunColumn[]>();
  readonly live = input<ReadonlyMap<string, ExecutionStatus>>(new Map());
  readonly usage = input<readonly AttemptUsage[]>([]);
  readonly selectedRank = input<number | null>(null);
  readonly selectWorker = output<number>();

  protected groupLabel(column: RunColumn) {
    return GROUP_STATUS_LABEL[column.status];
  }

  protected hasFanIn(): boolean {
    return this.columns().some((c, i) => c.parallel && i < this.columns().length - 1);
  }
}
