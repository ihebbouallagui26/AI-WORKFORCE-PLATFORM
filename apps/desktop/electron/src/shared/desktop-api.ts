/**
 * Contract between the Electron main process, the preload script and the renderer.
 * Mirrored on the renderer side in `ui/src/app/core/desktop/desktop-api.ts`.
 * Only type declarations live here: the sandboxed preload cannot load local modules.
 *
 * Security: no type in this file carries a token. Authentication is performed and held by the
 * main process; the renderer only sees the session state and asks for allowlisted API calls.
 */

export interface DesktopAppInfo {
  readonly name: string;
  readonly version: string;
  readonly platform: string;
  readonly arch: string;
  readonly electronVersion: string;
  readonly chromeVersion: string;
  readonly isPackaged: boolean;
}

export type OpenExternalResult =
  | { readonly ok: true }
  | { readonly ok: false; readonly reason: 'invalid-url' | 'not-allowed' | 'failed' };

export interface AuthUser {
  readonly subject: string;
  readonly email: string | null;
  readonly emailVerified: boolean;
  readonly name: string | null;
}

export interface AuthSessionState {
  readonly status: 'signed-out' | 'signing-in' | 'signed-in';
  readonly user: AuthUser | null;
  /** 'secure': refresh token protected by the OS; 'session-only': kept in memory until exit. */
  readonly storage: 'secure' | 'session-only';
  readonly reason: 'expired' | 'error' | null;
}

export type LoginResult =
  | { readonly ok: true }
  | { readonly ok: false; readonly reason: 'cancelled' | 'timeout' | 'denied' | 'busy' | 'unavailable' | 'failed' };

export type ApiMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface ApiRequest {
  readonly method: ApiMethod;
  /** API path, e.g. `/api/v1/organizations`. Only allowlisted operations are executed. */
  readonly path: string;
  readonly query?: Readonly<Record<string, string | number | boolean>>;
  readonly body?: unknown;
}

export interface ApiResponse {
  readonly status: number;
  readonly body: unknown;
  readonly error?: 'invalid-request' | 'not-allowed' | 'unauthenticated' | 'network' | 'timeout' | 'too-large';
}

export interface DesktopAuthBridge {
  getSession(): Promise<AuthSessionState>;
  login(): Promise<LoginResult>;
  cancelLogin(): Promise<void>;
  logout(): Promise<void>;
  /** Subscribes to session changes; returns an unsubscribe function. */
  onSessionChanged(listener: (state: AuthSessionState) => void): () => void;
}

export interface DesktopApiBridge {
  request(request: ApiRequest): Promise<ApiResponse>;
}

export interface DesktopBridge {
  getAppInfo(): Promise<DesktopAppInfo>;
  openExternal(url: string): Promise<OpenExternalResult>;
  readonly auth: DesktopAuthBridge;
  readonly api: DesktopApiBridge;
}

/** Every IPC channel the renderer may invoke. Nothing else is reachable. */
export type IpcChannel =
  | 'app:get-info'
  | 'shell:open-external'
  | 'auth:get-session'
  | 'auth:login'
  | 'auth:cancel-login'
  | 'auth:logout'
  | 'api:request';

/** Events sent by the main process to the renderer. */
export type IpcEvent = 'auth:session-changed';
