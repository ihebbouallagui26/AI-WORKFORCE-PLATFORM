/**
 * Desktop authentication session, owned by the main process.
 *
 * - Login: Authorization Code + PKCE (S256) in the system browser, with random state, nonce and
 *   verifier, a temporary loopback callback server, and the code exchange done here.
 * - Tokens: the access token stays in this process's memory; the refresh token is kept by the
 *   {@link TokenStore} (OS-encrypted, or memory only). Neither ever reaches the renderer.
 * - Refresh: single-flight, before expiry; refresh tokens are rotated by the provider.
 * - Logout: refresh token revoked at the provider, local state wiped, provider session ended.
 *
 * Protocol details are delegated to an {@link OidcProvider} (openid-client in production).
 */
import { startLoopbackServer, type LoopbackServer } from './loopback-server';
import type { StorageMode, TokenStore } from './token-store';

export interface TokenSet {
  readonly accessToken: string;
  /** Epoch milliseconds. */
  readonly expiresAt: number;
  readonly refreshToken?: string;
  readonly idToken?: string;
  readonly claims?: UserClaims;
}

export interface UserClaims {
  readonly sub: string;
  readonly email?: string;
  readonly emailVerified?: boolean;
  readonly name?: string;
}

export interface PkceChecks {
  readonly verifier: string;
  readonly state: string;
  readonly nonce: string;
}

/** Protocol operations (implemented with openid-client). */
export interface OidcProvider {
  newChecks(): Promise<PkceChecks>;
  authorizationUrl(redirectUri: string, checks: PkceChecks): Promise<URL>;
  /** Validates the response (state, issuer, ID token signature/nonce) and redeems the code. */
  exchangeCode(callbackUrl: URL, checks: PkceChecks): Promise<TokenSet>;
  refresh(refreshToken: string): Promise<TokenSet>;
  revoke(refreshToken: string): Promise<void>;
  endSessionUrl(idTokenHint: string | undefined): Promise<URL | null>;
}

export interface AuthUser {
  readonly subject: string;
  readonly email: string | null;
  readonly emailVerified: boolean;
  readonly name: string | null;
}

/** What the renderer may know about the session. Never contains tokens. */
export interface AuthSessionState {
  readonly status: 'signed-out' | 'signing-in' | 'signed-in';
  readonly user: AuthUser | null;
  readonly storage: StorageMode;
  /** Why the last session ended, when it was not an explicit logout. */
  readonly reason: 'expired' | 'error' | null;
}

export type LoginResult =
  | { readonly ok: true }
  | { readonly ok: false; readonly reason: 'cancelled' | 'timeout' | 'denied' | 'busy' | 'unavailable' | 'failed' };

export interface AuthSessionOptions {
  readonly provider: OidcProvider;
  readonly store: TokenStore;
  /** Opens a URL of the identity provider in the system browser. */
  readonly openBrowser: (url: URL) => Promise<void>;
  readonly loginTimeoutMs?: number;
  readonly now?: () => number;
  /** Refresh the access token when it expires within this delay. */
  readonly refreshSkewMs?: number;
  readonly startLoopback?: typeof startLoopbackServer;
}

interface ActiveSession {
  accessToken: string;
  expiresAt: number;
  refreshToken: string | null;
  idToken: string | undefined;
  user: AuthUser;
}

export class AuthSession {
  private active: ActiveSession | null = null;
  private signingIn: { promise: Promise<LoginResult>; server: LoopbackServer | null } | null = null;
  private refreshing: Promise<string | null> | null = null;
  private reason: AuthSessionState['reason'] = null;
  private readonly listeners = new Set<(state: AuthSessionState) => void>();
  private readonly now: () => number;
  private readonly skew: number;
  private readonly loginTimeout: number;
  private readonly startLoopback: typeof startLoopbackServer;

  constructor(private readonly options: AuthSessionOptions) {
    this.now = options.now ?? Date.now;
    this.skew = options.refreshSkewMs ?? 30_000;
    this.loginTimeout = options.loginTimeoutMs ?? 5 * 60_000;
    this.startLoopback = options.startLoopback ?? startLoopbackServer;
  }

  state(): AuthSessionState {
    return {
      status: this.active ? 'signed-in' : this.signingIn ? 'signing-in' : 'signed-out',
      user: this.active?.user ?? null,
      storage: this.options.store.mode,
      reason: this.active ? null : this.reason,
    };
  }

