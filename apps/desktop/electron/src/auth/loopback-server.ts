/**
 * Temporary loopback HTTP server receiving the OAuth authorization response (RFC 8252 §7.3).
 * Bound to 127.0.0.1 on an ephemeral port, it lives only for one login, accepts exactly one
 * valid callback (matching `state`) and then closes. It never receives or returns tokens.
 */
import http from 'node:http';
import type { AddressInfo } from 'node:net';
import { CALLBACK_HOST, evaluateCallback, loopbackRedirectUri } from './auth-policy';

export type CallbackResult =
  | { readonly kind: 'accept'; readonly url: URL }
  | { readonly kind: 'provider-error'; readonly error: string };

export interface LoopbackServer {
  readonly redirectUri: string;
  /** Resolves with the authorization response, rejects on timeout or cancellation. */
  readonly result: Promise<CallbackResult>;
  close(): void;
}

const PAGE_HEADERS = {
  'Content-Type': 'text/html; charset=utf-8',
  'Cache-Control': 'no-store',
  'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'",
  'Referrer-Policy': 'no-referrer',
  'X-Content-Type-Options': 'nosniff',
};

function page(title: string, message: string): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>${title}</title>
<style>body{font-family:system-ui,sans-serif;background:#0f1115;color:#e6e8eb;display:grid;place-items:center;height:100vh;margin:0}
main{max-width:28rem;text-align:center}</style></head>
<body><main><h1>${title}</h1><p>${message}</p></main></body></html>`;
}

export function startLoopbackServer(expectedState: string, timeoutMs: number): Promise<LoopbackServer> {
  return new Promise((resolveServer, rejectServer) => {
    let settle: ((result: CallbackResult) => void) | null = null;
    let fail: ((error: Error) => void) | null = null;
    const result = new Promise<CallbackResult>((resolve, reject) => {
      settle = resolve;
      fail = reject;
    });
    // Avoid unhandled rejections if the caller only awaits `result` later.
    result.catch(() => undefined);

    let port = 0;
    let done = false;
    const server = http.createServer((request, response) => {
      if (done) {
        response.writeHead(410, PAGE_HEADERS).end(page('Sign-in already completed', 'You can close this window.'));
        return;
      }
      const decision = evaluateCallback(request.method, request.url, port, expectedState);
      if (decision.kind === 'reject') {
        response.writeHead(decision.status, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' }).end();
        return;
      }
      done = true;
      const ok = decision.kind === 'accept';
      response.writeHead(200, PAGE_HEADERS).end(ok
        ? page('Signed in', 'You can close this window and return to AI Workforce Platform.')
        : page('Sign-in cancelled', 'Return to AI Workforce Platform to try again.'));
      settle?.(decision);
      close();
    });

    const timer = setTimeout(() => {
      fail?.(new Error('login-timeout'));
      close();
    }, timeoutMs);
    timer.unref();

    function close(): void {
      clearTimeout(timer);
      done = true;
      server.close();
      server.closeAllConnections();
    }

    server.on('error', (error) => {
      clearTimeout(timer);
      rejectServer(error);
    });
    server.listen(0, CALLBACK_HOST, () => {
      port = (server.address() as AddressInfo).port;
      resolveServer({
        redirectUri: loopbackRedirectUri(port),
        result,
        close: () => {
          if (!done) {
            fail?.(new Error('login-cancelled'));
          }
          close();
        },
      });
    });
  });
}
