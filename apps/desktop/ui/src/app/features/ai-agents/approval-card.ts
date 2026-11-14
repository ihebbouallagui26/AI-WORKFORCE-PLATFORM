import { ChangeDetectionStrategy, Component, computed, effect, inject, input, linkedSignal, output, signal, untracked } from '@angular/core';
import { AgentsApi } from '../../core/agents/agents.api';
import { ApprovalStatus, ToolApproval } from '../../core/agents/agents.models';
import { AgentsStore } from '../../core/agents/agents.store';
import { ApiError } from '../../core/identity/backend-client';
import { ToastService } from '../../core/services/toast.service';
import { Avatar } from '../../shared/components/avatar/avatar';
import { Badge } from '../../shared/components/badge/badge';
import { Tone } from '../../shared/utils/presentation';
import { INVOCATION_LABEL, MAX_ARGUMENT_CHARS, bounded, maskSensitive } from './execution-timeline.model';

export const MAX_REASON_LENGTH = 500;

const STATUS_LABEL: Readonly<Record<ApprovalStatus, string>> = {
  PENDING: 'Pending decision',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  EXPIRED: 'Expired',
  CANCELLED: 'Cancelled',
};
const STATUS_TONE: Readonly<Record<ApprovalStatus, Tone>> = {
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  EXPIRED: 'neutral',
  CANCELLED: 'neutral',
};
const RISK_TONE: Readonly<Record<ToolApproval['risk'], Tone>> = { LOW: 'neutral', MEDIUM: 'warning', HIGH: 'danger' };
/** A timer is only armed for a deadline within a day (a safety bound, not a product rule). */
const MAX_TIMER_MS = 24 * 60 * 60 * 1000;

/** The text shown for a failed decision. Server messages are problem-details texts meant for people. */
export function decisionErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) {
      return 'You are not allowed to decide approvals in this organization.';
    }
    if (error.status === 404) {
      return 'This approval request is no longer available.';
    }
    if (error.status === 409) {
      const detail = error.problem?.detail ? maskSensitive(error.problem.detail).slice(0, 200) : '';
      return detail || 'This request was already decided, has expired, or its execution is no longer waiting for it.';
    }
    return error.message;
  }
  return 'The decision could not be recorded. Nothing was approved or rejected.';
}

/**
 * A human approval request for ONE tool call. The decision is bound to the arguments shown here (their hash is
 * sent on approval), requires a deliberate second step, and is only displayed as recorded once the Core API has
 * confirmed it: there is no optimistic update. Approving never authorizes any other call.
 */
