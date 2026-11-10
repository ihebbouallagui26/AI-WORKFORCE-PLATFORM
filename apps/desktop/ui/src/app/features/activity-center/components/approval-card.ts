import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Approval } from '../../../core/models';
import { ProjectStore } from '../../../core/state/project.store';
import { Badge } from '../../../shared/components/badge/badge';
import { Icon } from '../../../shared/components/icon/icon';
import { Member } from '../../../shared/components/member/member';
import { RelativeTimePipe } from '../../../shared/pipes/relative-time.pipe';
import { APPROVAL_STATUS, RISK_LEVEL, SENSITIVE_OPERATION } from '../../../shared/utils/presentation';

@Component({
  selector: 'app-approval-card',
  imports: [DatePipe, RouterLink, Badge, Icon, Member, RelativeTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class]': '"risk-" + approval().risk', '[class.is-focused]': 'focused()' },
  template: `
    <article class="approval" [attr.aria-labelledby]="'approval-' + approval().id">
      <div class="approval__rail" aria-hidden="true"></div>
      <div class="approval__content">
        <header class="approval__header">
          <a [routerLink]="['/agents', approval().agentId]" class="approval__agent">
            <ui-member [ref]="{ kind: 'agent', id: approval().agentId }" [showRole]="true" />
          </a>
          <ui-badge [presentation]="risks[approval().risk]" />
        </header>
        <h3 class="approval__action" [id]="'approval-' + approval().id">{{ approval().action }}</h3>
        <p class="approval__description">{{ approval().description }}</p>
        <div class="approval__meta">
          <span class="chip"><ui-icon name="shield-alert" [size]="12" /> {{ operations[approval().operation] }}</span>
          @if (project(); as project) {
            <a class="approval__project" [routerLink]="['/projects', project.id]"><ui-icon name="projects" [size]="13" /> {{ project.name }}</a>
          }
          <span class="approval__time">
            <ui-icon name="clock" [size]="13" />
            <time [attr.datetime]="approval().requestedAt">Requested {{ approval().requestedAt | relativeTime }}</time>
          </span>
        </div>
        @if (approval().status === 'pending') {
          <footer class="approval__actions">
            <button type="button" class="btn btn--danger btn--sm" [disabled]="busy()" (click)="reject.emit(approval())">
              <ui-icon name="close" [size]="14" /> Reject
            </button>
            <button type="button" class="btn btn--success btn--sm" [disabled]="busy()" (click)="approve.emit(approval())">
              <ui-icon name="check" [size]="14" /> Approve
            </button>
          </footer>
        } @else {
          <footer class="approval__decision">
            <ui-badge [presentation]="statuses[approval().status]" />
            <span>{{ approval().decidedAt | date: 'd MMM, HH:mm' }}</span>
            @if (approval().decisionNote) {
              <span class="approval__note">“{{ approval().decisionNote }}”</span>
            }
          </footer>
        }
      </div>
    </article>
  `,
  styleUrl: './approval-card.scss',
})
export class ApprovalCard {
  readonly approval = input.required<Approval>();
  readonly busy = input(false);
  readonly focused = input(false);
  readonly approve = output<Approval>();
  readonly reject = output<Approval>();

  private readonly projects = inject(ProjectStore);
  protected readonly risks = RISK_LEVEL;
  protected readonly statuses = APPROVAL_STATUS;
  protected readonly operations = SENSITIVE_OPERATION;
  protected readonly project = computed(() => {
    const id = this.approval().projectId;
    return id ? (this.projects.projectById().get(id) ?? null) : null;
  });
}
