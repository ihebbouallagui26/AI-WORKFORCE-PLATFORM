import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiError } from '../../core/identity/backend-client';
import { ConfirmService } from '../../core/services/confirm.service';
import { ToastService } from '../../core/services/toast.service';
import { RunMonitor } from '../../core/workflows/run-monitor';
import {
  budgetOf,
  buildTimeline,
  cancelImpact,
  formatDuration,
  formatTokens,
  layoutRun,
  presentStep,
  retryAvailability,
  RUN_STATUS_LABEL,
  runAnalytics,
} from '../../core/workflows/workflow-view.model';
import { WorkflowsApi } from '../../core/workflows/workflows.api';
import { WorkflowsStore } from '../../core/workflows/workflows.store';
import { isTerminalRun } from '../../core/workflows/workflows.models';
import { Badge } from '../../shared/components/badge/badge';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Panel } from '../../shared/components/panel/panel';
import { ProgressBar } from '../../shared/components/progress-bar/progress-bar';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { ApprovalCard } from '../ai-agents/approval-card';
import { POLICY_TEXT, safeText } from './workflow-labels';
import { RunAnalyticsPanel } from './run-analytics-panel';
import { RunGraph } from './run-graph';
import { RunTimelineView } from './run-timeline-view';
import { WorkerDetail } from './worker-detail';

/**
 * Live view of ONE workflow run: the stages and workers (parallel groups as side-by-side cards between a fan-out and a fan-in), the
 * budget, why the run is waiting, the approvals it needs, per-worker detail and the chronological timeline. It only READS (the Core API
 * is the authority): the two actions it offers, cancelling and retrying one eligible failed step, are requests the Core API may refuse.
 */
