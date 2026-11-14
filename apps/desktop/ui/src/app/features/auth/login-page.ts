import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { safeReturnUrl } from '../../core/auth/auth.guard';
import { SessionService } from '../../core/auth/session.service';
import type { LoginResult } from '../../core/desktop/desktop-api';
import { Icon } from '../../shared/components/icon/icon';

const FAILURES: Record<Exclude<LoginResult, { ok: true }>['reason'], string> = {
  cancelled: 'Sign-in was cancelled.',
  timeout: 'Sign-in timed out. Please try again.',
  denied: 'Sign-in was refused by the identity provider.',
  busy: 'A sign-in is already in progress in your browser.',
  unavailable: 'The identity provider is unreachable. Check that the local stack is running.',
  failed: 'Sign-in failed. Please try again.',
};

/**
 * Sign-in screen. Authentication happens in the system browser (Authorization Code + PKCE)
 * driven by the Electron main process: no password is ever typed into this application.
 */
@Component({
  selector: 'app-login-page',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="login">
      <section class="login__card" aria-labelledby="login-title">
        <div class="login__brand"><ui-icon name="shield-check" [size]="28" /></div>
        <h1 id="login-title">AI Workforce Platform</h1>

        @switch (session.status()) {
          @case ('unsupported') {
            <p class="login__text">Sign-in is only available in the desktop application.</p>
            <p class="login__hint">To preview the interface in a browser, run <code>ng serve --configuration mock</code>.</p>
          }
          @case ('signing-in') {
            <p class="login__text">Continue in your browser to sign in. This window updates automatically.</p>
            <button type="button" class="btn btn--secondary" (click)="cancel()">Cancel</button>
          }
          @default {
            <p class="login__text">Sign in with your organization account. Your browser will open the secure sign-in page.</p>
            @if (notice(); as message) {
              <p class="login__notice" role="status">{{ message }}</p>
            }
            <button type="button" class="btn btn--primary login__button" [disabled]="busy() || session.status() === 'loading'" (click)="signIn()">
              <ui-icon name="log-in" [size]="16" /> Sign in
            </button>
            <p class="login__hint">No account yet? Use “Register” on the sign-in page, then verify your e-mail address.</p>
          }
        }
      </section>
    </main>
  `,
  styles: `
    .login {
      display: grid;
      place-items: center;
      min-height: 100vh;
      padding: var(--space-6);
      background: var(--color-bg-app);
    }
    .login__card {
      width: min(420px, 100%);
      padding: var(--space-8) var(--space-6);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      background: var(--color-surface-1);
      text-align: center;
      display: flex;
      flex-direction: column;
      gap: var(--space-4);
      align-items: center;
    }
    .login__brand {
      color: var(--color-accent);
    }
    h1 {
      margin: 0;
      font-size: var(--text-xl);
    }
    .login__text {
      margin: 0;
      color: var(--color-text-secondary);
    }
    .login__notice {
      margin: 0;
      padding: var(--space-2) var(--space-3);
      border-radius: var(--radius-md);
      background: var(--color-surface-2);
      color: var(--color-text-primary);
    }
    .login__button {
      min-width: 180px;
      justify-content: center;
    }
    .login__hint {
      margin: 0;
      font-size: var(--text-sm);
      color: var(--color-text-tertiary);
    }
  `,
})
export class LoginPage {
  protected readonly session = inject(SessionService);
  private readonly router = inject(Router);

  /** Query parameter bound by the router. */
  readonly returnUrl = input<string | undefined>(undefined);

  protected readonly busy = signal(false);
  private readonly failure = signal<string | null>(null);
  protected readonly notice = computed(() => {
    if (this.failure()) {
      return this.failure();
    }
    return this.session.endReason() === 'expired' ? 'Your session has expired. Please sign in again.' : null;
  });

  constructor() {
    // Also covers a session restored or completed while this page is displayed.
    effect(() => {
      if (this.session.isAuthenticated()) {
        void this.router.navigateByUrl(safeReturnUrl(this.returnUrl()));
      }
    });
  }

  protected async signIn(): Promise<void> {
    this.busy.set(true);
    this.failure.set(null);
    try {
      const result = await this.session.login();
      if (!result.ok) {
        this.failure.set(FAILURES[result.reason]);
      }
    } finally {
      this.busy.set(false);
    }
  }

  protected cancel(): void {
    void this.session.cancelLogin();
  }
}
