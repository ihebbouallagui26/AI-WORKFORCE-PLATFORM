import { ChangeDetectionStrategy, Component, ElementRef, input, output, signal, viewChild } from '@angular/core';
import { Icon } from '../../../shared/components/icon/icon';
import { Tooltip } from '../../../shared/directives/tooltip';

const MAX_LENGTH = 4000;

@Component({
  selector: 'app-message-composer',
  imports: [Icon, Tooltip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form class="composer" (submit)="$event.preventDefault(); submit()">
      <label class="sr-only" for="composer-input">Message {{ recipient() }}</label>
      <textarea
        #field
        id="composer-input"
        class="composer__field"
        rows="1"
        [maxLength]="maxLength"
        [placeholder]="'Message ' + recipient()"
        [disabled]="disabled()"
        [value]="draft()"
        (input)="onInput(field)"
        (keydown.enter)="onEnter($event)"
      ></textarea>
      <div class="composer__bar">
        <button type="button" class="btn btn--ghost btn--icon btn--sm" aria-disabled="true" aria-label="Attach file (coming soon)" uiTooltip="Attachments — coming in a later phase">
          <ui-icon name="attachment" [size]="15" />
        </button>
        <button type="button" class="btn btn--ghost btn--icon btn--sm" aria-disabled="true" aria-label="Voice message (coming soon)" uiTooltip="Voice messages — coming in a later phase">
          <ui-icon name="mic" [size]="15" />
        </button>
        <span class="composer__hint">
          <kbd class="kbd">Enter</kbd> to send · <kbd class="kbd">Shift</kbd> + <kbd class="kbd">Enter</kbd> for a new line
        </span>
        <button type="submit" class="btn btn--primary btn--sm" [disabled]="disabled() || sending() || !draft().trim()">
          <ui-icon name="send" [size]="14" /> Send
        </button>
      </div>
    </form>
  `,
  styles: `
    .composer {
      margin: 0 var(--space-6) var(--space-4);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      background: var(--color-surface-1);
      transition: border-color var(--duration-fast) var(--ease-standard);
    }
    .composer:focus-within {
      border-color: var(--color-accent);
      box-shadow: 0 0 0 3px var(--color-accent-subtle);
    }
    .composer__field {
      display: block;
      width: 100%;
      min-height: 44px;
      max-height: 180px;
      padding: var(--space-3) var(--space-3) var(--space-1);
      border: 0;
      outline: none;
      resize: none;
      background: none;
      color: var(--color-text-primary);
      font: inherit;
      line-height: 1.5;
    }
    .composer__field::placeholder {
      color: var(--color-text-disabled);
    }
    .composer__bar {
      display: flex;
      align-items: center;
      gap: var(--space-1);
      padding: var(--space-1) var(--space-2) var(--space-2);
    }
    .composer__hint {
      margin-left: auto;
      margin-right: var(--space-2);
      color: var(--color-text-tertiary);
      font-size: var(--text-2xs);
    }
  `,
})
export class MessageComposer {
  readonly recipient = input('');
  readonly disabled = input(false);
  readonly sending = input(false);
  readonly send = output<string>();

  protected readonly maxLength = MAX_LENGTH;
  protected readonly draft = signal('');
  private readonly field = viewChild.required<ElementRef<HTMLTextAreaElement>>('field');

  /** Clears the draft once the parent confirms the message was stored. */
  reset(): void {
    this.draft.set('');
    const element = this.field().nativeElement;
    element.value = '';
    element.style.height = 'auto';
    element.focus();
  }

  focus(): void {
    this.field().nativeElement.focus();
  }

  protected onInput(element: HTMLTextAreaElement): void {
    this.draft.set(element.value);
    element.style.height = 'auto';
    element.style.height = `${Math.min(element.scrollHeight, 180)}px`;
  }

  protected onEnter(event: Event): void {
    if ((event as KeyboardEvent).shiftKey) {
      return;
    }
    event.preventDefault();
    this.submit();
  }

  protected submit(): void {
    const text = this.draft().trim();
    if (text && !this.disabled() && !this.sending()) {
      this.send.emit(text);
    }
  }
}
