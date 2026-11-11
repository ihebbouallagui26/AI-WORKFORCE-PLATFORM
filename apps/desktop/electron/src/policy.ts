/**
 * Security policy as pure functions (no Electron imports) so it can be unit-tested
 * with `node --test`. Everything that decides what is trusted lives here.
 */
import path from 'node:path';

/** Custom privileged scheme serving the packaged Angular build. */
export const APP_SCHEME = 'app';
export const APP_HOST = 'workforce';
export const APP_ORIGIN = `${APP_SCHEME}://${APP_HOST}`;

/** Hosts the renderer may open in the system browser (HTTPS only). */
export const ALLOWED_EXTERNAL_HOSTS: ReadonlySet<string> = new Set(['github.com', 'angular.dev', 'www.electronjs.org']);

const MAX_URL_LENGTH = 2048;

/** True for URLs belonging to the application itself (bundled origin or dev server). */
export function isAppUrl(value: string, devServer: URL | null): boolean {
  try {
    const url = new URL(value);
    // `URL.origin` is "null" for custom schemes in Node, so compare scheme and host explicitly.
    if (url.protocol === `${APP_SCHEME}:` && url.host === APP_HOST) {
      return true;
    }
    return devServer !== null && url.origin === devServer.origin;
  } catch {
    return false;
  }
}

export function isAllowedExternalUrl(value: unknown): value is string {
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_URL_LENGTH) {
    return false;
  }
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && ALLOWED_EXTERNAL_HOSTS.has(url.hostname) && !url.username && !url.password;
  } catch {
    return false;
  }
}

/** Accepts only http://localhost or http://127.0.0.1 as a development server. */
export function parseDevServerUrl(value: string): URL | null {
  try {
    const url = new URL(value);
    const isLocal = url.hostname === 'localhost' || url.hostname === '127.0.0.1';
    return url.protocol === 'http:' && isLocal ? url : null;
  } catch {
    return null;
  }
}

export type RendererPathResolution =
  | { readonly kind: 'file'; readonly filePath: string; readonly isAsset: boolean }
  | { readonly kind: 'reject'; readonly status: 400 | 403 | 404 };

/**
 * Maps an `app://workforce/...` request URL to a file under `root`.
 * Rejects foreign hosts, malformed encodings, NUL bytes and any path escaping `root`.
 * `isAsset` tells the caller whether a missing file is a 404 (asset with an extension)
 * or should fall back to index.html (client-side route).
 */
export function resolveRendererPath(root: string, requestUrl: string): RendererPathResolution {
  let url: URL;
  try {
    url = new URL(requestUrl);
  } catch {
    return { kind: 'reject', status: 400 };
  }
  if (url.protocol !== `${APP_SCHEME}:` || url.host !== APP_HOST) {
    return { kind: 'reject', status: 404 };
  }

  let pathname: string;
  try {
    pathname = decodeURIComponent(url.pathname);
  } catch {
    return { kind: 'reject', status: 400 };
  }
  if (pathname.includes('\0')) {
    return { kind: 'reject', status: 400 };
  }

  const normalizedRoot = path.resolve(root);
  const filePath = path.resolve(normalizedRoot, `.${pathname}`);
  if (filePath !== normalizedRoot && !filePath.startsWith(normalizedRoot + path.sep)) {
    return { kind: 'reject', status: 403 };
  }
  return { kind: 'file', filePath, isAsset: path.extname(pathname) !== '' };
}

const BASE_DIRECTIVES = [
  "default-src 'none'",
  "script-src 'self'",
  // Angular injects component styles at runtime.
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data:",
  "font-src 'self'",
  "base-uri 'self'",
  "form-action 'none'",
  "frame-ancestors 'none'",
  "object-src 'none'",
];

/** Production policy: everything is served from the bundled app origin. */
export function productionCsp(): string {
  return [...BASE_DIRECTIVES, "connect-src 'self'"].join('; ');
}

/** Development policy: additionally allows the dev server's live-reload socket. */
export function developmentCsp(devServer: URL): string {
  return [...BASE_DIRECTIVES, `connect-src 'self' ws://${devServer.host}`].join('; ');
}

/** Chromium switches that expose a debugging endpoint and must not reach packaged builds. */
export const FORBIDDEN_PACKAGED_SWITCHES: readonly string[] = [
  'remote-debugging-port',
  'remote-debugging-pipe',
  'inspect',
  'inspect-brk',
  'remote-allow-origins',
];
