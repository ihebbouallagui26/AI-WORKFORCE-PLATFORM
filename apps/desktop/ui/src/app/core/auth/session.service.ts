import { computed, DestroyRef, inject, Injectable, InjectionToken, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import type { AuthSessionState, DesktopBridge, LoginResult } from '../desktop/desktop-api';

/** Whether this build authenticates against the identity provider (`api` data source). */
export const AUTH_REQUIRED = new InjectionToken<boolean>('AUTH_REQUIRED', { factory: () => false });

export type SessionStatus = AuthSessionState['status'] | 'loading' | 'unsupported';

/**
 * Renderer view of the authentication session. The Electron main process performs the login
 * and keeps the tokens; this service only mirrors the session state (user identity, status)
 * and forwards login/logout requests. It never sees an access or refresh token.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly bridge: DesktopBridge | undefined = inject(DOCUMENT).defaultView?.desktop;
  readonly required = inject(AUTH_REQUIRED);

  private readonly state = signal<AuthSessionState | null>(null);
  private readonly initialized = signal(false);

  readonly status = computed<SessionStatus>(() => {
    if (!this.required) {
      return 'signed-in';
    }
    if (!this.bridge) {
      return 'unsupported';
    }
    return this.initialized() ? (this.state()?.status ?? 'signed-out') : 'loading';
  });
  readonly user = computed(() => this.state()?.user ?? null);
  readonly isAuthenticated = computed(() => this.status() === 'signed-in');
  readonly storage = computed(() => this.state()?.storage ?? 'session-only');
  /** Set when the previous session ended on its own (expired or revoked). */
  readonly endReason = computed(() => this.state()?.reason ?? null);

  private ready: Promise<void> | null = null;

  constructor() {
    if (this.required && this.bridge) {
      const unsubscribe = this.bridge.auth.onSessionChanged((next) => {
        this.state.set(next);
        this.initialized.set(true);
      });
      inject(DestroyRef).onDestroy(unsubscribe);
    }
  }

  /** Resolves once the initial session state is known. */
  whenReady(): Promise<void> {
    if (!this.required || !this.bridge) {
      return Promise.resolve();
    }
    this.ready ??= this.bridge.auth
      .getSession()
      .then((current) => {
        if (!this.initialized()) {
          this.state.set(current);
          this.initialized.set(true);
        }
      })
      .catch(() => {
        this.initialized.set(true);
      });
    return this.ready;
  }

  async login(): Promise<LoginResult> {
    if (!this.bridge) {
      return { ok: false, reason: 'unavailable' };
    }
    const result = await this.bridge.auth.login();
    this.state.set(await this.bridge.auth.getSession());
    this.initialized.set(true);
    return result;
  }

  cancelLogin(): Promise<void> {
    return this.bridge?.auth.cancelLogin() ?? Promise.resolve();
  }

  async logout(): Promise<void> {
    if (!this.bridge) {
      return;
    }
    await this.bridge.auth.logout();
    this.state.set(await this.bridge.auth.getSession());
  }

  /** Called when the API reports that no valid session exists any more. */
  async refreshState(): Promise<void> {
    if (this.bridge) {
      this.state.set(await this.bridge.auth.getSession());
      this.initialized.set(true);
    }
  }
}
