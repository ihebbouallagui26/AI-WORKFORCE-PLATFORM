import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'ui-page-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-header__titles">
      <h1 class="page-header__title">{{ heading() }}</h1>
      @if (description()) {
        <p class="page-header__description">{{ description() }}</p>
      }
    </div>
    <div class="page-header__actions"><ng-content /></div>
  `,
  styles: `
    :host {
      display: flex;
      align-items: flex-end;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: var(--space-4);
    }
    .page-header__title {
      font-size: var(--text-2xl);
      letter-spacing: -0.02em;
    }
    .page-header__description {
      margin-top: var(--space-1);
      max-width: 640px;
      color: var(--color-text-tertiary);
    }
    .page-header__actions {
      display: flex;
      align-items: center;
      gap: var(--space-2);
    }
  `,
})
export class PageHeader {
  readonly heading = input.required<string>();
  readonly description = input<string>('');
}