@Component({
  selector: 'app-workflow-run-page',
  imports: [RouterLink, Panel, Badge, EmptyState, Skeleton, ProgressBar, RunGraph, WorkerDetail, RunTimelineView, RunAnalyticsPanel, ApprovalCard],
  providers: [RunMonitor],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <a class="back text-tertiary" routerLink="/workflows">← Workflows</a>
      @if (!store.can('workflow.read')) {
        <ui-empty-state icon="lock" heading="Not available" description="Your role does not allow viewing workflows." />
      } @else if (!monitor.run()) {
        @if (monitor.error(); as message) {
          <ui-empty-state icon="circle-alert" tone="danger" heading="Run unavailable" [description]="message" data-testid="run-error" />
        } @else {
          <ui-skeleton [rows]="5" />
        }
      } @else {
        @let run = monitor.run()!;
        <header class="head">
          <div class="titles">
            <h1 class="title" data-testid="objective">{{ objective() }}</h1>
            <p class="text-tertiary">
              {{ run.mode === 'COORDINATED' ? 'Coordinated workflow' : 'Pipeline' }} · launched {{ run.createdAt }}
            </p>
          </div>
          <div class="actions">
            <ui-badge [label]="status().label" [tone]="status().tone" data-testid="run-status" />
            <button type="button" class="btn btn--ghost btn--sm" data-testid="refresh" [disabled]="monitor.loading()" (click)="monitor.refresh()">Refresh</button>
            @if (store.can('workflow.execute')) {
              <button type="button" class="btn btn--sm" data-testid="cancel-run" [disabled]="!canCancel() || busy()" (click)="cancel()">Cancel run</button>
            }
          </div>
        </header>

        <p class="live text-tertiary" role="status" aria-live="polite" data-testid="live-state">
          @if (monitor.error(); as message) {
            Could not refresh: {{ message }}. Showing the last data; retrying.
          } @else if (monitor.paused()) {
            Auto-refresh paused after a while. Use Refresh to continue.
          } @else if (monitor.terminal()) {
            This run has ended: no further refresh.
          } @else if (monitor.polling()) {
            Refreshing automatically about every few seconds.
          }
        </p>

        <section class="why" [attr.data-state]="run.status" data-testid="why-waiting">
          @for (line of waitingLines(); track line) { <p>{{ line }}</p> }
        </section>

        <div class="facts">
          <ui-panel heading="Timing">
            <dl class="dl">
              <div><dt>Started</dt><dd>{{ run.startedAt ?? 'Not started' }}</dd></div>
              <div><dt>Duration</dt><dd data-testid="duration">{{ duration() }}</dd></div>
              <div><dt>Deadline</dt><dd>{{ run.deadlineAt }}</dd></div>
              <div><dt>Failure policy</dt><dd>{{ policyText() }}</dd></div>
              <div><dt>Parallel workers</dt><dd>up to {{ run.maxParallelWorkers }}@if (monitor.summary(); as s) { · peak {{ s.peakConcurrency }} }</dd></div>
            </dl>
          </ui-panel>
          <ui-panel heading="Token budget" subheading="Tokens, not money">
            <ui-progress-bar [value]="budget().utilization * 100" [tone]="budget().overrun > 0 ? 'danger' : budget().utilization > 0.8 ? 'warning' : 'accent'" label="Token budget used" />
            <dl class="dl" data-testid="budget">
              <div><dt>Used</dt><dd>{{ tokens(budget().used) }} of {{ tokens(budget().budget) }}</dd></div>
              <div><dt>Reserved by running workers</dt><dd>{{ budget().reserved === null ? 'Not available' : tokens(budget().reserved!) }}</dd></div>
              <div><dt>Remaining</dt><dd>{{ tokens(budget().remaining) }}</dd></div>
              @if (budget().overrun > 0) { <div><dt>Over budget</dt><dd class="danger" data-testid="overrun">{{ tokens(budget().overrun) }} tokens beyond the budget (actual usage exceeded the reservation)</dd></div> }
            </dl>
          </ui-panel>
        </div>

        @if (unknownOutcome()) {
          <section class="banner banner--danger" role="alert" data-testid="unknown-outcome">
            <strong>Unresolved external outcome.</strong> An external action of at least one worker may have taken effect and its result is lost.
            It is not retried, replayed or reported as cancelled. Check the external system before taking any other step.
          </section>
        }

        <ui-panel heading="Execution" subheading="Stages left to right. Workers of a parallel group run at the same time.">
          @if (!columns().length) {
            <p class="text-tertiary" data-testid="no-steps">No step has been created yet.</p>
          } @else {
            <app-run-graph [columns]="columns()" [live]="monitor.live()" [usage]="usage()" [selectedRank]="selectedRank()" (selectWorker)="selectedRank.set($event)" />
          }
        </ui-panel>

        @if (selectedCard(); as card) {
          <app-worker-detail [card]="card" [usage]="usage()" [messages]="monitor.messages()" [actions]="monitor.actions()" [live]="monitor.live()"
            [approvals]="approvalsOf(card.latest.executionId)" [retry]="retry()" [busy]="busy()" (close)="selectedRank.set(null)"
            (decided)="monitor.refresh()" (retryRequested)="retryStep($event)" />
        }

        @if (monitor.pendingApprovals().length) {
          <ui-panel heading="Waiting for your decision" subheading="Each approval belongs to one worker's tool call and authorizes nothing else.">
            @for (approval of monitor.pendingApprovals(); track approval.id) {
              <app-approval-card [approval]="approval" (decided)="monitor.refresh()" />
            }
          </ui-panel>
        } @else if (waitingForApproval() && !store.access.can('approval.read')) {
          <p class="text-tertiary" data-testid="approval-permission">Some workers wait for approval, but your role cannot read approvals. Ask someone who can review them.</p>
        }

        <ui-panel heading="Recovery" subheading="What can be done about a failure">
          <p class="text-tertiary" data-testid="recovery-text">{{ retry().reason }}</p>
        </ui-panel>

        <ui-panel heading="Results" subheading="Each worker's own output, kept separate. Agent output is untrusted text.">
          @if (run.result) {
            <h3 class="h">Workflow result</h3>
            <pre class="result" data-testid="final-result">{{ result() }}</pre>
          }
          <ul class="plain" data-testid="results">
            @for (r of workerResults(); track r.rank) {
              <li>
                <strong>{{ r.name }}</strong> <span class="text-tertiary">· {{ r.agent }}</span>
                <ui-badge [label]="r.label" [tone]="r.tone" />
                @if (r.text) { <p class="text">{{ r.text }}</p> }
              </li>
            }
          </ul>
        </ui-panel>

        @if (monitor.actions().length) {
          <ui-panel heading="Coordinator actions" subheading="Every delegation the coordinator attempted, accepted or refused by the Core API.">
            <ul class="plain" data-testid="actions">
              @for (a of monitor.actions(); track a.id) {
                <li>
                  <ui-badge [label]="a.status === 'ACCEPTED' ? 'Accepted' : 'Refused'" [tone]="a.status === 'ACCEPTED' ? 'success' : 'danger'" />
                  {{ a.action === 'workflow.delegate' ? 'Delegate to ' + (a.participantRef ?? 'a worker') : 'Finish' }}
                  @if (a.status === 'REJECTED') { <span class="text-tertiary"> · {{ a.code }}: {{ a.message }}</span> }
                </li>
              }
            </ul>
          </ui-panel>
        }

        @if (monitor.summary(); as s) { <app-run-analytics [analytics]="analytics()!" [summary]="s" /> }

        <ui-panel heading="Timeline">
          <app-run-timeline [events]="timeline()" />
        </ui-panel>
      }
    </div>
  `,
  styles: `
    .page { display: flex; flex-direction: column; gap: var(--space-4); }
    .back { text-decoration: none; }
    .head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--space-4); flex-wrap: wrap; }
    .title { font-size: var(--text-xl); margin: 0; overflow-wrap: anywhere; }
    .actions { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; }
    .live { font-size: var(--text-xs); margin: 0; min-height: 1.2em; }
    .why p { margin: 0 0 var(--space-1); }
    .why { padding: var(--space-3); border-radius: var(--radius-md); background: var(--color-surface-2); }
    .facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: var(--space-4); }
    .dl { display: grid; gap: var(--space-2); margin: var(--space-2) 0 0; }
    .dl dt { color: var(--color-text-tertiary); font-size: var(--text-xs); }
    .dl dd { margin: 0; overflow-wrap: anywhere; }
    .danger { color: var(--color-danger); }
    .banner { padding: var(--space-3); border-radius: var(--radius-md); }
    .banner--danger { border: 1px solid var(--color-danger); background: var(--color-danger-subtle, var(--color-surface-2)); }
    .h { font-size: var(--text-sm); margin: 0 0 var(--space-1); }
    .result { white-space: pre-wrap; overflow-wrap: anywhere; margin: 0 0 var(--space-3); padding: var(--space-3); background: var(--color-surface-2); border-radius: var(--radius-md); font: inherit; max-height: 280px; overflow: auto; }
    .text { margin: var(--space-1) 0 0; white-space: pre-wrap; overflow-wrap: anywhere; }
    .plain { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--space-3); }
  `,
})
export class WorkflowRunPage {
  /** The route parameter `:runId` (component input binding). */
  readonly runId = input.required<string>();

  protected readonly store = inject(WorkflowsStore);
  protected readonly monitor = inject(RunMonitor);
  private readonly api = inject(WorkflowsApi);
  private readonly confirm = inject(ConfirmService);
  private readonly toast = inject(ToastService);

  protected readonly selectedRank = signal<number | null>(null);
  protected readonly busy = signal(false);

  protected readonly status = computed(() => RUN_STATUS_LABEL[this.monitor.run()?.status ?? 'PENDING']);
  protected readonly columns = computed(() => layoutRun(this.monitor.steps(), this.monitor.run()?.mode ?? 'PIPELINE'));
  protected readonly usage = computed(() => this.monitor.summary()?.attemptList ?? []);
  protected readonly budget = computed(() => budgetOf(this.monitor.run()!, this.monitor.summary()));
  protected readonly objective = computed(() => safeText(this.monitor.run()?.objective ?? '', 300));
  protected readonly result = computed(() => safeText(this.monitor.run()?.result ?? '', 6000));
  protected readonly policyText = computed(() => POLICY_TEXT[this.monitor.summary()?.failurePolicy ?? 'FAIL_FAST']);
  protected readonly canCancel = computed(() => {
    const run = this.monitor.run();
    return run !== null && !isTerminalRun(run.status) && run.status !== 'CANCEL_REQUESTED';
  });
  protected readonly selectedCard = computed(() => {
    const rank = this.selectedRank();
    return rank === null ? null : (this.columns().flatMap((c) => c.workers).find((w) => w.rank === rank) ?? null);
  });
  protected readonly retry = computed(() =>
    retryAvailability(this.monitor.run()!, this.monitor.steps(), this.monitor.summary(), (p) => this.store.can(p)),
  );
  protected readonly analytics = computed(() => {
    const summary = this.monitor.summary();
    return summary ? runAnalytics(summary, this.monitor.steps(), this.monitor.approvals()) : null;
  });
  protected readonly timeline = computed(() =>
    this.monitor.run() ? buildTimeline(this.monitor.run()!, this.monitor.steps(), this.monitor.actions(), this.monitor.messages(), this.monitor.approvals()) : [],
  );
  protected readonly unknownOutcome = computed(() => this.monitor.steps().some((s) => s.failureCategory === 'OUTCOME_UNKNOWN'));
  protected readonly waitingForApproval = computed(() => this.monitor.steps().some((s) => s.status === 'WAITING_APPROVAL'));
  protected readonly duration = computed(() => {
    this.monitor.lastUpdated();
    const run = this.monitor.run();
    if (!run) return '—';
    const from = Date.parse(run.startedAt ?? run.createdAt);
    const to = run.completedAt ? Date.parse(run.completedAt) : Date.now();
    return formatDuration(Math.max(0, to - from));
  });

  /** Why the run is where it is, from the recorded states only. */
  protected readonly waitingLines = computed<readonly string[]>(() => {
    const run = this.monitor.run();
    if (!run) return [];
    const steps = this.columns().flatMap((c) => c.workers.map((w) => w.latest));
    const count = (pred: (s: (typeof steps)[number]) => boolean) => steps.filter(pred).length;
    const lines: string[] = [];
    switch (run.status) {
      case 'SUCCEEDED': return ['The workflow completed.'];
      case 'FAILED': return [`The workflow failed${run.errorCode ? ` (${run.errorCode})` : ''}. Results of completed workers are kept below.`];
      case 'TIMEOUT': return ['The workflow reached its total time limit.'];
      case 'CANCELLED': return ['The workflow was cancelled. Actions that already took effect are not undone.'];
      case 'CANCEL_REQUESTED': return ['Cancellation requested: nothing new starts; active workers are being stopped.'];
      case 'WAITING_RECOVERY': return ['A failed step is held for a person: it can be retried when it is safe (see Recovery).'];
      default: break;
    }
    const queued = count((s) => s.status === 'QUEUED');
    const waiting = count((s) => s.status === 'WAITING_APPROVAL');
    const dispatched = count((s) => s.status === 'RUNNING' && presentStep(s, s.executionId ? (this.monitor.live().get(s.executionId) ?? null) : null).phase === 'dispatched');
    const running = count((s) => s.status === 'RUNNING') - dispatched;
    const retries = steps.filter((s) => s.status === 'FAILED' && s.nextAttemptAt);
    if (waiting) lines.push(`${waiting} worker(s) wait for a person to approve or reject a tool action.`);
    if (running) lines.push(`${running} worker(s) running.`);
    if (dispatched) lines.push(`${dispatched} worker(s) dispatched, not confirmed running yet.`);
    if (queued) lines.push(`${queued} worker(s) waiting for capacity (execution slots or token budget).`);
    for (const r of retries) lines.push(`Step ${r.rank} will be retried at ${r.nextAttemptAt}.`);
    return lines.length ? lines : ['Waiting for the next scheduling pass.'];
  });

  /** Each worker's latest outcome, shown separately from the group (never a failed attempt as a result). */
  protected readonly workerResults = computed(() =>
    this.columns().flatMap((c) => c.workers).filter((w) => w.latest.role === 'WORKER').map((w) => {
      const p = presentStep(w.latest);
      return {
        rank: w.rank, name: w.latest.name, agent: w.latest.agentName ?? 'Unknown agent', label: p.label, tone: p.tone,
        text: w.latest.status === 'SUCCEEDED' && w.latest.resultSummary ? safeText(w.latest.resultSummary, 600) : null,
      };
    }),
  );

  constructor() {
    effect(() => {
      // All three are read in the TRACKED part: at a cold start (reload, direct URL) the organization and its permissions arrive
      // asynchronously, and the effect must run again when they do. Only the side effects below are untracked.
      const id = this.runId();
      const organizationId = this.store.organizationId();
      const allowed = this.store.can('workflow.read');
      untracked(() => {
        this.selectedRank.set(null);
        if (organizationId && allowed) {
          this.monitor.start(id);
        } else {
          this.monitor.release(); // no organization yet, or no workflow.read: nothing is followed and nothing of another run stays shown
        }
      });
    });
  }

  protected tokens(n: number): string {
    return formatTokens(n);
  }

  protected approvalsOf(executionId: string | null) {
    return executionId ? this.monitor.pendingApprovals().filter((a) => a.executionId === executionId) : [];
  }

  protected async cancel(): Promise<void> {
    const run = this.monitor.run();
    if (!run) return;
    const impact = cancelImpact(this.monitor.steps());
    const result = await this.confirm.ask({
      title: 'Cancel this workflow?',
      message: impact.lines.join(' '),
      confirmLabel: 'Cancel workflow',
      cancelLabel: 'Keep running',
      tone: 'danger',
    });
    if (!result.confirmed) return;
    await this.request(() => this.store.mutate((org) => this.api.cancelRun(org, run.id)), 'Cancellation requested', 'The workflow could not be cancelled.');
  }

  protected async retryStep(rank: number): Promise<void> {
    const run = this.monitor.run();
    if (!run || this.retry().state !== 'available') return;
    const result = await this.confirm.ask({
      title: `Retry step ${rank}?`,
      message: 'A new attempt starts as a new execution; the failed attempt stays in the history. The Core API checks again that this is safe and that you and the agent are still authorized.',
      confirmLabel: 'Retry step',
      tone: 'primary',
    });
    if (!result.confirmed) return;
    await this.request(() => this.store.mutate((org) => this.api.retryStep(org, run.id, rank)), 'Retry requested', 'The step could not be retried.');
  }

  private async request(action: () => Promise<unknown>, done: string, failed: string): Promise<void> {
    this.busy.set(true);
    try {
      await action();
      this.toast.success(done, null);
    } catch (e) {
      // The Core API decides: a 409 means the state changed or the action is not allowed; the page then shows the real state.
      const detail = e instanceof ApiError ? (e.status === 409 ? (e.problem?.detail ?? e.message) : e.message) : null;
      this.toast.error(failed, detail ? safeText(detail, 200) : null);
    } finally {
      this.busy.set(false);
      await this.monitor.refresh();
    }
  }
}
