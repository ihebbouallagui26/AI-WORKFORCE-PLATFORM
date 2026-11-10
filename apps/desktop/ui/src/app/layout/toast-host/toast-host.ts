import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ToastService, ToastTone } from '../../core/services/toast.service';
import { Icon } from '../../shared/components/icon/icon';
import { IconName } from '../../shared/components/icon/icon-registry';

const TONE_ICON: Record<ToastTone, IconName> = {
  success: 'circle-check',
  info: 'info',
  warning: 'warning',
  danger: 'circle-alert',
};

@Component({
  selector: 'app-toast-host',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="toasts" aria-live="polite" aria-label="Notifications">
      @for (toast of toastService.toasts(); track toast.id) {
        <div class="toast" [class]="'toast toast--' + toast.tone" [attr.role]="toast.tone === 'danger' ? 'alert' : 'status'">
          <ui-icon class="toast__icon" [name]="icons[toast.tone]" />
          <div class="toast__body">
            <p class="toast__title">{{ toast.title }}</p>
            @if (toast.message) {
              <p class="toast__message">{{ toast.message }}</p>
            }
          </div>
          <button type="button" class="btn btn--ghost btn--icon btn--sm" aria-label="Dismiss" (click)="toastService.dismiss(toast.id)">
            <ui-icon name="close" [size]="14" />
          </button>
        </div>
      }
    </section>
  `,
  styles: `
    .toasts {
      position: fixed;
      right: var(--space-5);
      bottom: var(--space-5);
      z-index: var(--z-toast);
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      width: min(380px, calc(100vw - 40px));
      pointer-events: none;
    }
    .toast {
      display: flex;
      align-items: flex-start;
      gap: var(--space-3);
      padding: var(--space-3) var(--space-2) var(--space-3) var(--space-4);
      border: 1px solid var(--color-border);
      border-left: 3px solid var(--toast-color, var(--color-info));
      border-radius: var(--radius-lg);
      background: var(--color-surface-2);
      box-shadow: var(--shadow-lg);
      pointer-events: auto;
      animation: toast-in var(--duration-base) var(--ease-standard);
    }
    .toast--success { --toast-color: var(--color-success); }
    .toast--warning { --toast-color: var(--color-warning); }
    .toast--danger { --toast-color: var(--color-danger); }
    .toast__icon {
      margin-top: 2px;
      color: var(--toast-color, var(--color-info));
    }
    .toast__body {
      flex: 1;
      min-width: 0;
    }
    .toast__title {
      font-weight: var(--weight-semibold);
    }
    .toast__message {
      margin-top: 2px;
      color: var(--color-text-secondary);
      font-size: var(--text-xs);
    }
    @keyframes toast-in {
      from { opacity: 0; transform: translateY(8px); }
    }
  `,
})
export class ToastHost {
  protected readonly toastService = inject(ToastService);
  protected readonly icons = TONE_ICON;
}
