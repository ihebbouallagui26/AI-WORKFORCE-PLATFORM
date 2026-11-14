import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { AgentsApi } from '../../core/agents/agents.api';
import { ApprovalStatus, ToolApproval } from '../../core/agents/agents.models';
import { AgentsStore } from '../../core/agents/agents.store';
import { orgResource } from '../../core/workforce/org-resource';
import { Badge } from '../../shared/components/badge/badge';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { Tone } from '../../shared/utils/presentation';
import { ApprovalCard } from './approval-card';
import { INVOCATION_LABEL, maskSensitive } from './execution-timeline.model';

export const APPROVAL_PAGE_SIZE = 20;

type Filter = ApprovalStatus | '';

const FILTERS: readonly { readonly value: Filter; readonly label: string }[] = [
  { value: 'PENDING', label: 'Pending' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'EXPIRED', label: 'Expired' },
  { value: 'CANCELLED', label: 'Cancelled' },
  { value: '', label: 'All' },
];
const TONE: Readonly<Record<ApprovalStatus, Tone>> = { PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', EXPIRED: 'neutral', CANCELLED: 'neutral' };
const LABEL: Readonly<Record<ApprovalStatus, string>> = { PENDING: 'Pending', APPROVED: 'Approved', REJECTED: 'Rejected', EXPIRED: 'Expired', CANCELLED: 'Cancelled' };

/**
 * Approval requests of the organization: the ones that need a decision (as full cards, decided one by one) and the
 * history of the others. Visible only with `approval.read`; the Core API enforces it and the organization scope.
 */
@Component({
  selector: 'app-approvals-tab',
  imports: [ApprovalCard, Badge, EmptyState, Skeleton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="filters" role="group" aria-label="Filter approvals by status">
      @for (f of filters; track f.label) {
        <button type="button" class="btn btn--sm" [class.btn--primary]="status() === f.value" [class.btn--ghost]="status() !== f.value"
          [attr.aria-pressed]="status() === f.value" (click)="setStatus(f.value)">{{ f.label }}</button>
      }
    </div>

    @if (list.error(); as message) {
      <ui-empty-state icon="circle-alert" tone="danger" heading="Approvals unavailable" [description]="message" />
    } @else if (list.loading() && !list.value()) {
      <ui-skeleton [rows]="3" />
    } @else if (!items().length) {
      <ui-empty-state icon="inbox" heading="No approval request" description="Nothing matches this filter." data-testid="approvals-empty" />
    } @else {
      <ul class="plain">
        @for (item of items(); track item.id) {
          <li [attr.data-testid]="'approval-row-' + item.id">
            @if (item.status === 'PENDING') {
              <app-approval-card [approval]="item" (decided)="list.reload()" />
            } @else {
              <div class="row">
                <div class="main">
                  <strong>{{ item.agentName ?? 'AI agent' }}</strong>
                  <span class="text-tertiary"> · {{ item.toolName ?? item.toolCode }}</span>
                  <p class="text-tertiary desc">{{ clean(item.description) }}</p>
                  <p class="text-tertiary meta">
                    Execution {{ item.executionId }}
                    @if (item.decidedAt) { · decided {{ item.decidedAt }}{{ item.decidedBy ? ' by member ' + short(item.decidedBy) : '' }} }
                    @else { · requested {{ item.requestedAt }} }
                    @if (item.invocationStatus) { · tool call {{ invocationLabel(item) }} }
                  </p>
                  @if (item.rejectionReason) { <p class="text-tertiary meta">Reason: {{ clean(item.rejectionReason) }}</p> }
                </div>
                <ui-badge [label]="label[item.status]" [tone]="tone[item.status]" />
              </div>
            }
          </li>
        }
      </ul>
      @if ((list.value()?.totalPages ?? 0) > 1) {
        <nav class="pager" aria-label="Approval pages">
          <button type="button" class="btn btn--ghost btn--sm" [disabled]="page() === 0" (click)="go(page() - 1)">Previous</button>
          <span class="text-tertiary">Page {{ page() + 1 }} of {{ list.value()?.totalPages }}</span>
          <button type="button" class="btn btn--ghost btn--sm" [disabled]="page() + 1 >= (list.value()?.totalPages ?? 0)" (click)="go(page() + 1)">Next</button>
        </nav>
      }
    }
  `,
  styles: `
    .filters { display: flex; gap: var(--space-2); flex-wrap: wrap; margin-bottom: var(--space-3); }
    .plain { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--space-3); }
    .row { display: flex; justify-content: space-between; gap: var(--space-3); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: var(--space-3); }
    .main { min-width: 0; }
    .desc, .meta { margin: var(--space-1) 0 0; overflow-wrap: anywhere; }
    .pager { display: flex; align-items: center; justify-content: center; gap: var(--space-3); margin-top: var(--space-3); }
  `,
})
export class ApprovalsTab {
  private readonly store = inject(AgentsStore);
  private readonly api = inject(AgentsApi);

  protected readonly filters = FILTERS;
  protected readonly tone = TONE;
  protected readonly label = LABEL;
  protected readonly status = signal<Filter>('PENDING');
  protected readonly page = signal(0);

  protected readonly list = orgResource(
    this.store.organizationId,
    () => this.store.can('approval.read'),
    () => ({ status: this.status(), page: this.page() }),
    (organizationId, key) =>
      this.api.listApprovals(organizationId, {
        size: APPROVAL_PAGE_SIZE,
        page: key.page,
        ...(key.status ? { status: key.status } : {}),
      }),
  );

  /** Only an array is ever iterated, whatever the API answered. */
  protected readonly items = computed<readonly ToolApproval[]>(() => {
    const content = this.list.value()?.content;
    return Array.isArray(content) ? content : [];
  });

  protected setStatus(value: Filter): void {
    this.status.set(value);
    this.page.set(0);
  }

  protected go(page: number): void {
    this.page.set(Math.max(0, page));
  }

  protected clean(text: string | null): string {
    return maskSensitive(text);
  }

  protected short(id: string): string {
    return id.slice(0, 8);
  }

  protected invocationLabel(item: ToolApproval): string {
    return item.invocationStatus ? INVOCATION_LABEL[item.invocationStatus].toLowerCase() : '';
  }
}
