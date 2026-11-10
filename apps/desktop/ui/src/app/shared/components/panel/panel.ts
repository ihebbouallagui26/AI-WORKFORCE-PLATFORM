import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Surface container with an optional header. Use `[panelActions]` to project header actions. */
@Component({
  selector: 'ui-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class.panel--flush]': 'flush()' },
  template: `
    @if (heading()) {
      <header class="panel__header">
        <div class="panel__titles">
          <h2 class="panel__title">{{ heading() }}</h2>
          @if (subheading()) {
            <p class="panel__subtitle">{{ subheading() }}</p>
          }
        </div>
        <div class="panel__actions"><ng-content select="[panelActions]" /></div>
      </header>
    }
    <div class="panel__body"><ng-content /></div>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      min-width: 0;
      background: var(--color-surface-1);
      border: 1px solid var(--color-border-subtle);
      border-radius: var(--radius-lg);
    }
    .panel__header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-3);
      min-height: 52px;
      padding: var(--space-3) var(--space-4);
      border-bottom: 1px solid var(--color-border-subtle);
    }
    .panel__titles {
      min-width: 0;
    }
    .panel__title {
      font-size: var(--text-md);
      font-weight: var(--weight-semibold);
    }
    .panel__subtitle {
      margin-top: 2px;
      font-size: var(--text-xs);
      color: var(--color-text-tertiary);
    }
    .panel__actions {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      flex-shrink: 0;
    }
    .panel__body {
      padding: var(--space-4);
      min-width: 0;
      flex: 1;
    }
    :host(.panel--flush) .panel__body {
      padding: 0;
    }
  `,
})
export class Panel {
  readonly heading = input<string>('');
  readonly subheading = input<string>('');
  readonly flush = input(false);
}
