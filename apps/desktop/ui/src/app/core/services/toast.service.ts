import { Injectable, signal } from '@angular/core';

export type ToastTone = 'success' | 'info' | 'warning' | 'danger';

export interface Toast {
  readonly id: number;
  readonly tone: ToastTone;
  readonly title: string;
  readonly message: string | null;
}

const DEFAULT_DURATION_MS = 4500;
const MAX_VISIBLE = 4;

@Injectable({ providedIn: 'root' })
export class ToastService {
  private sequence = 0;
  private readonly timers = new Map<number, ReturnType<typeof setTimeout>>();
  readonly toasts = signal<readonly Toast[]>([]);

  show(tone: ToastTone, title: string, message: string | null = null, durationMs = DEFAULT_DURATION_MS): number {
    const id = ++this.sequence;
    this.toasts.update((list) => [...list, { id, tone, title, message }].slice(-MAX_VISIBLE));
    this.timers.set(
      id,
      setTimeout(() => this.dismiss(id), durationMs),
    );
    return id;
  }

  success(title: string, message: string | null = null): number {
    return this.show('success', title, message);
  }

  info(title: string, message: string | null = null): number {
    return this.show('info', title, message);
  }

  error(title: string, error: unknown): number {
    const message = error instanceof Error ? error.message : typeof error === 'string' ? error : null;
    return this.show('danger', title, message, 7000);
  }

  dismiss(id: number): void {
    clearTimeout(this.timers.get(id));
    this.timers.delete(id);
    this.toasts.update((list) => list.filter((t) => t.id !== id));
  }
}
