import { net, protocol } from 'electron';
import { stat } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { APP_SCHEME, productionCsp, resolveRendererPath } from './policy';

/** Must run before the `ready` event. */
export function registerAppScheme(): void {
  protocol.registerSchemesAsPrivileged([
    { scheme: APP_SCHEME, privileges: { standard: true, secure: true, supportFetchAPI: true } },
  ]);
}

async function isFile(filePath: string): Promise<boolean> {
  try {
    return (await stat(filePath)).isFile();
  } catch {
    return false;
  }
}

function reject(status: number): Response {
  return new Response(null, { status, headers: { 'Content-Security-Policy': productionCsp() } });
}

/**
 * Serves the built Angular application from `app://workforce/`.
 * - Requests cannot escape the renderer directory (see `resolveRendererPath`).
 * - Unknown extension-less paths fall back to index.html so Angular routes deep-link.
 * - Every response carries the Content Security Policy.
 */
export function serveRenderer(root: string): void {
  const indexFile = path.join(path.resolve(root), 'index.html');

  protocol.handle(APP_SCHEME, async (request) => {
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return reject(405);
    }
    const resolution = resolveRendererPath(root, request.url);
    if (resolution.kind === 'reject') {
      return reject(resolution.status);
    }

    let target = resolution.filePath;
    if (!(await isFile(target))) {
      // Client-side routes have no extension; missing assets must stay 404.
      if (resolution.isAsset) {
        return reject(404);
      }
      target = indexFile;
    }

    const response = await net.fetch(pathToFileURL(target).toString());
    const headers = new Headers(response.headers);
    headers.set('Content-Security-Policy', productionCsp());
    headers.set('X-Content-Type-Options', 'nosniff');
    return new Response(response.body, { status: response.status, headers });
  });
}
