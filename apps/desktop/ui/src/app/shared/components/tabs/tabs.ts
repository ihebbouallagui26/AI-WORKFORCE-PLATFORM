import { ChangeDetectionStrategy, Component, ElementRef, inject, input, model } from '@angular/core';
import { Icon } from '../icon/icon';
import { IconName } from '../icon/icon-registry';

export interface TabItem {
  readonly id: string;
  readonly label: string;
  readonly count?: number | null;
  readonly icon?: IconName;
}

/** Accessible tab list (WAI-ARIA tabs pattern, manual activation with arrow keys). */
@Component({
  selector: 'ui-tabs',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tabs" role="tablist" [attr.aria-label]="label()" (keydown)="onKeydown($event)">
      @for (item of items(); track item.id) {
        <button
          type="button"
          role="tab"
          class="tabs__tab"
          [class.is-active]="item.id === active()"
          [attr.aria-selected]="item.id === active()"
          [tabIndex]="item.id === active() ? 0 : -1"
          (click)="active.set(item.id)"
        >
          @if (item.icon) {
            <ui-icon [name]="item.icon" [size]="15" />
          }
          {{ item.label }}
          @if (item.count !== undefined && item.count !== null) {
            <span class="tabs__count tabular">{{ item.count }}</span>
          }
        </button>
      }
    </div>
  `,
  styles: `
    .tabs {
      display: flex;
      gap: var(--space-1);
      border-bottom: 1px solid var(--color-border-subtle);
      overflow-x: auto;
      scrollbar-width: none;
    }
    .tabs__tab {
      position: relative;
      display: inline-flex;
      align-items: center;
      gap: var(--space-1-5);
      height: 38px;
      padding: 0 var(--space-3);
      border: 0;
      background: none;
      color: var(--color-text-tertiary);
      font-size: var(--text-sm);
      font-weight: var(--weight-medium);
      white-space: nowrap;
      cursor: pointer;
      border-radius: var(--radius-md) var(--radius-md) 0 0;
      transition: color var(--duration-fast) var(--ease-standard);
    }
    .tabs__tab:hover {
      color: var(--color-text-primary);
    }
    .tabs__tab.is-active {
      color: var(--color-text-primary);
    }
    .tabs__tab.is-active::after {
      content: '';
      position: absolute;
      left: var(--space-2);
      right: var(--space-2);
      bottom: -1px;
      height: 2px;
      border-radius: 2px;
      background: var(--color-accent);
    }
    .tabs__count {
      min-width: 18px;
      padding: 0 5px;
      border-radius: var(--radius-full);
      background: var(--color-surface-3);
      color: var(--color-text-secondary);
      font-size: var(--text-2xs);
      line-height: 17px;
      text-align: center;
    }
  `,
})
export class Tabs {
  readonly items = input.required<readonly TabItem[]>();
  readonly active = model.required<string>();
  readonly label = input('Sections');

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected onKeydown(event: KeyboardEvent): void {
    const items = this.items();
    const index = items.findIndex((i) => i.id === this.active());
    let next = -1;
    if (event.key === 'ArrowRight') next = (index + 1) % items.length;
    if (event.key === 'ArrowLeft') next = (index - 1 + items.length) % items.length;
    if (event.key === 'Home') next = 0;
    if (event.key === 'End') next = items.length - 1;
    if (next < 0) {
      return;
    }
    event.preventDefault();
    this.active.set(items[next].id);
    const buttons = this.host.nativeElement.querySelectorAll<HTMLButtonElement>('[role="tab"]');
    buttons[next]?.focus();
  }
}
