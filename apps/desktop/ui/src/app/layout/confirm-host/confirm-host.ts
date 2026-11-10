import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { ConfirmService } from '../../core/services/confirm.service';
import { Modal } from '../../shared/components/modal/modal';

/** Renders the confirmation requested through {@link ConfirmService}. */
@Component({
  selector: 'app-confirm-host',
  imports: [Modal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-modal size="sm" [open]="!!options()" [heading]="options()?.title ?? ''" (closed)="cancel()">
      @if (options(); as o) {
        <p class="confirm__message">{{ o.message }}</p>
        @if (o.noteLabel) {
          <div class="field confirm__note">
            <label class="field__label" for="confirm-note">{{ o.noteLabel }}</label>
            <textarea
              #noteField
              id="confirm-note"
              class="textarea"
              rows="3"
              maxlength="500"
              [placeholder]="o.notePlaceholder ?? ''"
              [value]="note()"
              (input)="note.set(noteField.value)"
            ></textarea>
          </div>
        }
      }
      <ng-container modalFooter>
        <button type="button" class="btn btn--secondary" (click)="cancel()">{{ options()?.cancelLabel ?? 'Cancel' }}</button>
        <button type="button" [class]="'btn btn--' + (options()?.tone ?? 'primary')" (click)="confirm()">
          {{ options()?.confirmLabel ?? 'Confirm' }}
        </button>
      </ng-container>
    </ui-modal>
  `,
  styles: `
    .confirm__message {
      color: var(--color-text-secondary);
    }
    .confirm__note {
      margin-top: var(--space-4);
    }
  `,
})
export class ConfirmHost {
  private readonly confirmService = inject(ConfirmService);
  protected readonly options = computed(() => this.confirmService.pending()?.options ?? null);
  protected readonly note = signal('');

  constructor() {
    effect(() => {
      if (this.options()) {
        this.note.set('');
      }
    });
  }

  protected confirm(): void {
    this.confirmService.settle({ confirmed: true, note: this.note().trim() || null });
  }

  protected cancel(): void {
    this.confirmService.settle({ confirmed: false, note: null });
  }
}
