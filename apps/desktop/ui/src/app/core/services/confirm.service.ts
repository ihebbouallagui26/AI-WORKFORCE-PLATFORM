import { Injectable, signal } from '@angular/core';

export interface ConfirmOptions {
  readonly title: string;
  readonly message: string;
  readonly confirmLabel?: string;
  readonly cancelLabel?: string;
  readonly tone?: 'primary' | 'danger' | 'success';
  /** When set, the dialog shows an optional note field and returns its value. */
  readonly noteLabel?: string;
  readonly notePlaceholder?: string;
}

export interface ConfirmResult {
  readonly confirmed: boolean;
  readonly note: string | null;
}

interface PendingConfirm {
  readonly options: ConfirmOptions;
  readonly resolve: (result: ConfirmResult) => void;
}

/** Promise-based confirmation dialog, rendered once by the application shell. */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  readonly pending = signal<PendingConfirm | null>(null);

  ask(options: ConfirmOptions): Promise<ConfirmResult> {
    // Only one confirmation at a time: a new request cancels the previous one.
    this.pending()?.resolve({ confirmed: false, note: null });
    return new Promise<ConfirmResult>((resolve) => this.pending.set({ options, resolve }));
  }

  settle(result: ConfirmResult): void {
    const current = this.pending();
    this.pending.set(null);
    current?.resolve(result);
  }
}
