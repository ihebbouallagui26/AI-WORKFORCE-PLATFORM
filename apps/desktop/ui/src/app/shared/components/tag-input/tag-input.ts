import { ChangeDetectionStrategy, Component, forwardRef, input, signal } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { Icon } from '../icon/icon';

/** Form control editing a list of short strings (skills, responsibilities, labels). */
@Component({
  selector: 'ui-tag-input',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => TagInput), multi: true }],
  template: `
    <div class="tags" [class.is-disabled]="disabled()" (click)="field.focus()">
      @for (tag of tags(); track tag; let i = $index) {
        <span class="chip chip--removable">
          {{ tag }}
          <button
            type="button"
            class="chip__remove"
            [attr.aria-label]="'Remove ' + tag"
            [disabled]="disabled()"
            (click)="remove(i); $event.stopPropagation()"
          >
            <ui-icon name="close" [size]="12" />
          </button>
        </span>
      }
      <input
        #field
        class="tags__field"
        [id]="inputId()"
        [placeholder]="tags().length ? '' : placeholder()"
        [disabled]="disabled()"
        [attr.aria-describedby]="describedBy()"
        (keydown)="onKeydown($event, field)"
        (blur)="commit(field); onTouched()"
      />
    </div>
  `,
  styles: `
    .tags {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-1-5);
      min-height: 34px;
      padding: 5px var(--space-2);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: var(--color-bg-app);
      cursor: text;
    }
    .tags:focus-within {
      border-color: var(--color-accent);
      box-shadow: 0 0 0 3px var(--color-accent-subtle);
    }
    .tags.is-disabled {
      opacity: 0.5;
    }
    .tags__field {
      flex: 1;
      min-width: 120px;
      height: 22px;
      border: 0;
      outline: none;
      background: none;
      color: var(--color-text-primary);
      font: inherit;
    }
    .tags__field::placeholder {
      color: var(--color-text-disabled);
    }
  `,
})
export class TagInput implements ControlValueAccessor {
  readonly placeholder = input('Type and press Enter');
  readonly inputId = input<string | null>(null);
  readonly describedBy = input<string | null>(null);
  readonly maxLength = input(60);

  protected readonly tags = signal<readonly string[]>([]);
  protected readonly disabled = signal(false);

  private onChange: (value: readonly string[]) => void = () => undefined;
  protected onTouched: () => void = () => undefined;

  writeValue(value: readonly string[] | null): void {
    this.tags.set(value ?? []);
  }

  registerOnChange(fn: (value: readonly string[]) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled.set(isDisabled);
  }

  protected onKeydown(event: KeyboardEvent, field: HTMLInputElement): void {
    if (event.key === 'Enter' || event.key === ',') {
      event.preventDefault();
      this.commit(field);
    } else if (event.key === 'Backspace' && !field.value && this.tags().length) {
      this.remove(this.tags().length - 1);
    }
  }

  protected commit(field: HTMLInputElement): void {
    const value = field.value.trim().slice(0, this.maxLength());
    field.value = '';
    if (!value || this.tags().some((t) => t.toLowerCase() === value.toLowerCase())) {
      return;
    }
    this.update([...this.tags(), value]);
  }

  protected remove(index: number): void {
    this.update(this.tags().filter((_, i) => i !== index));
  }

  private update(next: readonly string[]): void {
    this.tags.set(next);
    this.onChange(next);
  }
}
