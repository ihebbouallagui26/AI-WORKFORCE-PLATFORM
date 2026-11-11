import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Icon } from '../icon/icon';
import { IconName } from '../icon/icon-registry';

/** Empty, error and informational placeholder. Project actions as content. */
@Component({
  selector: 'ui-empty-state',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class.empty--danger]': "tone() === 'danger'", '[class.empty--compact]': 'compact()' },
  template: `
    <span class="empty__icon"><ui-icon [name]="icon()" [size]="compact() ? 18 : 22" /></span>
    <p class="empty__title">{{ heading() }}</p>
    @if (description()) {
      <p class="empty__description">{{ description() }}</p>
    }
    <div class="empty__actions"><ng-content /></div>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--space-2);
      padding: var(--space-10) var(--space-6);
      text-align: center;
    }
    :host(.empty--compact) {
      padding: var(--space-6) var(--space-4);
    }
    .empty__icon {
      display: inline-flex;
      padding: 10px;
      margin-bottom: var(--space-1);
      border-radius: var(--radius-lg);
      background: var(--color-surface-3);
      color: var(--color-text-secondary);
    }
    :host(.empty--danger) .empty__icon {
      background: var(--color-danger-subtle);
      color: var(--color-danger);
    }
    .empty__title {
      color: var(--color-text-primary);
      font-weight: var(--weight-semibold);
    }
    .empty__description {
      max-width: 380px;
      color: var(--color-text-tertiary);
      font-size: var(--text-xs);
    }
    .empty__actions:not(:empty) {
      display: flex;
      gap: var(--space-2);
      margin-top: var(--space-2);
    }
  `,
})
export class EmptyState {
  readonly heading = input.required<string>();
  readonly description = input<string>('');
  readonly icon = input<IconName>('inbox');
  readonly tone = input<'neutral' | 'danger'>('neutral');
  readonly compact = input(false);
}
