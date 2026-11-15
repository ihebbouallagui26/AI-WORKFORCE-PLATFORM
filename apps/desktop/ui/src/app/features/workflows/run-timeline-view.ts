import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { TimelineEvent } from '../../core/workflows/workflow-view.model';
import { Badge } from '../../shared/components/badge/badge';
import { safeText } from './workflow-labels';

/**
 * Chronological history of a run, rebuilt from the records the Core API persisted. It is refreshed by polling, so an event appears when it
 * has been recorded: this is not a live event stream. Text coming from agents is masked and bounded.
 */
@Component({
  selector: 'app-run-timeline',
  imports: [Badge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p class="text-tertiary note" data-testid="timeline-note">Reconstructed from the workflow records each time the page refreshes; it is not a live stream.</p>
    <ol class="timeline" aria-label="Workflow timeline">
      @for (event of events(); track event.key) {
        <li class="event" [attr.data-kind]="event.kind" [attr.data-testid]="'event-' + event.key">
          <time class="at tabular text-tertiary" [attr.datetime]="event.at">{{ clock(event.at) }}</time>
          <span class="body">
            <ui-badge [label]="kindLabel(event.kind)" [tone]="event.tone" />
            <span class="title">{{ safe(event.title) }}</span>
            @if (event.detail) { <span class="detail text-tertiary">{{ safe(event.detail) }}</span> }
          </span>
        </li>
      } @empty {
        <li class="text-tertiary">Nothing recorded yet.</li>
      }
    </ol>
  `,
  styles: `
    .note { font-size: var(--text-xs); margin: 0 0 var(--space-2); }
    .timeline { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--space-2); }
    .event { display: flex; gap: var(--space-3); align-items: flex-start; }
    .at { min-width: 78px; font-size: var(--text-xs); padding-top: 3px; }
    .body { display: flex; flex-wrap: wrap; gap: var(--space-2); align-items: baseline; min-width: 0; }
    .title { overflow-wrap: anywhere; }
    .detail { font-size: var(--text-xs); overflow-wrap: anywhere; }
  `,
})
export class RunTimelineView {
  readonly events = input.required<readonly TimelineEvent[]>();

  protected clock(at: string): string {
    const d = new Date(at);
    return Number.isNaN(d.getTime()) ? at : d.toISOString().slice(11, 19);
  }

  protected safe(text: string): string {
    return safeText(text, 300);
  }

  protected kindLabel(kind: TimelineEvent['kind']): string {
    return {
      run: 'Workflow', queued: 'Queued', dispatched: 'Dispatched', delegation: 'Delegation', completion: 'Completed', failure: 'Failure',
      retry: 'Retry', approval: 'Approval', group: 'Group', journal: 'Note',
    }[kind];
  }
}
