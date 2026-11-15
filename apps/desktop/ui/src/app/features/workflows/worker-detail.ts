import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { ExecutionStatus, ToolApproval } from '../../core/agents/agents.models';
import {
  FAILURE_LABEL,
  formatDuration,
  formatTokens,
  presentStep,
  RetryAvailability,
  stepDurationMs,
  WorkerCard,
} from '../../core/workflows/workflow-view.model';
import { AttemptUsage, LaunchReason, WorkflowAction, WorkflowMessage } from '../../core/workflows/workflows.models';
import { Badge } from '../../shared/components/badge/badge';
import { Panel } from '../../shared/components/panel/panel';
import { ApprovalCard } from '../ai-agents/approval-card';
import { safeText } from './workflow-labels';

export const LAUNCH_LABEL: Readonly<Record<LaunchReason, string>> = { INITIAL: 'Initial attempt', AUTO_RETRY: 'Automatic retry', MANUAL_RETRY: 'Manual retry' };
const MAX_TEXT = 4000;

/**
 * Everything the API records about ONE worker: its task, every attempt (a retry is a NEW attempt, earlier ones stay), the failure
 * classification, the safe result summary and any approval waiting for a person. Agent output is untrusted text: it is shown as plain
 * text, masked and bounded, never interpreted. No credential or raw tool argument is ever available here.
 */
