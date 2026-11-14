/**
 * Calls the Core API on behalf of the renderer. The renderer only names an allowlisted operation
 * (method + path + optional query/body); the main process adds the bearer token, so tokens never
 * cross the IPC boundary and the renderer cannot reach any other URL.
 */
import { resolveApiRequest } from './auth-policy';

export interface ApiResponse {
  /** HTTP status, or 0 when the request was not sent / did not complete. */
  readonly status: number;
  /** Parsed JSON body (RFC 9457 problem for errors), or null. */
  readonly body: unknown;
  /** Set when no HTTP response is available. */
  readonly error?: 'invalid-request' | 'not-allowed' | 'unauthenticated' | 'network' | 'timeout' | 'too-large';
}

export interface TokenSource {
  accessToken(forceRefresh?: boolean): Promise<string | null>;
}

const TIMEOUT_MS = 15_000;
const MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

export class ApiProxy {
  constructor(
    private readonly apiBaseUrl: URL,
    private readonly tokens: TokenSource,
    private readonly fetchImpl: typeof fetch = fetch,
  ) {}

  async request(raw: unknown): Promise<ApiResponse> {
    const resolved = resolveApiRequest(raw, this.apiBaseUrl);
    if (!resolved.ok) {
      return { status: 0, body: null, error: resolved.reason };
    }
    let token = await this.tokens.accessToken();
    if (!token) {
      return { status: 0, body: null, error: 'unauthenticated' };
    }
    let response = await this.send(resolved.url, resolved.method, resolved.body, token);
    if ('status' in response && response.status === 401) {
      // The token may have been revoked or rotated server-side: renew once, then give up.
      token = await this.tokens.accessToken(true);
      if (!token) {
        return { status: 0, body: null, error: 'unauthenticated' };
      }
      response = await this.send(resolved.url, resolved.method, resolved.body, token);
    }
    return response;
  }

  private async send(url: URL, method: string, body: string | null, token: string): Promise<ApiResponse> {
    const headers: Record<string, string> = { Accept: 'application/json', Authorization: `Bearer ${token}` };
    if (body !== null) {
      headers['Content-Type'] = 'application/json';
    }
    let response: Response;
    try {
      response = await this.fetchImpl(url, {
        method,
        headers,
        body,
        redirect: 'error',
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
    } catch (error) {
      const timeout = error instanceof Error && (error.name === 'TimeoutError' || error.name === 'AbortError');
      return { status: 0, body: null, error: timeout ? 'timeout' : 'network' };
    }
    const declared = Number(response.headers.get('content-length') ?? '0');
    if (declared > MAX_RESPONSE_BYTES) {
      return { status: 0, body: null, error: 'too-large' };
    }
    const text = await response.text();
    if (text.length > MAX_RESPONSE_BYTES) {
      return { status: 0, body: null, error: 'too-large' };
    }
    let parsed: unknown = null;
    if (text.length > 0) {
      try {
        parsed = JSON.parse(text);
      } catch {
        parsed = null;
      }
    }
    return { status: response.status, body: parsed };
  }
}
