import {
  ChangeDetectionStrategy,
  Component,
  effect,
  ElementRef,
  input,
  output,
  viewChild,
} from '@angular/core';
import { Icon } from '../icon/icon';

let nextModalId = 0;

/**
 * Modal built on the native `<dialog>` element: focus trapping, Escape handling and
 * top-layer rendering come from the platform. Project footer buttons with `[modalFooter]`.
 */
@Component({
  selector: 'ui-modal',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './modal.html',
  styleUrl: './modal.scss',
})
export class Modal {
  readonly open = input(false);
  readonly heading = input.required<string>();
  readonly description = input<string>('');
  readonly size = input<'sm' | 'md' | 'lg'>('md');
  /** Prevents closing via Escape or backdrop, e.g. while a request is in flight. */
  readonly locked = input(false);
  readonly closed = output<void>();

  protected readonly titleId = `ui-modal-title-${++nextModalId}`;
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
    effect(() => {
      const dialog = this.dialog().nativeElement;
      if (this.open() && !dialog.open) {
        dialog.showModal();
      } else if (!this.open() && dialog.open) {
        dialog.close();
      }
    });
  }

  protected requestClose(): void {
    if (!this.locked()) {
      this.closed.emit();
    }
  }

  protected onCancel(event: Event): void {
    // Keep the DOM state driven by the `open` input.
    event.preventDefault();
    this.requestClose();
  }

  protected onBackdropClick(event: MouseEvent): void {
    if (event.target === this.dialog().nativeElement) {
      this.requestClose();
    }
  }
}
