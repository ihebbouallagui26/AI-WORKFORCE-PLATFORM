import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, inject, input, signal } from '@angular/core';

interface PanelPosition {
  readonly top: number | null;
  readonly bottom: number | null;
  readonly left: number | null;
  readonly right: number | null;
}

const OFFSET = 6;

/**
 * Generic dropdown. Project the trigger with `[dropdownTrigger]` and toggle it through a
 * template reference (`#menu="uiDropdown"` → `(click)="menu.toggle()"`).
 * Items using the global `.menu-item` class close the panel when activated.
 * The panel is positioned with `position: fixed` so scroll containers never clip it.
 */
@Component({
  selector: 'ui-dropdown',
  exportAs: 'uiDropdown',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:click)': 'onDocumentClick($event)',
    '(keydown.escape)': 'closeAndFocusTrigger()',
    '(window:resize)': 'close()',
  },
  template: `
    <ng-content select="[dropdownTrigger]" />
    @if (isOpen()) {
      <div
        class="dropdown__panel"
        [style.width.px]="width()"
        [style.top.px]="position().top"
        [style.bottom.px]="position().bottom"
        [style.left.px]="position().left"
        [style.right.px]="position().right"
        (click)="onPanelClick($event)"
        (keydown)="onPanelKeydown($event)"
      >
        <ng-content />
      </div>
    }
  `,
  styles: `
    :host {
      position: relative;
      display: inline-flex;
    }
    .dropdown__panel {
      position: fixed;
      z-index: var(--z-dropdown);
      min-width: 200px;
      max-height: min(440px, calc(100vh - 80px));
      overflow-y: auto;
      padding: var(--space-1);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      background: var(--color-surface-2);
      box-shadow: var(--shadow-md);
      animation: dropdown-in var(--duration-fast) var(--ease-standard);
    }
    @keyframes dropdown-in {
      from { opacity: 0; transform: translateY(-4px); }
    }
  `,
})
export class Dropdown {
  readonly align = input<'start' | 'end'>('start');
  readonly direction = input<'down' | 'up'>('down');
  readonly width = input<number | null>(null);

  readonly isOpen = signal(false);
  protected readonly position = signal<PanelPosition>({ top: null, bottom: null, left: null, right: null });
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  constructor() {
    // Scroll events do not bubble: listen in the capture phase to catch any scroll container.
    const listener = (event: Event) => this.onScroll(event);
    document.addEventListener('scroll', listener, true);
    inject(DestroyRef).onDestroy(() => document.removeEventListener('scroll', listener, true));
  }

  toggle(): void {
    if (this.isOpen()) {
      this.close();
      return;
    }
    this.position.set(this.computePosition());
    this.isOpen.set(true);
    queueMicrotask(() => this.focusItem(0));
  }

  close(): void {
    this.isOpen.set(false);
  }

  protected closeAndFocusTrigger(): void {
    if (!this.isOpen()) {
      return;
    }
    this.close();
    this.host.nativeElement.querySelector<HTMLElement>('[dropdownTrigger]')?.focus();
  }

  protected onDocumentClick(event: MouseEvent): void {
    if (this.isOpen() && !this.host.nativeElement.contains(event.target as Node)) {
      this.close();
    }
  }

  private onScroll(event: Event): void {
    const panel = this.host.nativeElement.querySelector('.dropdown__panel');
    if (this.isOpen() && !(panel && panel.contains(event.target as Node))) {
      this.close();
    }
  }

  protected onPanelClick(event: MouseEvent): void {
    const item = (event.target as HTMLElement).closest('.menu-item');
    if (item && !item.hasAttribute('data-keep-open')) {
      this.close();
    }
  }

  protected onPanelKeydown(event: KeyboardEvent): void {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') {
      return;
    }
    event.preventDefault();
    const items = this.items();
    const current = items.indexOf(document.activeElement as HTMLElement);
    const delta = event.key === 'ArrowDown' ? 1 : -1;
    this.focusItem((current + delta + items.length) % items.length);
  }

  private computePosition(): PanelPosition {
    const rect = this.host.nativeElement.getBoundingClientRect();
    const viewportWidth = document.documentElement.clientWidth;
    const viewportHeight = document.documentElement.clientHeight;
    const vertical =
      this.direction() === 'up'
        ? { top: null, bottom: viewportHeight - rect.top + OFFSET }
        : { top: rect.bottom + OFFSET, bottom: null };
    const horizontal =
      this.align() === 'end'
        ? { left: null, right: Math.max(OFFSET, viewportWidth - rect.right) }
        : { left: Math.max(OFFSET, rect.left), right: null };
    return { ...vertical, ...horizontal };
  }

  private items(): HTMLElement[] {
    return Array.from(
      this.host.nativeElement.querySelectorAll<HTMLElement>('.dropdown__panel .menu-item:not([disabled])'),
    );
  }

  private focusItem(index: number): void {
    this.items()[index]?.focus();
  }
}
