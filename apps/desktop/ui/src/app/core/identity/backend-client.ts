import { DOCUMENT } from '@angular/common';
import { inject, Injectable } from '@angular/core';
import { SessionService } from '../auth/session.service';
import type { ApiMethod, ApiRequest, ApiResponse, DesktopBridge } from '../desktop/desktop-api';

/** RFC 9457 problem returned by the Core API. */
export interface Problem {
  readonly type?: string;
  readonly title?: string;
  readonly status?: number;
  readonly detail?: string;
  readonly errors?: readonly { readonly field: string; readonly message: string }[];
}

/** Failed API call. `message` is safe to display. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly problem: Problem | null = null,
    readonly reason: ApiResponse['error'] | null = null,
  ) {
    super(message);
    this.name = 'ApiError';
  }

  get isForbidden(): boolean {
    return this.status === 403;
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }
}

const TRANSPORT_MESSAGES: Record<NonNullable<ApiResponse['error']>, string> = {
  'invalid-request': 'The request is invalid.',
  'not-allowed': 'This operation is not available.',
  unauthenticated: 'Your session has ended. Please sign in again.',
  network: 'The Core API is unreachable. Check that it is running.',
  timeout: 'The Core API did not answer in time.',
  'too-large': 'The response is too large.',
};

/**
 * Calls the Core API through the Electron main process (`window.desktop.api`), which validates
 * the operation against its allowlist and attaches the bearer token. The renderer has no direct
 * network access to the API and never handles tokens.
 */
@Injectable({ providedIn: 'root' })
export class BackendClient {
  private readonly bridge: DesktopBridge | undefined = inject(DOCUMENT).defaultView?.desktop;
  private readonly session = inject(SessionService);

  get<T>(path: string, query?: ApiRequest['query']): Promise<T> {
    return this.send<T>('GET', path, undefined, query);
  }

  post<T>(path: string, body: unknown = {}): Promise<T> {
    return this.send<T>('POST', path, body);
  }

  put<T>(path: string, body: unknown): Promise<T> {
    return this.send<T>('PUT', path, body);
  }

  patch<T>(path: string, body: unknown): Promise<T> {
    return this.send<T>('PATCH', path, body);
  }

  delete<T = void>(path: string): Promise<T> {
    return this.send<T>('DELETE', path);
  }

  private async send<T>(method: ApiMethod, path: string, body?: unknown, query?: ApiRequest['query']): Promise<T> {
    if (!this.bridge) {
      throw new ApiError(0, 'The Core API is only available in the desktop application.');
    }
    const request: ApiRequest = { method, path, ...(query ? { query } : {}), ...(body !== undefined ? { body } : {}) };
    const response = await this.bridge.api.request(request);
    if (response.error) {
      if (response.error === 'unauthenticated') {
        void this.session.refreshState();
      }
      throw new ApiError(0, TRANSPORT_MESSAGES[response.error], null, response.error);
    }
    if (response.status >= 200 && response.status < 300) {
      return response.body as T;
    }
    if (response.status === 401) {
      void this.session.refreshState();
    }
    const problem = (response.body ?? null) as Problem | null;
    throw new ApiError(response.status, describe(response.status, problem), problem);
  }
}

function describe(status: number, problem: Problem | null): string {
  if (problem?.errors?.length) {
    return problem.errors.map((e) => `${e.field}: ${e.message}`).join(' · ');
  }
  if (problem?.detail) {
    return problem.detail;
  }
  if (status === 403) {
    return 'You do not have permission to perform this action.';
  }
  if (status === 404) {
    return 'Not found, or you no longer have access to it.';
  }
  return `Request failed (${status}).`;
}
