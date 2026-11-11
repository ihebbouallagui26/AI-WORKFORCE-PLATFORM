import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Presentation, Tone } from '../../utils/presentation';

/** Status indicator: coloured dot + label, always readable without colour. */
@Component({
  selector: 'ui-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class]': 'classes()' },
  template: `
    @if (dot()) {
      <span class="badge__dot" aria-hidden="true"></span>
    }
    <span class="badge__label">{{ text() }}</span>
  `,
  styles: `
    :host {
      --badge-color: var(--color-neutral);
      --badge-bg: var(--color-neutral-subtle);
      display: inline-flex;
      align-items: center;
      gap: 6px;
      height: 22px;
      padding: 0 8px;
      border-radius: var(--radius-full);
      background: var(--badge-bg);
      color: var(--badge-color);
      font-size: var(--text-xs);
      font-weight: var(--weight-medium);
      line-height: 1;
      white-space: nowrap;
    }
    :host(.badge--plain) {
      height: auto;
      padding: 0;
      background: none;
    }
    .badge__dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: currentColor;
    }
    :host(.badge--accent) { --badge-color: var(--color-accent-text); --badge-bg: var(--color-accent-subtle); }
    :host(.badge--success) { --badge-color: var(--color-success); --badge-bg: var(--color-success-subtle); }
    :host(.badge--warning) { --badge-color: var(--color-warning); --badge-bg: var(--color-warning-subtle); }
    :host(.badge--danger) { --badge-color: var(--color-danger-hover); --badge-bg: var(--color-danger-subtle); }
    :host(.badge--info) { --badge-color: var(--color-info); --badge-bg: var(--color-info-subtle); }
    :host(.badge--ai) { --badge-color: var(--color-ai); --badge-bg: var(--color-ai-subtle); }
    :host(.badge--neutral) { --badge-color: var(--color-text-secondary); }
  `,
})
export class Badge {
  /** Either pass a presentation object, or `label` + `tone`. */
  readonly presentation = input<Presentation | null>(null);
  readonly label = input<string>('');
  readonly tone = input<Tone>('neutral');
  readonly dot = input(true);
  readonly plain = input(false);

  protected readonly text = computed(() => this.presentation()?.label ?? this.label());
  protected readonly classes = computed(() => {
    const tone = this.presentation()?.tone ?? this.tone();
    return `badge badge--${tone}${this.plain() ? ' badge--plain' : ''}`;
  });
}
