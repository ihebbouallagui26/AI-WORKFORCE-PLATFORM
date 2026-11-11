import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';
import { Icon } from '../icon/icon';
import { IconName } from '../icon/icon-registry';

export interface SegmentOption<T extends string> {
  readonly value: T;
  readonly label: string;
  readonly icon?: IconName;
  /** Hide the label visually (icon-only), keeping it for assistive technologies. */
  readonly iconOnly?: boolean;
}

@Component({
  selector: 'ui-segmented',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="segmented" role="radiogroup" [attr.aria-label]="label()">
      @for (option of options(); track option.value) {
        <button
          type="button"
          role="radio"
          class="segmented__option"
          [class.is-active]="option.value === value()"
          [attr.aria-checked]="option.value === value()"
          [attr.aria-label]="option.iconOnly ? option.label : null"
          (click)="value.set(option.value)"
        >
          @if (option.icon) {
            <ui-icon [name]="option.icon" [size]="15" />
          }
          @if (!option.iconOnly) {
            <span>{{ option.label }}</span>
          }
        </button>
      }
    </div>
  `,
  styles: `
    .segmented {
      display: inline-flex;
      gap: 2px;
      padding: 2px;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: var(--color-bg-app);
    }
    .segmented__option {
      display: inline-flex;
      align-items: center;
      gap: var(--space-1-5);
      height: 26px;
      padding: 0 var(--space-2);
      border: 0;
      border-radius: var(--radius-sm);
      background: none;
      color: var(--color-text-tertiary);
      font-size: var(--text-xs);
      font-weight: var(--weight-medium);
      cursor: pointer;
    }
    .segmented__option:hover {
      color: var(--color-text-primary);
    }
    .segmented__option.is-active {
      background: var(--color-surface-3);
      color: var(--color-text-primary);
    }
  `,
})
export class Segmented<T extends string> {
  readonly options = input.required<readonly SegmentOption<T>[]>();
  readonly value = model.required<T>();
  readonly label = input('View');
}
