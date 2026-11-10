import { DOCUMENT } from '@angular/common';
import { DestroyRef, Directive, ElementRef, inject, input } from '@angular/core';

export type TooltipPlacement = 'top' | 'right' | 'bottom';

let nextTooltipId = 0;
const GAP = 8;

/** Lightweight tooltip shown on hover and keyboard focus. */
@Directive({
  selector: '[uiTooltip]',
  host: {
    '(mouseenter)': 'show()',
    '(mouseleave)': 'hide()',
    '(focusin)': 'show()',
    '(focusout)': 'hide()',
    '(keydown.escape)': 'hide()',
    '(click)': 'hide()',
  },
})
export class Tooltip {
  readonly text = input<string | null>(null, { alias: 'uiTooltip' });
  readonly placement = input<TooltipPlacement>('top', { alias: 'uiTooltipPlacement' });
  readonly disabled = input(false, { alias: 'uiTooltipDisabled' });

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly document = inject(DOCUMENT);
  private readonly id = `ui-tooltip-${++nextTooltipId}`;
  private element: HTMLElement | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.hide());
  }

  protected show(): void {
    const text = this.text();
    if (this.disabled() || !text || this.element) {
      return;
    }
    const tip = this.document.createElement('div');
    tip.className = `ui-tooltip ui-tooltip--${this.placement()}`;
    tip.id = this.id;
    tip.setAttribute('role', 'tooltip');
    tip.textContent = text;
    this.document.body.appendChild(tip);
    this.element = tip;
    this.host.nativeElement.setAttribute('aria-describedby', this.id);
    this.position(tip);
  }

  protected hide(): void {
    this.element?.remove();
    this.element = null;
    this.host.nativeElement.removeAttribute('aria-describedby');
  }

  private position(tip: HTMLElement): void {
    const anchor = this.host.nativeElement.getBoundingClientRect();
    const box = tip.getBoundingClientRect();
    let top: number;
    let left: number;
    switch (this.placement()) {
      case 'right':
        top = anchor.top + anchor.height / 2 - box.height / 2;
        left = anchor.right + GAP;
        break;
      case 'bottom':
        top = anchor.bottom + GAP;
        left = anchor.left + anchor.width / 2 - box.width / 2;
        break;
      default:
        top = anchor.top - box.height - GAP;
        left = anchor.left + anchor.width / 2 - box.width / 2;
    }
    const maxLeft = this.document.documentElement.clientWidth - box.width - GAP;
    tip.style.top = `${Math.max(GAP, top)}px`;
    tip.style.left = `${Math.min(Math.max(GAP, left), maxLeft)}px`;
  }
}
