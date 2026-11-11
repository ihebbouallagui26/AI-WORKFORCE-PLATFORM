import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { ProjectStore } from '../../../core/state/project.store';
import { SupervisionStore } from '../../../core/state/supervision.store';
import { AvatarStack } from '../../../shared/components/avatar-stack/avatar-stack';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Panel } from '../../../shared/components/panel/panel';
import { RelativeTimePipe } from '../../../shared/pipes/relative-time.pipe';

@Component({
  selector: 'app-meetings-widget',
  imports: [DatePipe, Panel, AvatarStack, EmptyState, RelativeTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-panel heading="Upcoming meetings" [flush]="true">
      <ul>
        @for (meeting of meetings(); track meeting.id) {
          <li class="list-row">
            <div class="when">
              <span class="when__time tabular">{{ meeting.startsAt | date: 'HH:mm' }}</span>
              <span class="when__day">{{ meeting.startsAt | date: 'EEE d' }}</span>
            </div>
            <div class="list-row__body">
              <span class="list-row__title">{{ meeting.title }}</span>
              <span class="list-row__meta">
                {{ meeting.startsAt | relativeTime }} · {{ meeting.durationMinutes }} min
                @if (meeting.projectId && projects.projectById().get(meeting.projectId); as project) {
                  · {{ project.name }}
                }
              </span>
            </div>
            <ui-avatar-stack [refs]="meeting.participantRefs" [max]="3" />
          </li>
        } @empty {
          <ui-empty-state [compact]="true" icon="calendar" heading="No upcoming meetings" />
        }
      </ul>
    </ui-panel>
  `,
  styles: `
    .when {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      width: 52px;
      height: 42px;
      flex-shrink: 0;
      border-radius: var(--radius-md);
      background: var(--color-surface-2);
      border: 1px solid var(--color-border-subtle);
    }
    .when__time {
      color: var(--color-text-primary);
      font-weight: var(--weight-semibold);
      font-size: var(--text-xs);
    }
    .when__day {
      color: var(--color-text-tertiary);
      font-size: var(--text-2xs);
    }
  `,
})
export class MeetingsWidget {
  private readonly supervision = inject(SupervisionStore);
  protected readonly projects = inject(ProjectStore);
  protected readonly meetings = computed(() => this.supervision.upcomingMeetings().slice(0, 4));
}
