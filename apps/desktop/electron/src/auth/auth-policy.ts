/**
 * Authentication policy as pure functions (no Electron imports), unit-tested with `node --test`.
 * Decides which identity provider and API are trusted, what a valid loopback callback is, and
 * which API calls the renderer may ask the main process to perform on its behalf.
 */
import { timingSafeEqual } from 'node:crypto';

export interface AuthConfig {
  /** OpenID Connect issuer (discovery at `${issuer}/.well-known/openid-configuration`). */
  readonly issuer: URL;
  /** Public client: no secret exists, PKCE S256 protects the code exchange. */
  readonly clientId: string;
  readonly scope: string;
  /** Core API base URL (origin only). */
  readonly apiBaseUrl: URL;
}

export const DEFAULT_ISSUER = 'http://localhost:8082/realms/ai-workforce';
export const DEFAULT_CLIENT_ID = 'ai-workforce-desktop';
export const DEFAULT_API_BASE_URL = 'http://localhost:8081';
export const DEFAULT_SCOPE = 'openid profile email';

/** Loopback redirect (RFC 8252 §7.3): fixed host and path, ephemeral port chosen at login. */
export const CALLBACK_HOST = '127.0.0.1';
export const CALLBACK_PATH = '/callback';

const CLIENT_ID_PATTERN = /^[A-Za-z0-9._-]{1,128}$/;

export function isLoopbackHost(hostname: string): boolean {
  return hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '[::1]';
}

/** HTTPS everywhere, plain HTTP only towards the local machine (development stack). */
export function isTrustedServiceUrl(url: URL): boolean {
  if (url.username || url.password || url.hash) {
    return false;
  }
  return url.protocol === 'https:' || (url.protocol === 'http:' && isLoopbackHost(url.hostname));
}

export type AuthConfigResult = { readonly ok: true; readonly config: AuthConfig } | { readonly ok: false; readonly error: string };

/**
 * Reads the configuration from environment variables, with defaults for the local stack.
 * Nothing secret is configured here: the desktop client is public.
 */
export function parseAuthConfig(env: Readonly<Record<string, string | undefined>>): AuthConfigResult {
  const issuerValue = (env['AIW_OIDC_ISSUER'] ?? DEFAULT_ISSUER).replace(/\/+$/, '');
  const clientId = env['AIW_OIDC_CLIENT_ID'] ?? DEFAULT_CLIENT_ID;
  const apiValue = env['AIW_API_BASE_URL'] ?? DEFAULT_API_BASE_URL;

  let issuer: URL;
  let apiBaseUrl: URL;
  try {
    issuer = new URL(issuerValue);
    apiBaseUrl = new URL(apiValue);
  } catch {
    return { ok: false, error: 'Invalid identity provider or API URL.' };
  }
  if (!isTrustedServiceUrl(issuer) || issuer.search) {
    return { ok: false, error: 'The identity provider must use HTTPS (HTTP is allowed for localhost only).' };
  }
  if (!isTrustedServiceUrl(apiBaseUrl) || apiBaseUrl.search || (apiBaseUrl.pathname !== '/' && apiBaseUrl.pathname !== '')) {
    return { ok: false, error: 'The API URL must be an HTTPS origin (HTTP is allowed for localhost only).' };
  }
  if (!CLIENT_ID_PATTERN.test(clientId)) {
    return { ok: false, error: 'Invalid OIDC client id.' };
  }
  return { ok: true, config: { issuer, clientId, scope: DEFAULT_SCOPE, apiBaseUrl: new URL(apiBaseUrl.origin) } };
}

export function loopbackRedirectUri(port: number): string {
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('Invalid loopback port');
  }
  return `http://${CALLBACK_HOST}:${port}${CALLBACK_PATH}`;
}

export type CallbackDecision =
  /** The authorization response for this login: hand the full URL to the OIDC library. */
  | { readonly kind: 'accept'; readonly url: URL }
  /** The provider reported an error (e.g. access_denied) for this login. */
  | { readonly kind: 'provider-error'; readonly error: string }
  /** Not a callback for this login (other path, wrong method, missing or wrong state): 404/400. */
  | { readonly kind: 'reject'; readonly status: 400 | 404 | 405 };

const SAFE_ERROR_CODE = /^[a-z_]{1,64}$/;

/**
 * Validates an incoming request on the loopback server. Only `GET /callback` carrying the exact
 * `state` of the pending login is accepted; anything else is ignored without ending the login,
 * so a local process cannot cancel or hijack it by guessing the port.
 */
export function evaluateCallback(method: string | undefined, rawUrl: string | undefined, port: number,
    expectedState: string): CallbackDecision {
  if (!rawUrl || !rawUrl.startsWith('/')) {
    return { kind: 'reject', status: 400 };
  }
  let url: URL;
  try {
    url = new URL(rawUrl, `http://${CALLBACK_HOST}:${port}`);
  } catch {
    return { kind: 'reject', status: 400 };
  }
  if (url.pathname !== CALLBACK_PATH) {
    return { kind: 'reject', status: 404 };
  }
  if (method !== 'GET') {
    return { kind: 'reject', status: 405 };
  }
  const states = url.searchParams.getAll('state');
  if (states.length !== 1 || !constantTimeEquals(states[0]!, expectedState)) {
    return { kind: 'reject', status: 400 };
  }
  const error = url.searchParams.get('error');
  if (error !== null) {
    return { kind: 'provider-error', error: SAFE_ERROR_CODE.test(error) ? error : 'invalid_response' };
  }
  if (url.searchParams.getAll('code').length !== 1) {
    return { kind: 'reject', status: 400 };
  }
  return { kind: 'accept', url };
}