@Component({
  selector: 'app-approval-card',
  imports: [Avatar, Badge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="card" [class.card--pending]="status() === 'PENDING'" [attr.data-testid]="'approval-' + approval().id" [attr.aria-label]="'Approval request for ' + toolLabel()">
      <header>
        <ui-avatar [name]="agentName()" kind="agent" size="sm" />
        <div class="who">
          <strong>{{ agentName() }}</strong>
          <span class="text-tertiary">asks to use {{ toolLabel() }}</span>
        </div>
        <ui-badge [label]="statusLabel()" [tone]="statusTone()" data-testid="approval-status" />
      </header>

      <dl>
        <dt>Action</dt><dd>{{ description() }}</dd>
        @if (current().mcpServer) { <dt>MCP server</dt><dd>{{ current().mcpServer }}</dd> }
        <dt>Risk</dt><dd><ui-badge [label]="current().risk" [tone]="riskTone()" /></dd>
        <dt>Execution</dt><dd class="mono">{{ current().executionId }}</dd>
        @if (current().reason) { <dt>Why</dt><dd>{{ reason() }}</dd> }
        <dt>Expires</dt><dd>{{ current().expiresAt }}@if (isExpired()) { <span class="error"> · expired</span> }</dd>
      </dl>

      <h4>Exact arguments of this call (sensitive values are hidden)</h4>
      <pre class="args" data-testid="approval-arguments">{{ args().text }}</pre>
      @if (args().clipped) { <p class="text-tertiary">Only the beginning of the arguments is shown: review is not possible, reject or ask for a smaller request.</p> }
      <p class="text-tertiary integrity" data-testid="approval-integrity">
        Integrity: your decision is bound to these exact arguments (SHA-256 {{ shortHash() }}). If they changed, the platform refuses the approval.
      </p>

      @if (status() === 'PENDING') {
        @if (!canDecide()) {
          <p class="text-tertiary" data-testid="approval-readonly">
            {{ isExpired() ? 'This request has expired and can no longer be approved.' : 'You can see this request but your role does not allow deciding it.' }}
          </p>
        } @else if (mode() === 'idle') {
          <div class="actions">
            <button type="button" class="btn btn--primary btn--sm" data-testid="approve-start" (click)="start('approve')">Review and approve…</button>
            <button type="button" class="btn btn--danger btn--sm" data-testid="reject-start" (click)="start('reject')">Reject…</button>
          </div>
        } @else if (mode() === 'approve') {
          <div class="confirm" role="group" aria-label="Confirm approval">
            <label class="check">
              <input type="checkbox" data-testid="approve-reviewed" [checked]="reviewed()" [disabled]="busy()" (change)="reviewed.set(check($event))" />
              I reviewed the arguments above. I authorize this single call of {{ toolLabel() }}, nothing else.
            </label>
            <div class="actions">
              <button type="button" class="btn btn--primary btn--sm" data-testid="approve-confirm" [disabled]="!reviewed() || busy() || isExpired()" (click)="submit()">
                {{ busy() ? 'Recording…' : 'Confirm approval' }}
              </button>
              <button type="button" class="btn btn--ghost btn--sm" [disabled]="busy()" (click)="cancel()">Back</button>
            </div>
          </div>
        } @else {
          <div class="confirm" role="group" aria-label="Confirm rejection">
            <label class="field__label" [attr.for]="reasonId">Reason (optional, visible in the history)</label>
            <textarea class="textarea" rows="2" [id]="reasonId" [attr.maxlength]="maxReason" [value]="reason$()" [disabled]="busy()" (input)="reason$.set(text($event))"></textarea>
            <div class="actions">
              <button type="button" class="btn btn--danger btn--sm" data-testid="reject-confirm" [disabled]="busy()" (click)="submit()">
                {{ busy() ? 'Recording…' : 'Confirm rejection' }}
              </button>
              <button type="button" class="btn btn--ghost btn--sm" [disabled]="busy()" (click)="cancel()">Back</button>
            </div>
          </div>
        }
        @if (error(); as message) { <p class="error" role="alert" data-testid="approval-error">{{ message }}</p> }
      } @else {
        <p class="outcome" data-testid="approval-outcome">
          @switch (status()) {
            @case ('APPROVED') { Approved{{ decidedAt() }}. The agent continues; the tool call is {{ invocationLabel() }}. }
            @case ('REJECTED') { Rejected{{ decidedAt() }}.@if (current().rejectionReason) { Reason: {{ rejectionReason() }}. } The action was not executed. }
            @case ('EXPIRED') { This request expired before anyone decided. The action was not executed. }
            @case ('CANCELLED') { This request was cancelled because the execution ended. The action was not executed. }
          }
        </p>
      }
    </article>
  `,
  styles: `
    .card { border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: var(--space-3); background: var(--color-surface-1, var(--color-bg-app)); }
    .card--pending { border-color: var(--color-warning); box-shadow: 0 0 0 1px var(--color-warning-subtle, transparent); }
    header { display: flex; align-items: center; gap: var(--space-3); margin-bottom: var(--space-2); }
    .who { display: flex; flex-direction: column; min-width: 0; flex: 1; }
    dl { display: grid; grid-template-columns: max-content 1fr; gap: var(--space-1) var(--space-3); margin: var(--space-2) 0; }
    dt { color: var(--color-text-tertiary); }
    dd { margin: 0; overflow-wrap: anywhere; }
    .mono { font-family: ui-monospace, monospace; font-size: var(--text-xs); }
    h4 { margin: var(--space-2) 0 var(--space-1); font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .04em; color: var(--color-text-tertiary); }
    .args { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 14rem; overflow: auto; margin: 0; padding: var(--space-2) var(--space-3); background: var(--color-surface-2); border-radius: var(--radius-md); font: var(--text-xs) ui-monospace, monospace; }
    .integrity { margin: var(--space-1) 0 var(--space-2); }
    .actions { display: flex; gap: var(--space-2); margin-top: var(--space-2); }
    .confirm { border-top: 1px solid var(--color-border); padding-top: var(--space-2); }
    .check { display: flex; gap: var(--space-2); align-items: flex-start; }
    .error { color: var(--color-danger); }
    .outcome { margin: var(--space-2) 0 0; }
  `,
})
export class ApprovalCard {
  readonly approval = input.required<ToolApproval>();
  /** Emitted with the server's confirmation once a decision is recorded. */
  readonly decided = output<ToolApproval>();

  private readonly store = inject(AgentsStore);
  private readonly api = inject(AgentsApi);
  private readonly toast = inject(ToastService);

  protected readonly maxReason = MAX_REASON_LENGTH;
  protected readonly reasonId = `approval-reason-${Math.random().toString(36).slice(2)}`;
  /** What the server last said about this approval (the input, replaced by every confirmed answer). */
  protected readonly current = linkedSignal<ToolApproval, ToolApproval>({
    source: () => this.approval(),
    // A later read that still says PENDING (an answer that was in flight) never undoes a decision the server confirmed.
    computation: (next, previous) => (previous && previous.value.id === next.id && previous.value.status !== 'PENDING' && next.status === 'PENDING' ? previous.value : next),
  });
  private readonly approvalId = computed(() => this.approval().id);
  protected readonly mode = signal<'idle' | 'approve' | 'reject'>('idle');
  protected readonly reviewed = signal(false);
  protected readonly reason$ = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly now = signal(Date.now());

  protected readonly status = computed(() => this.current().status);
  protected readonly statusLabel = computed(() => STATUS_LABEL[this.current().status]);
  protected readonly statusTone = computed(() => STATUS_TONE[this.current().status]);
  protected readonly riskTone = computed(() => RISK_TONE[this.current().risk]);
  protected readonly agentName = computed(() => this.current().agentName ?? 'AI agent');
  protected readonly toolLabel = computed(() => this.current().toolName ?? this.current().toolCode);
  protected readonly description = computed(() => maskSensitive(this.current().description));
  protected readonly reason = computed(() => maskSensitive(this.current().reason));
  protected readonly rejectionReason = computed(() => maskSensitive(this.current().rejectionReason));
  protected readonly args = computed(() => bounded(this.current().argumentsRedacted, MAX_ARGUMENT_CHARS));
  protected readonly shortHash = computed(() => `${this.current().argumentsHash.slice(0, 8)}…${this.current().argumentsHash.slice(-6)}`);
  protected readonly invocationLabel = computed(() => {
    const status = this.current().invocationStatus;
    return status ? INVOCATION_LABEL[status].toLowerCase() : 'queued';
  });
  protected readonly isExpired = computed(
    () => this.current().status === 'PENDING' && Date.parse(this.current().expiresAt) <= this.now(),
  );
  /** Usability hint only: the Core API decides (permission, expiry, arguments, grant, policy). */
  protected readonly canDecide = computed(() => this.store.can('approval.decide') && this.status() === 'PENDING' && !this.isExpired());

  constructor() {
    // When the approval itself changes (another request, another organization) every transient state is dropped.
    effect(() => {
      this.approvalId();
      untracked(() => {
        this.cancel();
        this.error.set(null);
      });
    });
    // One timer to the deadline, so an approval cannot be approved from a stale screen after it expired.
    effect((onCleanup) => {
      const { status, expiresAt } = this.current();
      const wait = Date.parse(expiresAt) - Date.now();
      if (status === 'PENDING' && wait > 0 && wait <= MAX_TIMER_MS) {
        const timer = setTimeout(() => this.now.set(Date.now()), wait + 5);
        onCleanup(() => clearTimeout(timer));
      }
    });
  }

  protected decidedAt(): string {
    const at = this.current().decidedAt;
    return at ? ` on ${at}` : '';
  }

  protected check(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  protected text(event: Event): string {
    return (event.target as HTMLTextAreaElement).value;
  }

  protected start(mode: 'approve' | 'reject'): void {
    this.error.set(null);
    this.reviewed.set(false);
    this.mode.set(mode);
  }

  protected cancel(): void {
    this.mode.set('idle');
    this.reviewed.set(false);
    this.reason$.set('');
  }

  protected async submit(): Promise<void> {
    const organizationId = this.store.organizationId();
    const target = this.current();
    const mode = this.mode();
    if (!organizationId || this.busy() || mode === 'idle' || (mode === 'approve' && (!this.reviewed() || this.isExpired()))) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      const confirmed =
        mode === 'approve'
          ? await this.api.approve(organizationId, target.id, target.argumentsHash)
          : await this.api.reject(organizationId, target.id, this.reason$().slice(0, MAX_REASON_LENGTH));
      if (this.store.organizationId() !== organizationId) {
        return; // the organization changed meanwhile: this answer no longer belongs on screen
      }
      this.current.set(confirmed);
      this.cancel();
      this.toast.success(mode === 'approve' ? 'Approval recorded' : 'Rejection recorded');
      this.decided.emit(confirmed);
    } catch (error) {
      if (this.store.organizationId() === organizationId) {
        this.error.set(decisionErrorMessage(error));
        // Someone else may have decided, or it expired: show the real state instead of leaving a stale card.
        if (error instanceof ApiError && (error.status === 409 || error.status === 404)) {
          void this.refresh(organizationId, target.id);
        }
      }
    } finally {
      this.busy.set(false);
    }
  }

  private async refresh(organizationId: string, approvalId: string): Promise<void> {
    try {
      const latest = await this.api.getApproval(organizationId, approvalId);
      if (this.store.organizationId() === organizationId && this.current().id === approvalId) {
        this.current.set(latest);
        if (latest.status !== 'PENDING') {
          this.cancel();
          this.decided.emit(latest);
        }
      }
    } catch {
      // The explanation above stays; nothing else to show.
    }
  }
}
