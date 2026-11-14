import type { ApiRequest, ApiResponse, AuthSessionState, DesktopBridge } from '../app/core/desktop/desktop-api';

export const SIGNED_IN_STATE: AuthSessionState = {
  status: 'signed-in',
  user: { subject: 's', email: 'jane@example.test', emailVerified: true, name: 'Jane' },
  storage: 'secure',
  reason: null,
};

/** Fake preload bridge: records what the renderer asks the main process for and answers with canned data. */
export class FakeDesktop {
  readonly requests: ApiRequest[] = [];
  handler: (request: ApiRequest) => ApiResponse | Promise<ApiResponse> = () => ({ status: 200, body: {} });

  readonly bridge: DesktopBridge = {
    getAppInfo: async () => {
      throw new Error('unused');
    },
    openExternal: async () => ({ ok: true }),
    auth: {
      getSession: async () => SIGNED_IN_STATE,
      login: async () => ({ ok: true }),
      cancelLogin: async () => undefined,
      logout: async () => undefined,
      onSessionChanged: () => () => undefined,
    },
    api: {
      request: async (request) => {
        this.requests.push(request);
        return this.handler(request);
      },
    },
  };

  install(): this {
    Object.defineProperty(window, 'desktop', { value: this.bridge, configurable: true, writable: true });
    return this;
  }

  static uninstall(): void {
    Object.defineProperty(window, 'desktop', { value: undefined, configurable: true, writable: true });
  }

  /** Requests whose path ends with the given suffix. */
  to(suffix: string, method = 'GET'): ApiRequest[] {
    return this.requests.filter((r) => r.path.endsWith(suffix) && r.method === method);
  }
}
