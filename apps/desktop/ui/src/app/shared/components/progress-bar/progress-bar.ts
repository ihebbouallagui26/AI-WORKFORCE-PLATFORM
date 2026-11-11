import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Tone } from '../../utils/presentation';

@Component({
  selector: 'ui-progress-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    role: 'progressbar',
    'aria-valuemin': '0',
    'aria-valuemax': '100',
    '[attr.aria-valuenow]': 'clamped()',
    '[attr.aria-label]': 'label() || null',
    '[class]': '"progress progress--" + tone()',
  },
  template: `<span class="progress__fill" [style.width.%]="clamped()"></span>`,
  styles: `
    :host {
      --progress-color: var(--color-accent);
      display: block;
      width: 100%;
      height: 6px;
      overflow: hidden;
      border-radius: var(--radius-full);
      background: var(--color-surface-3);
    }
    :host(.progress--success) { --progress-color: var(--color-success); }
    :host(.progress--warning) { --progress-color: var(--color-warning); }
    :host(.progress--danger) { --progress-color: var(--color-danger); }
    :host(.progress--ai) { --progress-color: var(--color-ai); }
    :host(.progress--neutral) { --progress-color: var(--color-text-tertiary); }
    .progress__fill {
      display: block;
      height: 100%;
      border-radius: inherit;
      background: var(--progress-color);
      transition: width var(--duration-slow) var(--ease-standard);
    }
  `,
})
export class ProgressBar {
  readonly value = input.required<number>();
  readonly tone = input<Tone>('accent');
  readonly label = input<string>('');

  protected readonly clamped = computed(() => Math.max(0, Math.min(100, Math.round(this.value()))));
}