export function constantTimeEquals(a: string, b: string): boolean {
  const left = Buffer.from(a, 'utf8');
  const right = Buffer.from(b, 'utf8');
  return left.length === right.length && timingSafeEqual(left, right);
}

// ---------------------------------------------------------------------------------- API proxy

export type ApiMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

/** What the renderer may ask for: a method, an API path and optional query/body. Never headers. */
export interface ApiRequest {
  readonly method: ApiMethod;
  readonly path: string;
  readonly query?: Readonly<Record<string, string | number | boolean>>;
  readonly body?: unknown;
}

const UUID = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';
const ORG = `/api/v1/organizations/${UUID}`;

/** Every API operation reachable from the renderer. Anything else is refused by the main process. */
const ALLOWED_ROUTES: ReadonlyArray<{ readonly method: ApiMethod; readonly pattern: RegExp }> = [
  ['GET', '/api/v1/health'],
  ['GET', '/api/v1/me'],
  ['PATCH', '/api/v1/me'],
  ['GET', '/api/v1/permissions'],
  ['GET', '/api/v1/organizations'],
  ['POST', '/api/v1/organizations'],
  ['GET', ORG],
  ['PATCH', ORG],
  ['GET', `${ORG}/audit-events`],
  ['GET', `${ORG}/members`],
  ['GET', `${ORG}/members/${UUID}`],
  ['POST', `${ORG}/members/${UUID}/suspend`],
  ['POST', `${ORG}/members/${UUID}/reactivate`],
  ['DELETE', `${ORG}/members/${UUID}`],
  ['PUT', `${ORG}/members/${UUID}/roles`],
  ['GET', `${ORG}/roles`],
  ['POST', `${ORG}/roles`],
  ['GET', `${ORG}/roles/${UUID}`],
  ['PATCH', `${ORG}/roles/${UUID}`],
  ['DELETE', `${ORG}/roles/${UUID}`],
  ['GET', `${ORG}/invitations`],
  ['POST', `${ORG}/invitations`],
  ['DELETE', `${ORG}/invitations/${UUID}`],
  ['POST', '/api/v1/invitations/accept'],
].map(([method, path]) => ({ method: method as ApiMethod, pattern: new RegExp(`^${path}$`) }));

const ALLOWED_QUERY: Readonly<Record<string, RegExp>> = {
  page: /^\d{1,6}$/,
  size: /^\d{1,3}$/,
  includeSuspended: /^(true|false)$/,
};

export const MAX_BODY_BYTES = 64 * 1024;

export type ResolvedApiRequest =
  | { readonly ok: true; readonly url: URL; readonly method: ApiMethod; readonly body: string | null }
  | { readonly ok: false; readonly reason: 'invalid-request' | 'not-allowed' };

/** Validates a renderer request against the allowlist and builds the target URL. */
export function resolveApiRequest(request: unknown, apiBaseUrl: URL): ResolvedApiRequest {
  if (typeof request !== 'object' || request === null || Array.isArray(request)) {
    return { ok: false, reason: 'invalid-request' };
  }
  const { method, path, query, body } = request as Record<string, unknown>;
  if (typeof method !== 'string' || typeof path !== 'string' || path.length > 512) {
    return { ok: false, reason: 'invalid-request' };
  }
  const route = ALLOWED_ROUTES.find((r) => r.method === method && r.pattern.test(path));
  if (!route) {
    return { ok: false, reason: 'not-allowed' };
  }
  const url = new URL(path, apiBaseUrl);
  if (url.origin !== apiBaseUrl.origin || url.pathname !== path) {
    return { ok: false, reason: 'not-allowed' };
  }
  if (query !== undefined) {
    if (typeof query !== 'object' || query === null || Array.isArray(query)) {
      return { ok: false, reason: 'invalid-request' };
    }
    for (const [key, raw] of Object.entries(query)) {
      const value = String(raw);
      const pattern = Object.hasOwn(ALLOWED_QUERY, key) ? ALLOWED_QUERY[key] : undefined;
      if (!pattern || !['string', 'number', 'boolean'].includes(typeof raw) || !pattern.test(value)) {
        return { ok: false, reason: 'not-allowed' };
      }
      url.searchParams.set(key, value);
    }
  }
  let serialized: string | null = null;
  if (body !== undefined && body !== null) {
    if (method === 'GET' || method === 'DELETE' || typeof body !== 'object') {
      return { ok: false, reason: 'invalid-request' };
    }
    serialized = JSON.stringify(body);
    if (Buffer.byteLength(serialized, 'utf8') > MAX_BODY_BYTES) {
      return { ok: false, reason: 'invalid-request' };
    }
  }
  return { ok: true, url, method: route.method, body: serialized };
}
