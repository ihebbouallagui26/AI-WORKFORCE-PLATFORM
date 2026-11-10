import { ChangeDetectionStrategy, Component, effect, ElementRef, inject, input, Renderer2 } from '@angular/core';
import { IconName, ICONS } from './icon-registry';

const SVG_NS = 'http://www.w3.org/2000/svg';

/**
 * Decorative Lucide icon. The SVG is built with Renderer2 from the icon node data
 * (no innerHTML), which keeps it compatible with a strict Content Security Policy.
 * Interactive parents must carry their own accessible label.
 */
@Component({
  selector: 'ui-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'ui-icon', 'aria-hidden': 'true' },
  template: '',
  styles: `
    :host {
      display: inline-flex;
      flex-shrink: 0;
      line-height: 0;
    }
    :host(.is-spinning) {
      animation: ui-icon-spin 0.9s linear infinite;
    }
    @keyframes ui-icon-spin {
      to {
        transform: rotate(360deg);
      }
    }
  `,
})
export class Icon {
  readonly name = input.required<IconName>();
  readonly size = input<number>(16);
  readonly strokeWidth = input<number>(1.75);

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly renderer = inject(Renderer2);

  constructor() {
    effect(() => this.render(this.name(), this.size(), this.strokeWidth()));
  }

  private render(name: IconName, size: number, strokeWidth: number): void {
    const host = this.host.nativeElement;
    for (const child of Array.from(host.childNodes)) {
      this.renderer.removeChild(host, child);
    }

    const svg = this.renderer.createElement('svg', SVG_NS) as SVGElement;
    const attributes: Record<string, string> = {
      xmlns: SVG_NS,
      width: String(size),
      height: String(size),
      viewBox: '0 0 24 24',
      fill: 'none',
      stroke: 'currentColor',
      'stroke-width': String(strokeWidth),
      'stroke-linecap': 'round',
      'stroke-linejoin': 'round',
      focusable: 'false',
    };
    for (const [key, value] of Object.entries(attributes)) {
      this.renderer.setAttribute(svg, key, value);
    }

    for (const [tag, attrs] of ICONS[name]) {
      const element = this.renderer.createElement(tag, SVG_NS) as SVGElement;
      for (const [key, value] of Object.entries(attrs)) {
        if (value !== undefined && value !== null) {
          this.renderer.setAttribute(element, key, String(value));
        }
      }
      this.renderer.appendChild(svg, element);
    }
    this.renderer.appendChild(host, svg);
  }
}
