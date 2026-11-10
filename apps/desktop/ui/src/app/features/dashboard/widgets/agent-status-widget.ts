import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AgentStatus } from '../../../core/models';
import { AgentStore } from '../../../core/state/agent.store';
import { Badge } from '../../../shared/components/badge/badge';
import { Member } from '../../../shared/components/member/member';
import { Panel } from '../../../shared/components/panel/panel';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { WORK_STATUS } from '../../../shared/utils/presentation';

const STATUS_ORDER: readonly AgentStatus[] = ['working', 'online', 'needs-approval', 'error', 'idle', 'offline'];

@Component({
  selector: 'app-agent-status-widget',
  imports: [RouterLink, Panel, Badge, Member, Skeleton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-panel heading="Agent status" [subheading]="store.agents().length + ' AI agents in this organization'">
      <a panelActions class="link-button" routerLink="/agents">Manage</a>
      @if (store.loading()) {
        <ui-skeleton [rows]="3" />
      } @else {
        <div class="distribution" role="img" [attr.aria-label]="summaryLabel()">
          @for (bucket of buckets(); track bucket.status) {
            @if (bucket.count) {
              <span class="distribution__segment" [class]="'distribution__segment is-' + bucket.status" [style.flex-grow]="bucket.count"></span>
            }
          }
        </div>
        <ul class="legend">
          @for (bucket of buckets(); track bucket.status) {
            <li>
              <a class="legend__item" routerLink="/agents" [queryParams]="{ status: bucket.status }">
                <ui-badge [presentation]="statuses[bucket.status]" [plain]="true" />
                <span class="legend__count tabular">{{ bucket.count }}</span>
              </a>
            </li>
          }
        </ul>
        @if (attention().length) {
          <p class="caps-label attention__label">Needs attention</p>
          <ul class="attention">
            @for (agent of attention(); track agent.id) {
              <li>
                <a class="attention__row" [routerLink]="['/agents', agent.id]">
                  <ui-member [ref]="{ kind: 'agent', id: agent.id }" [showRole]="true" [showAiTag]="false" />
                  <ui-badge [presentation]="statuses[agent.status]" />
                </a>
              </li>
            }
          </ul>
        }
      }
    </ui-panel>
  `,
  styles: `
    .distribution {
      display: flex;
      gap: 3px;
      height: 8px;
      margin-bottom: var(--space-4);
    }
    .distribution__segment {
      border-radius: 2px;
      background: var(--color-neutral);
    }
    .distribution__segment.is-working { background: var(--color-accent); }
    .distribution__segment.is-online { background: var(--color-success); }
    .distribution__segment.is-needs-approval { background: var(--color-warning); }
    .distribution__segment.is-error { background: var(--color-danger); }
    .distribution__segment.is-idle { background: var(--color-text-tertiary); }
    .distribution__segment.is-offline { background: var(--color-surface-3); }
    .legend {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 2px var(--space-4);
    }
    .legend__item {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 4px 6px;
      margin: 0 -6px;
      border-radius: var(--radius-sm);
      color: inherit;
    }
    .legend__item:hover {
      background: var(--color-surface-hover);
      text-decoration: none;
    }
    .legend__count {
      color: var(--color-text-primary);
      font-weight: var(--weight-semibold);
    }
    .attention__label {
      margin: var(--space-4) 0 var(--space-2);
    }
    .attention__row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
      padding: var(--space-2);
      margin: 0 calc(var(--space-2) * -1);
      border-radius: var(--radius-md);
      color: inherit;
    }
    .attention__row:hover {
      background: var(--color-surface-hover);
      text-decoration: none;
    }
  `,
})
export class AgentStatusWidget {
  protected readonly store = inject(AgentStore);
  protected readonly statuses = WORK_STATUS;

  protected readonly buckets = computed(() =>
    STATUS_ORDER.map((status) => ({
      status,
      count: this.store.agents().filter((a) => a.status === status).length,
    })),
  );

  protected readonly attention = computed(() =>
    this.store.agents().filter((a) => a.status === 'error' || a.status === 'needs-approval'),
  );

  protected readonly summaryLabel = computed(() =>
    this.buckets()
      .filter((b) => b.count)
      .map((b) => `${b.count} ${WORK_STATUS[b.status].label.toLowerCase()}`)
      .join(', '),
  );
}
