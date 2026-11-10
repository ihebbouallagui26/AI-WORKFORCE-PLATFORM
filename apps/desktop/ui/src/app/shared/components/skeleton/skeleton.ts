import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Loading placeholder rows. Announced to assistive technologies as a busy region. */
@Component({
  selector: 'ui-skeleton',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { role: 'status', 'aria-live': 'polite', 'aria-busy': 'true' },
  template: `
    <span class="sr-only">{{ label() }}</span>
    @for (row of rowList(); track row) {
      <span class="skeleton__row" [class.skeleton__row--short]="row % 3 === 2"></span>
    }
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
      padding: var(--space-4);
    }
    .skeleton__row {
      display: block;
      height: 14px;
      border-radius: var(--radius-sm);
      background: linear-gradient(90deg, var(--color-surface-2), var(--color-surface-3), var(--color-surface-2));
      background-size: 200% 100%;
      animation: skeleton-shimmer 1.4s ease-in-out infinite;
    }
    .skeleton__row--short {
      width: 60%;
    }
    @keyframes skeleton-shimmer {
      from { background-position: 100% 0; }
      to { background-position: -100% 0; }
    }
    @media (prefers-reduced-motion: reduce) {
      .skeleton__row { animation: none; }
    }
  `,
})
export class Skeleton {
  readonly rows = input(4);
  readonly label = input('Loading…');
  protected readonly rowList = computed(() => Array.from({ length: this.rows() }, (_, i) => i));
}
