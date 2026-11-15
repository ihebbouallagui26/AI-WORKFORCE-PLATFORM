import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AUTH_REQUIRED } from '../../core/auth/session.service';
import { formatDuration, formatTokens, Metric, overviewAnalytics, RUN_STATUS_LABEL } from '../../core/workflows/workflow-view.model';
import { WorkflowMode } from '../../core/workflows/workflows.models';
import { WorkflowsStore } from '../../core/workflows/workflows.store';
import { Badge } from '../../shared/components/badge/badge';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { Panel } from '../../shared/components/panel/panel';
import { SearchInput } from '../../shared/components/search-input/search-input';
import { SegmentOption, Segmented } from '../../shared/components/segmented/segmented';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { StatCard } from '../../shared/components/stat-card/stat-card';
import { TabItem, Tabs } from '../../shared/components/tabs/tabs';
import { ApprovalsTab } from '../ai-agents/approvals-tab';
import { MODE_TEXT, safeText } from './workflow-labels';

type ModeFilter = '' | WorkflowMode;
type RunFilter = '' | 'active' | 'completed' | 'failed' | 'waiting-approval' | 'waiting-recovery';

const MODE_OPTIONS: readonly SegmentOption<ModeFilter>[] = [
  { value: '', label: 'All' },
  { value: 'PIPELINE', label: 'Pipeline' },
  { value: 'COORDINATED', label: 'Coordinated' },
];
const RUN_FILTERS: readonly { readonly value: RunFilter; readonly label: string }[] = [
  { value: '', label: 'All' },
  { value: 'active', label: 'Active' },
  { value: 'completed', label: 'Completed' },
  { value: 'failed', label: 'Failed' },
  { value: 'waiting-approval', label: 'Waiting for approval' },
  { value: 'waiting-recovery', label: 'Waiting for recovery' },
];

/**
 * Workflow management center: an overview with numbers computed from the loaded runs, the workflow definitions, the runs (execution
 * history) and the approvals. Everything shown comes from the Core API; a figure with no recorded basis is shown as unavailable.
 */
