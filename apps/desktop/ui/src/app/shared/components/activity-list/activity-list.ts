import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ActivityEvent } from '../../../core/models';
import { ProjectStore } from '../../../core/state/project.store';
import { RelativeTimePipe } from '../../pipes/relative-time.pipe';
import { ACTIVITY_EVENT } from '../../utils/presentation';
import { Icon } from '../icon/icon';
import { Member } from '../member/member';

/** Timeline of activity events, used by the dashboard and the Activity Center. */
@Component({
  selector: 'ui-activity-list',
  imports: [Icon, Member, RouterLink, RelativeTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol class="timeline">
      @for (event of events(); track event.id) {
        <li class="timeline__item" [class.is-unread]="!event.read">
          <span class="timeline__icon" [class]="'timeline__icon timeline__icon--' + presentation[event.type].tone">
            <ui-icon [name]="presentation[event.type].icon" [size]="14" />
          </span>
          <div class="timeline__body">
            <p class="timeline__title">
              {{ event.title }}
              @if (!event.read) {
                <span class="sr-only">(unread)</span>
              }
            </p>
            <p class="timeline__detail">{{ event.detail }}</p>
            <div class="timeline__meta">
              <ui-member [ref]="event.actorRef" size="xs" />
              @if (event.projectId && projects.projectById().get(event.projectId); as project) {
                <span aria-hidden="true">·</span>
                <a [routerLink]="['/projects', project.id]">{{ project.name }}</a>
              }
            </div>
          </div>
          <time class="timeline__time" [attr.datetime]="event.occurredAt">{{ event.occurredAt | relativeTime }}</time>
        </li>
      }
    </ol>
  `,
  styles: `
    .timeline__item {
      position: relative;
      display: flex;
      gap: var(--space-3);
      padding: var(--space-3) var(--space-4);
    }
    .timeline__item:not(:last-child)::after {
      content: '';
      position: absolute;
      left: calc(var(--space-4) + 13px);
      top: 42px;
      bottom: -6px;
      width: 1px;
      background: var(--color-border-subtle);
    }
    .timeline__icon {
      position: relative;
      z-index: 1;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 28px;
      height: 28px;
      flex-shrink: 0;
      border-radius: var(--radius-md);
      background: var(--color-surface-3);
      color: var(--color-text-secondary);
    }
    .timeline__icon--accent { background: var(--color-accent-subtle); color: var(--color-accent-text); }
    .timeline__icon--success { background: var(--color-success-subtle); color: var(--color-success); }
    .timeline__icon--warning { background: var(--color-warning-subtle); color: var(--color-warning); }
    .timeline__icon--danger { background: var(--color-danger-subtle); color: var(--color-danger); }
    .timeline__icon--info { background: var(--color-info-subtle); color: var(--color-info); }
    .timeline__body {
      flex: 1;
      min-width: 0;
    }
    .timeline__title {
      color: var(--color-text-primary);
      font-weight: var(--weight-medium);
    }
    .is-unread .timeline__title::before {
      content: '';
      display: inline-block;
      width: 6px;
      height: 6px;
      margin-right: 6px;
      border-radius: 50%;
      background: var(--color-accent);
      vertical-align: middle;
    }
    .timeline__detail {
      margin-top: 1px;
      color: var(--color-text-secondary);
      font-size: var(--text-xs);
    }
    .timeline__meta {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--space-1-5);
      margin-top: var(--space-2);
      color: var(--color-text-tertiary);
      font-size: var(--text-xs);
    }
    .timeline__time {
      flex-shrink: 0;
      color: var(--color-text-tertiary);
      font-size: var(--text-xs);
      white-space: nowrap;
    }
  `,
})
export class ActivityList {
  readonly events = input.required<readonly ActivityEvent[]>();
  protected readonly projects = inject(ProjectStore);
  protected readonly presentation = ACTIVITY_EVENT;
}
