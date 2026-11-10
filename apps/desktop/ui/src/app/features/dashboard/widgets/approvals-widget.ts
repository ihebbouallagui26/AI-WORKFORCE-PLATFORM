import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SupervisionStore } from '../../../core/state/supervision.store';
import { Badge } from '../../../shared/components/badge/badge';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Member } from '../../../shared/components/member/member';
import { Panel } from '../../../shared/components/panel/panel';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { RelativeTimePipe } from '../../../shared/pipes/relative-time.pipe';
import { RISK_LEVEL } from '../../../shared/utils/presentation';

@Component({
  selector: 'app-approvals-widget',
  imports: [RouterLink, Panel, Badge, Member, Skeleton, EmptyState, RelativeTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-panel heading="Pending approvals" subheading="Agents are paused until you decide" [flush]="true">
      <a panelActions class="link-button" routerLink="/activity-center" [queryParams]="{ tab: 'approvals' }">Review all</a>
      @if (store.loading()) {
        <ui-skeleton [rows]="3" />
      } @else {
        <ul>
          @for (approval of approvals(); track approval.id) {
            <li>
              <a class="list-row list-row--interactive approval" routerLink="/activity-center" [queryParams]="{ tab: 'approvals', focus: approval.id }">
                <ui-member [ref]="{ kind: 'agent', id: approval.agentId }" [avatarOnly]="true" />
                <span class="list-row__body">
                  <span class="list-row__title">{{ approval.action }}</span>
                  <span class="list-row__meta">{{ approval.requestedAt | relativeTime }}</span>
                </span>
                <ui-badge [presentation]="risks[approval.risk]" />
              </a>
            </li>
          } @empty {
            <ui-empty-state [compact]="true" icon="shield-check" heading="Nothing to approve" description="Agents have no pending sensitive operations." />
          }
        </ul>
        @if (remaining() > 0) {
          <p class="more">+{{ remaining() }} more waiting in the Activity Center</p>
        }
      }
    </ui-panel>
  `,
  styles: `
    .approval {
      color: inherit;
    }
    .approval:hover {
      text-decoration: none;
    }
    .more {
      padding: var(--space-2) var(--space-4) var(--space-3);
      color: var(--color-text-tertiary);
      font-size: var(--text-xs);
    }
  `,
})
export class ApprovalsWidget {
  protected readonly store = inject(SupervisionStore);
  protected readonly risks = RISK_LEVEL;
  protected readonly approvals = computed(() => this.store.pendingApprovals().slice(0, 4));
  protected readonly remaining = computed(() => Math.max(0, this.store.pendingApprovals().length - 4));
}