@Component({
  selector: 'app-workflows-page',
  imports: [RouterLink, PageHeader, Tabs, Panel, StatCard, Badge, EmptyState, Skeleton, SearchInput, Segmented, ApprovalsTab],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <ui-page-header heading="Workflows"
        description="Multi-agent workflows: define them, launch runs and follow what each agent does. Agents only act within their own permissions, and sensitive tool actions wait for a person's approval.">
        @if (store.can('workflow.manage') && authRequired) {
          <a class="btn btn--primary btn--sm" routerLink="/workflows/new" data-testid="new-workflow">New workflow</a>
        }
      </ui-page-header>

      @if (!authRequired) {
        <ui-empty-state icon="workflow" heading="Workflows need the Core API" description="Workflows run on the Core API. Sign in with a connected backend to use them." data-testid="mock-mode" />
      } @else if (!store.organizationId()) {
        <ui-empty-state icon="building" heading="No organization selected" description="Select or create an organization first." />
      } @else if (!store.can('workflow.read')) {
        <ui-empty-state icon="lock" heading="Not available" description="Your role does not allow viewing workflows." data-testid="no-permission" />
      } @else {
        <ui-tabs [items]="items()" [(active)]="active" label="Workflow sections" />
        @if (store.error(); as message) {
          <ui-empty-state icon="circle-alert" tone="danger" heading="Workflows unavailable" [description]="message" data-testid="load-error" />
        } @else if (store.loading() && !store.definitions().length && !store.runs().length) {
          <ui-skeleton [rows]="4" />
        } @else {
          @switch (active()) {
            @case ('overview') {
              <section class="stats" data-testid="overview">
                <ui-stat-card label="Workflow definitions" [value]="store.totalDefinitions()" icon="workflow" />
                <ui-stat-card label="Active runs" [value]="analytics().active" icon="activity" tone="accent" />
                <ui-stat-card label="Completed" [value]="analytics().succeeded" icon="circle-check" tone="success" />
                <ui-stat-card label="Failed" [value]="analytics().failed" icon="circle-x" tone="danger" />
                <ui-stat-card label="Waiting for approval" [value]="analytics().waitingApproval" icon="shield-alert" tone="warning" />
                <ui-stat-card label="Waiting for recovery" [value]="analytics().waitingRecovery" icon="warning" tone="warning" />
                <ui-stat-card label="Runs with parallel workers" [value]="analytics().parallel" icon="cpu" tone="ai" />
              </section>
              <ui-panel heading="Run statistics" [subheading]="'Computed from the ' + analytics().total + ' most recent run(s) loaded. Tokens are not money.'">
                <dl class="dl" data-testid="statistics">
                  <div><dt>Success rate</dt><dd>{{ rate(analytics().successRate) }}</dd></div>
                  <div><dt>Average duration</dt><dd>{{ duration(analytics().averageDurationMs) }}</dd></div>
                  <div><dt>Tokens used</dt><dd>{{ tokens(analytics().tokensUsed) }}</dd></div>
                  <div><dt>Budget utilization</dt><dd>{{ rate(analytics().budgetUtilization) }}</dd></div>
                </dl>
              </ui-panel>
              <ui-panel heading="Needs attention" subheading="Runs that wait for a person">
                @if (attention().length) {
                  <ul class="plain" data-testid="attention">
                    @for (run of attention(); track run.id) {
                      <li><a [routerLink]="['/workflows/runs', run.id]">{{ shorten(run.objective) }}</a> <ui-badge [label]="runStatus[run.status].label" [tone]="runStatus[run.status].tone" /></li>
                    }
                  </ul>
                } @else { <p class="text-tertiary" data-testid="attention-empty">Nothing is waiting for you.</p> }
              </ui-panel>
              <ui-panel heading="Recent activity">
                @if (store.runs().length) {
                  <ul class="plain" data-testid="recent">
                    @for (run of recent(); track run.id) {
                      <li><a [routerLink]="['/workflows/runs', run.id]">{{ shorten(run.objective) }}</a> <ui-badge [label]="runStatus[run.status].label" [tone]="runStatus[run.status].tone" /> <span class="text-tertiary">{{ run.createdAt }}</span></li>
                    }
                  </ul>
                } @else { <p class="text-tertiary">No run yet.</p> }
              </ui-panel>
            }
            @case ('workflows') {
              <div class="filters">
                <ui-search-input class="search" placeholder="Search workflows" label="Search workflows" [value]="store.search()" (valueChange)="store.search.set($event)" />
                <ui-segmented [options]="modeOptions" [value]="store.modeFilter()" (valueChange)="store.modeFilter.set($event)" label="Filter by mode" />
              </div>
              @if (!store.definitions().length) {
                <ui-empty-state icon="workflow" heading="No workflow yet" description="Create a workflow to let several agents work together." data-testid="no-workflows" />
              } @else if (!store.filteredDefinitions().length) {
                <ui-empty-state icon="search" heading="No match" description="No workflow matches this search or filter." data-testid="no-match" />
              } @else {
                <ul class="cards" data-testid="definitions">
                  @for (d of store.filteredDefinitions(); track d.id) {
                    <li class="card" [attr.data-testid]="'definition-' + d.id">
                      <a class="card__title" [routerLink]="['/workflows', d.id]">{{ d.name }}</a>
                      <p class="text-tertiary">{{ d.description || 'No description.' }}</p>
                      <div class="chips">
                        <ui-badge [label]="modeText[d.mode].label" [tone]="d.mode === 'COORDINATED' ? 'ai' : 'info'" />
                        @if (d.status === 'ARCHIVED') { <ui-badge label="Archived" tone="neutral" /> }
                        @if (d.maxParallelWorkers > 1) { <ui-badge [label]="'Parallel ×' + d.maxParallelWorkers" tone="accent" /> }
                      </div>
                      <dl class="dl">
                        <div><dt>Participants</dt><dd>{{ d.participants.length }}</dd></div>
                        @if (d.mode === 'COORDINATED') { <div><dt>Coordinator</dt><dd>{{ coordinatorOf(d) }}</dd></div> }
                        <div><dt>Last run</dt><dd>
                          @if (store.lastRunByDefinition().get(d.id); as last) { <a [routerLink]="['/workflows/runs', last.id]">{{ runStatus[last.status].label }}</a> } @else { never run }
                        </dd></div>
                        <div><dt>Created</dt><dd>{{ d.createdAt }}</dd></div>
                      </dl>
                    </li>
                  }
                </ul>
              }
            }
            @case ('runs') {
              <div class="filters" role="group" aria-label="Filter runs">
                @for (f of runFilters; track f.label) {
                  <button type="button" class="btn btn--sm" [class.btn--primary]="store.runFilter() === f.value" [class.btn--ghost]="store.runFilter() !== f.value"
                    [attr.aria-pressed]="store.runFilter() === f.value" (click)="store.runFilter.set(f.value)">{{ f.label }}</button>
                }
              </div>
              @if (!store.runs().length) {
                <ui-empty-state icon="activity" heading="No run yet" description="Launch a workflow to see its runs here." data-testid="no-runs" />
              } @else if (!store.filteredRuns().length) {
                <ui-empty-state icon="search" heading="No match" description="No run matches this filter." data-testid="no-run-match" />
              } @else {
                <div class="table-wrap">
                  <table class="table" data-testid="runs-table">
                    <thead><tr><th>Objective</th><th>Workflow</th><th>Mode</th><th>Status</th><th>Tokens</th><th>Launched</th></tr></thead>
                    <tbody>
                      @for (run of store.filteredRuns(); track run.id) {
                        <tr>
                          <td><a [routerLink]="['/workflows/runs', run.id]">{{ shorten(run.objective) }}</a></td>
                          <td>{{ definitionName(run.definitionId) }}</td>
                          <td>{{ modeText[run.mode].label }}{{ run.maxParallelWorkers > 1 ? ' (parallel)' : '' }}</td>
                          <td><ui-badge [label]="runStatus[run.status].label" [tone]="runStatus[run.status].tone" /></td>
                          <td class="tabular">{{ tokens(run.tokensUsed) }} / {{ tokens(run.tokenBudget) }}</td>
                          <td>{{ run.createdAt }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            }
            @case ('approvals') {
              @if (store.can('approval.read')) { <app-approvals-tab /> }
            }
          }
        }
      }
    </div>
  `,
  styles: `
    .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: var(--space-3); margin: var(--space-4) 0; }
    .filters { display: flex; gap: var(--space-3); flex-wrap: wrap; align-items: center; margin: var(--space-3) 0; }
    .search { min-width: 240px; flex: 1; }
    .cards { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: var(--space-3); }
    .card { padding: var(--space-4); background: var(--color-surface-1); border: 1px solid var(--color-border-subtle); border-radius: var(--radius-lg); display: flex; flex-direction: column; gap: var(--space-2); min-width: 0; }
    .card__title { font-weight: var(--weight-semibold); overflow-wrap: anywhere; }
    .chips { display: flex; gap: var(--space-2); flex-wrap: wrap; }
    .dl { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: var(--space-2) var(--space-4); margin: 0; }
    .dl dt { color: var(--color-text-tertiary); font-size: var(--text-xs); }
    .dl dd { margin: 0; overflow-wrap: anywhere; }
    ui-panel { margin-bottom: var(--space-3); }
  `,
})
export class WorkflowsPage {
  protected readonly store = inject(WorkflowsStore);
  protected readonly authRequired = inject(AUTH_REQUIRED);
  protected readonly active = signal<string>('overview');
  protected readonly modeOptions = MODE_OPTIONS;
  protected readonly runFilters = RUN_FILTERS;
  protected readonly modeText = MODE_TEXT;
  protected readonly runStatus = RUN_STATUS_LABEL;

  protected readonly items = computed<readonly TabItem[]>(() => [
    { id: 'overview', label: 'Overview' },
    { id: 'workflows', label: 'Workflows', count: this.store.totalDefinitions() },
    { id: 'runs', label: 'Runs', count: this.store.totalRuns() },
    ...(this.store.can('approval.read') ? [{ id: 'approvals', label: 'Approvals' }] : []),
  ]);
  protected readonly analytics = computed(() => overviewAnalytics(this.store.runs()));
  protected readonly attention = computed(() => this.store.runs().filter((r) => r.status === 'WAITING_APPROVAL' || r.status === 'WAITING_RECOVERY'));
  protected readonly recent = computed(() => this.store.runs().slice(0, 5));

  protected shorten(text: string): string {
    return safeText(text, 90);
  }

  protected tokens(n: number): string {
    return formatTokens(n);
  }

  protected rate(metric: Metric): string {
    return metric.value === null ? 'Not available' : `${Math.round(metric.value * 100)}% (of ${metric.basedOn})`;
  }

  protected duration(metric: Metric): string {
    return metric.value === null ? 'Not available' : `${formatDuration(metric.value)} (of ${metric.basedOn})`;
  }

  protected definitionName(id: string): string {
    return this.store.definitions().find((d) => d.id === id)?.name ?? 'Unknown workflow';
  }

  protected coordinatorOf(d: { participants: readonly { coordinator: boolean; agentName: string | null }[] }): string {
    return d.participants.find((p) => p.coordinator)?.agentName ?? 'not chosen';
  }
}
