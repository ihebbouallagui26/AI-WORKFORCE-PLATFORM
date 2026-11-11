import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Tone } from '../../utils/presentation';
import { Icon } from '../icon/icon';
import { IconName } from '../icon/icon-registry';

@Component({
  selector: 'ui-stat-card',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="stat__top">
      <span class="stat__label">{{ label() }}</span>
      <span class="stat__icon" [class]="'stat__icon stat__icon--' + tone()"><ui-icon [name]="icon()" [size]="15" /></span>
    </div>
    <div class="stat__value tabular">{{ value() }}</div>
    @if (caption()) {
      <div class="stat__caption">{{ caption() }}</div>
    }
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      padding: var(--space-4);
      background: var(--color-surface-1);
      border: 1px solid var(--color-border-subtle);
      border-radius: var(--radius-lg);
      min-width: 0;
    }
    .stat__top {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
    }
    .stat__label {
      color: var(--color-text-secondary);
      font-size: var(--text-xs);
      font-weight: var(--weight-medium);
    }
    .stat__icon {
      display: inline-flex;
      padding: 6px;
      border-radius: var(--radius-md);
      background: var(--color-surface-3);
      color: var(--color-text-secondary);
    }
    .stat__icon--accent { background: var(--color-accent-subtle); color: var(--color-accent-text); }
    .stat__icon--success { background: var(--color-success-subtle); color: var(--color-success); }
    .stat__icon--warning { background: var(--color-warning-subtle); color: var(--color-warning); }
    .stat__icon--ai { background: var(--color-ai-subtle); color: var(--color-ai); }
    .stat__icon--info { background: var(--color-info-subtle); color: var(--color-info); }
    .stat__icon--danger { background: var(--color-danger-subtle); color: var(--color-danger); }
    .stat__value {
      font-size: var(--text-3xl);
      font-weight: var(--weight-semibold);
      letter-spacing: -0.02em;
      line-height: 1.1;
    }
    .stat__caption {
      color: var(--color-text-tertiary);
      font-size: var(--text-xs);
    }
  `,
})
export class StatCard {
  readonly label = input.required<string>();
  readonly value = input.required<string | number>();
  readonly icon = input.required<IconName>();
  readonly tone = input<Tone>('neutral');
  readonly caption = input<string>('');
}
