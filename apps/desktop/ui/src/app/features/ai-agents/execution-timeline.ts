import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { TimelineEntry, TimelineKind, formatDuration } from './execution-timeline.model';

const KIND_LABEL: Readonly<Record<TimelineKind, string>> = { execution: 'Execution', tool: 'Tool', approval: 'Approval' };

/** Chronological list of what really happened in an execution. Status is always written, never colour only. */
@Component({
  selector: 'app-execution-timeline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!entries().length) {
      <p class="text-tertiary" data-testid="timeline-empty">No activity recorded yet.</p>
    } @else {
      <ol class="timeline" aria-label="Execution timeline">
        @for (item of entries(); track item.key) {
          <li class="entry" [class]="'entry entry--' + item.kind + ' tone-' + item.tone" data-testid="timeline-entry">
            <span class="marker" aria-hidden="true"></span>
            <div class="content">
              <div class="line">
                <span class="kind">{{ kindLabel[item.kind] }}</span>
                <strong class="title">{{ item.title }}</strong>
                @if (item.status) { <span class="status">{{ item.status }}</span> }
              </div>
              <div class="meta text-tertiary">
                <time [attr.datetime]="item.at">{{ item.at }}</time>
                @if (item.durationMs !== null) { <span> · {{ duration(item.durationMs) }}</span> }
              </div>
              @if (item.detail) { <p class="detail">{{ item.detail }}</p> }
              @if (item.error) { <p class="error" role="alert">{{ item.error }}</p> }
            </div>
          </li>
        }
      </ol>
    }
  `,
  styles: `
    .timeline { list-style: none; margin: 0; padding: 0; }
    .entry { position: relative; display: flex; gap: var(--space-3); padding: 0 0 var(--space-3) 0; }
    .entry::before { content: ''; position: absolute; left: 5px; top: 14px; bottom: 0; width: 1px; background: var(--color-border); }
    .entry:last-child::before { display: none; }
    .marker { flex: 0 0 11px; height: 11px; margin-top: 4px; border-radius: 50%; background: var(--color-neutral); border: 2px solid var(--color-bg-app); box-shadow: 0 0 0 1px var(--color-border); }
    .tone-success .marker { background: var(--color-success); }
    .tone-danger .marker { background: var(--color-danger); }
    .tone-warning .marker { background: var(--color-warning); }
    .tone-info .marker { background: var(--color-info, var(--color-accent)); }
    .entry--approval .title { color: var(--color-warning); }
    .entry--approval.tone-success .title { color: var(--color-success); }
    .entry--approval.tone-danger .title { color: var(--color-danger); }
    .entry--approval.tone-neutral .title { color: inherit; }
    .line { display: flex; flex-wrap: wrap; align-items: baseline; gap: var(--space-2); }
    .kind { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .04em; color: var(--color-text-tertiary); }
    .status { font-size: var(--text-xs); padding: 0 6px; border-radius: var(--radius-full); background: var(--color-surface-2); }
    .meta { font-size: var(--text-xs); }
    .detail { margin: var(--space-1) 0 0; overflow-wrap: anywhere; }
    .error { margin: var(--space-1) 0 0; color: var(--color-danger); overflow-wrap: anywhere; }
    .content { min-width: 0; }
  `,
})
export class ExecutionTimeline {
  readonly entries = input.required<readonly TimelineEntry[]>();
  protected readonly kindLabel = KIND_LABEL;

  protected duration(ms: number): string {
    return formatDuration(ms);
  }
}