  onChange(listener: (state: AuthSessionState) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /** Restores a previous session from the stored refresh token (app start). */
  async restore(): Promise<AuthSessionState> {
    const stored = this.options.store.load();
    if (stored && !this.active) {
      try {
        this.establish(await this.options.provider.refresh(stored), stored);
      } catch {
        this.options.store.clear();
      }
      this.emit();
    }
    return this.state();
  }

  login(): Promise<LoginResult> {
    if (this.active) {
      return Promise.resolve({ ok: true });
    }
    if (this.signingIn) {
      return Promise.resolve({ ok: false, reason: 'busy' });
    }
    const attempt: { promise: Promise<LoginResult>; server: LoopbackServer | null } = { promise: Promise.resolve({ ok: true }), server: null };
    this.signingIn = attempt;
    attempt.promise = this.runLogin(attempt).finally(() => {
      this.signingIn = null;
      this.emit();
    });
    this.emit();
    return attempt.promise;
  }

  /** Abandons a pending login (e.g. the user closed the browser tab). */
  cancelLogin(): void {
    this.signingIn?.server?.close();
  }

  private async runLogin(attempt: { server: LoopbackServer | null }): Promise<LoginResult> {
    const { provider } = this.options;
    let checks: PkceChecks;
    let server: LoopbackServer;
    let authorizationUrl: URL;
    try {
      checks = await provider.newChecks();
      server = await this.startLoopback(checks.state, this.loginTimeout);
      attempt.server = server;
      authorizationUrl = await provider.authorizationUrl(server.redirectUri, checks);
    } catch {
      attempt.server?.close();
      return { ok: false, reason: 'unavailable' };
    }
    try {
      await this.options.openBrowser(authorizationUrl);
      const callback = await server.result;
      if (callback.kind === 'provider-error') {
        return { ok: false, reason: callback.error === 'access_denied' ? 'denied' : 'failed' };
      }
      const tokens = await provider.exchangeCode(callback.url, checks);
      this.reason = null;
      this.establish(tokens, null);
      return { ok: true };
    } catch (error) {
      const message = error instanceof Error ? error.message : '';
      if (message === 'login-timeout') {
        return { ok: false, reason: 'timeout' };
      }
      if (message === 'login-cancelled') {
        return { ok: false, reason: 'cancelled' };
      }
      return { ok: false, reason: 'failed' };
    } finally {
      server.close();
    }
  }

  /**
   * A valid access token for an API call, refreshed if it is about to expire; null when signed
   * out or when the session could not be renewed.
   */
  async accessToken(forceRefresh = false): Promise<string | null> {
    const active = this.active;
    if (!active) {
      return null;
    }
    if (!forceRefresh && active.expiresAt - this.now() > this.skew) {
      return active.accessToken;
    }
    return this.refreshOnce();
  }

  private refreshOnce(): Promise<string | null> {
    if (!this.refreshing) {
      this.refreshing = this.doRefresh().finally(() => (this.refreshing = null));
    }
    return this.refreshing;
  }

  private async doRefresh(): Promise<string | null> {
    const active = this.active;
    if (!active?.refreshToken) {
      this.end('expired');
      return null;
    }
    try {
      this.establish(await this.options.provider.refresh(active.refreshToken), active.refreshToken, active);
      return this.active?.accessToken ?? null;
    } catch {
      // Refresh token expired, revoked or reused: the session is over.
      this.end('expired');
      return null;
    }
  }

  /** Called when the API rejects a token that looked valid (e.g. session revoked). */
  async handleUnauthorized(): Promise<string | null> {
    return this.accessToken(true);
  }

  async logout(): Promise<void> {
    const active = this.active;
    this.cancelLogin();
    this.end(null);
    if (!active) {
      return;
    }
    const { provider } = this.options;
    if (active.refreshToken) {
      await provider.revoke(active.refreshToken).catch(() => undefined);
    }
    // Ends the provider's browser session too, so the next login asks for credentials again.
    try {
      const endSession = await provider.endSessionUrl(active.idToken);
      if (endSession) {
        await this.options.openBrowser(endSession);
      }
    } catch {
      // Best effort: the refresh token is already revoked and local state wiped.
    }
  }

  private establish(tokens: TokenSet, previousRefreshToken: string | null, previous?: ActiveSession): void {
    const refreshToken = tokens.refreshToken ?? previousRefreshToken;
    const claims = tokens.claims;
    const user: AuthUser | undefined = claims
      ? { subject: claims.sub, email: claims.email ?? null, emailVerified: claims.emailVerified === true, name: claims.name ?? null }
      : previous?.user;
    if (!user) {
      throw new Error('missing-identity');
    }
    this.active = {
      accessToken: tokens.accessToken,
      expiresAt: tokens.expiresAt,
      refreshToken,
      idToken: tokens.idToken ?? previous?.idToken,
      user,
    };
    if (refreshToken) {
      this.options.store.save(refreshToken);
    }
    this.emit();
  }

  private end(reason: AuthSessionState['reason']): void {
    const wasActive = this.active !== null;
    this.active = null;
    this.reason = reason;
    this.options.store.clear();
    if (wasActive) {
      this.emit();
    }
  }

  private emit(): void {
    const state = this.state();
    for (const listener of this.listeners) {
      listener(state);
    }
  }
}
