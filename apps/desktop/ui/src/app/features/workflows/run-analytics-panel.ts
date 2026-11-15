import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { formatDuration, formatTokens, Metric, RunAnalytics } from '../../core/workflows/workflow-view.model';
import { RunSummary } from '../../core/workflows/workflows.models';
import { Panel } from '../../shared/components/panel/panel';

/**
 * Measured figures of ONE run. A figure with no recorded basis reads "not available" rather than showing an estimate; tokens are never
 * converted to money (there is no reliable price data).
 */
@Component({
  selector: 'app-run-analytics',
  imports: [Panel],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-panel heading="Run metrics" subheading="Measured from the recorded attempts. Tokens are not money.">
      <dl class="grid-dl" data-testid="run-metrics">
        <div><dt>Attempts</dt><dd>{{ analytics().attempts }}</dd></div>
        <div><dt>Retries</dt><dd>{{ analytics().retries }} <span class="text-tertiary">({{ summary().automaticRetries }} automatic, {{ summary().manualRetries }} manual)</span></dd></div>
        <div><dt>Failed attempts</dt><dd>{{ analytics().failedAttempts }}</dd></div>
        <div><dt>Peak concurrency</dt><dd>{{ analytics().peakConcurrency }} <span class="text-tertiary">of {{ summary().maxParallelWorkers }} allowed</span></dd></div>
        <div><dt>Average queue time</dt><dd>{{ duration(analytics().averageQueueMs) }}</dd></div>
        <div><dt>Parallel group duration</dt><dd>{{ duration(analytics().parallelGroupMs) }}</dd></div>
        <div><dt>Approval waiting time</dt><dd>{{ duration(analytics().approvalWaitMs) }}</dd></div>
        <div><dt>Input / output tokens</dt><dd>{{ tokens(summary().inputTokens) }} / {{ tokens(summary().outputTokens) }}</dd></div>
        <div><dt>Coordinator tokens</dt><dd>{{ tokens(summary().coordinatorTokens) }}</dd></div>
        <div><dt>Worker tokens</dt><dd>{{ tokens(summary().workerTokens) }}</dd></div>
      </dl>
      @if (failures().length) {
        <h3 class="h">Failures by category</h3>
        <ul class="plain" data-testid="failures">
          @for (f of failures(); track f.category) { <li>{{ f.label }}: {{ f.count }}</li> }
        </ul>
      }
      <h3 class="h">Work by agent</h3>
      <ul class="plain" data-testid="distribution">
        @for (a of analytics().agentDistribution; track a.agent) { <li>{{ a.agent }}: {{ a.attempts }} attempt(s)</li> }
      </ul>
    </ui-panel>
  `,
  styles: `
    .grid-dl { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: var(--space-2) var(--space-4); margin: 0; }
    .grid-dl dt { color: var(--color-text-tertiary); font-size: var(--text-xs); }
    .grid-dl dd { margin: 0; }
    .h { font-size: var(--text-sm); margin: var(--space-3) 0 var(--space-1); }
  `,
})
export class RunAnalyticsPanel {
  readonly analytics = input.required<RunAnalytics>();
  readonly summary = input.required<RunSummary>();

  protected readonly failures = computed(() =>
    Object.entries(this.summary().failuresByCategory).map(([category, count]) => ({ category, count, label: category.replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase()) })),
  );

  protected duration(metric: Metric): string {
    return metric.value === null ? 'Not available' : `${formatDuration(metric.value)} (${metric.basedOn})`;
  }

  protected tokens(n: number): string {
    return formatTokens(n);
  }
}
