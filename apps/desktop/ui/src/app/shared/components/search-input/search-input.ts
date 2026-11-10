import { ChangeDetectionStrategy, Component, ElementRef, input, model, viewChild } from '@angular/core';
import { Icon } from '../icon/icon';

@Component({
  selector: 'ui-search-input',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-icon class="search__icon" name="search" [size]="15" />
    <input
      #field
      type="search"
      class="input search__field"
      [placeholder]="placeholder()"
      [attr.aria-label]="label()"
      [value]="value()"
      (input)="value.set(field.value)"
      (keydown.escape)="clear()"
    />
    @if (value()) {
      <button type="button" class="search__clear" aria-label="Clear search" (click)="clear(); field.focus()">
        <ui-icon name="close" [size]="14" />
      </button>
    } @else if (shortcut()) {
      <kbd class="kbd search__kbd" aria-hidden="true">{{ shortcut() }}</kbd>
    }
  `,
  styles: `
    :host {
      position: relative;
      display: flex;
      align-items: center;
      min-width: 0;
    }
    .search__icon {
      position: absolute;
      left: 10px;
      color: var(--color-text-tertiary);
      pointer-events: none;
    }
    .search__field {
      padding-left: 32px;
      padding-right: 32px;
    }
    .search__field::-webkit-search-cancel-button {
      display: none;
    }
    .search__clear {
      position: absolute;
      right: 6px;
      display: inline-flex;
      padding: 4px;
      border: 0;
      border-radius: var(--radius-sm);
      background: none;
      color: var(--color-text-tertiary);
      cursor: pointer;
    }
    .search__clear:hover {
      color: var(--color-text-primary);
      background: var(--color-surface-3);
    }
    .search__kbd {
      position: absolute;
      right: 8px;
      pointer-events: none;
    }
  `,
})
export class SearchInput {
  readonly value = model('');
  readonly placeholder = input('Search');
  readonly label = input('Search');
  readonly shortcut = input<string>('');

  private readonly field = viewChild.required<ElementRef<HTMLInputElement>>('field');

  focus(): void {
    this.field().nativeElement.focus();
    this.field().nativeElement.select();
  }

  protected clear(): void {
    this.value.set('');
  }
}
