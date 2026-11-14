import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, output } from '@angular/core';
import { AgentsApi } from '../../core/agents/agents.api';
import { AgentExecution, ToolApproval, ToolDefinition, ToolInvocation } from '../../core/agents/agents.models';
import { AgentsStore } from '../../core/agents/agents.store';
import { orgResource } from '../../core/workforce/org-resource';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { ApprovalCard } from './approval-card';
import { ExecutionTimeline } from './execution-timeline';
import { buildTimeline } from './execution-timeline.model';
import { ToolActivityCard } from './tool-activity-card';

/**
 * What happened inside one execution: the decision that needs a person (if the run is waiting), the timeline and
 * the tool calls. Everything is read from the Core API for the CURRENT organization and refreshed only when the
 * execution record changes (its status or version), so it adds no polling of its own. A reload of the app shows
 * the same thing because nothing here is kept on the client.
 */
@Component({
  selector: 'app-execution-activity',
  imports: [ApprovalCard, ExecutionTimeline, ToolActivityCard, EmptyState, Skeleton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (run().status === 'WAITING_APPROVAL' || decidedHere().length) {
      <section class="block" aria-label="Approval" data-testid="decision-needed">
        <h3>{{ needsDecision() ? 'A person must decide before this run continues' : 'Decision recorded' }}</h3>
        @if (!store.can('approval.read') && !shown().length) {
          <p class="text-tertiary" data-testid="decision-no-access">
            This run is waiting for an approval. Ask a person who is allowed to review approvals in this organization.
          </p>
        } @else if (pending.error(); as message) {
          <ui-empty-state icon="circle-alert" tone="danger" heading="Approval unavailable" [description]="message" [compact]="true" />
        } @else if (pending.loading() && !pending.value()) {
          <ui-skeleton [rows]="2" />
        } @else if (!shown().length) {
          <p class="text-tertiary" data-testid="decision-none">No pending approval was found for this run. It may have just been decided.</p>
        } @else {
          @for (approval of shown(); track approval.id) {
            <app-approval-card [approval]="approval" (decided)="onDecided($event)" />
          }
        }
      </section>
    }

    @if (store.can('agent.execution.read')) {
      <section class="block" aria-label="Execution timeline">
        <h3>Timeline</h3>
        @if (calls.error(); as message) {
          <ui-empty-state icon="circle-alert" tone="danger" heading="Tool activity unavailable" [description]="message" [compact]="true" />
        } @else if (calls.loading() && !calls.value()) {
          <ui-skeleton [rows]="3" />
        } @else {
          <app-execution-timeline [entries]="entries()" />
        }
      </section>
      @if (callList().length) {
        <section class="block" aria-label="Tool activity">
          <h3>Tool activity</h3>
          <div class="stack">
            @for (call of callList(); track call.id) {
              <app-tool-activity-card [call]="call" [tool]="catalog().get(call.toolCode) ?? null" />
            }
          </div>
        </section>
      }
    }
  `,
  styles: `
    :host { display: block; }
    .block { margin-top: var(--space-4); }
    h3 { margin: 0 0 var(--space-2); font-size: var(--text-sm); }
    .stack { display: flex; flex-direction: column; gap: var(--space-2); }
  `,
})
export class ExecutionActivity {
  readonly run = input.required<AgentExecution>();
  /** A decision was recorded: the owner of the run refreshes it right away instead of waiting for its next poll. */
  readonly changed = output<void>();

  protected readonly store = inject(AgentsStore);
  private readonly api = inject(AgentsApi);

  /** Reloaded only when the run record changes. */
  private readonly runKey = computed(() => `${this.run().id}|${this.run().status}|${this.run().version}`);

  protected readonly calls = orgResource(
    this.store.organizationId,
    () => this.store.can('agent.execution.read'),
    () => this.runKey(),
    (organizationId) => this.api.executionToolCalls(organizationId, this.run().id),
  );

  protected readonly pending = orgResource(
    this.store.organizationId,
    () => this.store.can('approval.read') && this.run().status === 'WAITING_APPROVAL',
    () => this.runKey(),
    (organizationId) => this.api.listApprovals(organizationId, { executionId: this.run().id, status: 'PENDING' }),
  );

  /**
   * Approvals decided from this screen stay visible (with their confirmed outcome) even though the "pending" list no
   * longer contains them. Dropped when another run or organization is shown.
   */
  protected readonly decidedHere = linkedSignal<string, readonly ToolApproval[]>({
    source: () => `${this.store.organizationId()}|${this.run().id}`,
    computation: () => [],
  });

  /** Only arrays are ever iterated, whatever the API answered. */
  protected readonly callList = computed<readonly ToolInvocation[]>(() => {
    const content = this.calls.value()?.content;
    return Array.isArray(content) ? content : [];
  });
  protected readonly pendingList = computed<readonly ToolApproval[]>(() => {
    const content = this.pending.value()?.content;
    return Array.isArray(content) ? content : [];
  });
  /** Pending approvals of the run plus the ones decided here, the confirmed version winning. */
  protected readonly shown = computed<readonly ToolApproval[]>(() => {
    const decided = new Map(this.decidedHere().map((a) => [a.id, a]));
    const merged = this.pendingList().map((a) => decided.get(a.id) ?? a);
    return [...merged, ...this.decidedHere().filter((a) => !this.pendingList().some((p) => p.id === a.id))];
  });
  protected readonly needsDecision = computed(() => this.shown().some((a) => a.status === 'PENDING') || (this.run().status === 'WAITING_APPROVAL' && !this.shown().length));
  protected readonly entries = computed(() => buildTimeline(this.run(), this.callList()));
  protected readonly catalog = computed(() => new Map<string, ToolDefinition>((Array.isArray(this.store.tools()) ? this.store.tools() : []).map((tool) => [tool.code, tool])));

  protected onDecided(confirmed: ToolApproval): void {
    this.decidedHere.update((list) => [...list.filter((a) => a.id !== confirmed.id), confirmed]);
    this.calls.reload();
    this.pending.reload();
    this.changed.emit();
  }
}