@Component({
  selector: 'app-worker-detail',
  imports: [Panel, Badge, ApprovalCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-panel [heading]="card().latest.name" [subheading]="subtitle()">
      <button panelActions type="button" class="btn btn--ghost btn--sm" (click)="close.emit()">Close</button>
      <div class="status">
        <ui-badge [label]="presentation().label" [tone]="presentation().tone" />
        <span class="text-tertiary" data-testid="status-explanation">{{ presentation().explanation }}</span>
      </div>

      <dl class="facts">
        <div><dt>Agent</dt><dd>{{ card().latest.agentName ?? 'Unknown agent' }} ({{ card().latest.role === 'COORDINATOR' ? 'coordinator' : 'worker' }})</dd></div>
        @if (card().latest.groupNo !== null) { <div><dt>Parallel group</dt><dd>{{ card().latest.groupNo }}</dd></div> }
        <div><dt>Started</dt><dd>{{ card().latest.startedAt ?? 'Not started yet' }}</dd></div>
        <div><dt>Ended</dt><dd>{{ card().latest.completedAt ?? '—' }}</dd></div>
        <div><dt>Duration</dt><dd>{{ duration() }}</dd></div>
        @if (card().latest.deadlineAt) { <div><dt>Step time limit</dt><dd>{{ card().latest.deadlineAt }}</dd></div> }
      </dl>

      @if (task(); as text) {
        <h3 class="h">Assigned task</h3>
        <p class="text" data-testid="task">{{ text }}</p>
      }

      <h3 class="h">Attempts</h3>
      <table class="table" data-testid="attempts-table">
        <thead><tr><th>#</th><th>How it started</th><th>Status</th><th>Duration</th><th>Tokens</th><th>Failure</th></tr></thead>
        <tbody>
          @for (a of card().attempts; track a.id) {
            <tr [attr.data-testid]="'attempt-' + a.attempt">
              <td>{{ a.attempt }}</td>
              <td>{{ launchLabel(a.launchReason) }}</td>
              <td>{{ statusLabel(a) }}</td>
              <td>{{ formatDuration(attemptDuration(a)) }}</td>
              <td>{{ attemptTokens(a.attempt) }}</td>
              <td>{{ a.failureCategory ? failureLabel(a.failureCategory) : '—' }}@if (a.errorCode) { <span class="mono"> · {{ a.errorCode }}</span> }</td>
            </tr>
          }
        </tbody>
      </table>

      @if (card().latest.status === 'FAILED') {
        <section class="failure" data-testid="failure-detail">
          <p><strong>{{ card().latest.failureCategory ? failureLabel(card().latest.failureCategory!) : 'Failed' }}</strong></p>
          @if (errorText(); as message) { <p class="text-tertiary">{{ message }}</p> }
          <p class="text-tertiary" data-testid="retry-safety">{{ safety() }}</p>
        </section>
      }

      @if (card().latest.status === 'SUCCEEDED' && summary(); as text) {
        <h3 class="h">Result</h3>
        <p class="text-tertiary note">Produced by this worker. It is text written by an AI agent: treat it as untrusted information.</p>
        <pre class="result" data-testid="result">{{ text }}</pre>
      } @else if (card().latest.status !== 'SUCCEEDED' && card().attempts.some(isSucceeded)) {
        <p class="text-tertiary">An earlier attempt produced a result; the current one has not.</p>
      } @else if (card().latest.status !== 'SUCCEEDED') {
        <p class="text-tertiary" data-testid="no-result">No result: this worker has not completed successfully.</p>
      }

      @if (approvals().length) {
        <h3 class="h">Waiting for a decision</h3>
        @for (approval of approvals(); track approval.id) {
          <app-approval-card [approval]="approval" (decided)="decided.emit()" />
        }
      }

      @if (card().latest.status === 'FAILED' || card().latest.status === 'CANCELLED') {
        <section class="recovery" data-testid="recovery">
          @if (retry().state === 'available' && retryRank() === card().rank) {
            <button type="button" class="btn btn--primary btn--sm" data-testid="retry-button" [disabled]="busy()" (click)="retryRequested.emit(card().rank)">
              {{ busy() ? 'Requesting…' : 'Retry this step' }}
            </button>
            <p class="text-tertiary">{{ retry().reason }}</p>
          } @else {
            <p class="text-tertiary" data-testid="retry-unavailable">{{ retry().reason }}</p>
          }
        </section>
      }
    </ui-panel>
  `,
  styles: `
    :host { display: block; }
    .status { display: flex; gap: var(--space-2); align-items: center; flex-wrap: wrap; margin-bottom: var(--space-3); }
    .facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: var(--space-2) var(--space-4); margin: 0 0 var(--space-3); }
    .facts dt { color: var(--color-text-tertiary); font-size: var(--text-xs); }
    .facts dd { margin: 0; overflow-wrap: anywhere; }
    .h { font-size: var(--text-sm); margin: var(--space-3) 0 var(--space-1); }
    .text { margin: 0; overflow-wrap: anywhere; white-space: pre-wrap; }
    .result { white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; padding: var(--space-3); background: var(--color-surface-2); border-radius: var(--radius-md); font: inherit; max-height: 280px; overflow: auto; }
    .failure { border-left: 3px solid var(--color-danger); padding-left: var(--space-3); margin-top: var(--space-3); }
    .recovery { margin-top: var(--space-3); }
    .note { font-size: var(--text-xs); }
  `,
})
export class WorkerDetail {
  readonly card = input.required<WorkerCard>();
  readonly usage = input<readonly AttemptUsage[]>([]);
  readonly messages = input<readonly WorkflowMessage[]>([]);
  readonly actions = input<readonly WorkflowAction[]>([]);
  readonly approvals = input<readonly ToolApproval[]>([]);
  readonly live = input<ReadonlyMap<string, ExecutionStatus>>(new Map());
  readonly retry = input.required<RetryAvailability>();
  readonly busy = input(false);
  readonly close = output<void>();
  readonly decided = output<void>();
  readonly retryRequested = output<number>();

  protected readonly formatDuration = formatDuration;
  protected readonly isSucceeded = (s: { status: string }) => s.status === 'SUCCEEDED';
  protected readonly presentation = computed(() => {
    const step = this.card().latest;
    return presentStep(step, step.executionId ? (this.live().get(step.executionId) ?? null) : null);
  });
  protected readonly subtitle = computed(() => `Step ${this.card().rank}${this.card().attempts.length > 1 ? ` · ${this.card().attempts.length} attempts` : ''}`);
  protected readonly duration = computed(() => formatDuration(stepDurationMs(this.card().latest)));
  protected readonly retryRank = computed(() => {
    const r = this.retry();
    return r.state === 'available' ? r.rank : null;
  });
  /** The task the worker was given: the coordinator's delegation, or the journal's assignment note (a pipeline). */
  protected readonly task = computed(() => {
    const step = this.card().attempts[0];
    const delegation = this.actions().find((a) => a.resultStepId === step.id && a.status === 'ACCEPTED');
    const text = delegation?.task ?? this.messages().find((m) => m.kind === 'ASSIGNMENT' && m.stepRank === this.card().rank)?.body ?? null;
    return text ? safeText(text, MAX_TEXT) : null;
  });
  protected readonly summary = computed(() => {
    const text = this.card().latest.resultSummary;
    return text ? safeText(text, MAX_TEXT) : null;
  });
  protected readonly errorText = computed(() => {
    const text = this.card().latest.errorMessage;
    return text ? safeText(text, 400) : null;
  });
  protected readonly safety = computed(() => {
    const step = this.card().latest;
    if (step.failureCategory === 'OUTCOME_UNKNOWN') return 'An external action may have taken effect and its result is unknown: it is never retried.';
    if (step.retrySafe === true) return 'No external action started during this attempt, so a repeat is safe.';
    if (step.retrySafe === false) return 'An external action had started, so this attempt is not retried automatically.';
    return 'Retry safety was not recorded for this attempt.';
  });

  protected launchLabel(reason: LaunchReason): string {
    return LAUNCH_LABEL[reason];
  }

  protected statusLabel(step: { status: string }): string {
    return step.status.replace('_', ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase());
  }

  protected failureLabel(category: keyof typeof FAILURE_LABEL): string {
    return FAILURE_LABEL[category];
  }

  protected attemptDuration(step: { startedAt: string | null; completedAt: string | null; status: string }): number | null {
    return stepDurationMs(step as never);
  }

  protected attemptTokens(attempt: number): string {
    const u = this.usage().find((x) => x.rank === this.card().rank && x.attempt === attempt);
    return u ? formatTokens(u.inputTokens + u.outputTokens) : '—';
  }
}
