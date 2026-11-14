import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { demoScoped, DEMO_ORGANIZATION_ID } from '../data/backend/demo-scope';
import type { ApiRequest, ApiResponse, AuthSessionState, DesktopBridge, LoginResult } from '../desktop/desktop-api';
import { AccessStore } from '../identity/access.store';
import { ApiError, BackendClient } from '../identity/backend-client';
import { IdentityApi } from '../identity/identity.api';
import { WorkspaceStore } from '../state/workspace.store';
import { MembersSettings } from '../../features/settings/sections/members-settings';
import { authGuard, safeReturnUrl } from './auth.guard';
import { AUTH_REQUIRED, SessionService } from './session.service';

const ORG = '0195f0a4-1c2d-7e3f-8a9b-0c1d2e3f4a5b';
const SIGNED_IN: AuthSessionState = {
  status: 'signed-in',
  user: { subject: 's', email: 'jane@example.test', emailVerified: true, name: 'Jane' },
  storage: 'secure',
  reason: null,
};
const SIGNED_OUT: AuthSessionState = { status: 'signed-out', user: null, storage: 'secure', reason: null };

/** Fake preload bridge recording what the renderer asks for. */
class FakeBridge {
  state: AuthSessionState = SIGNED_OUT;
  readonly requests: ApiRequest[] = [];
  respond: (request: ApiRequest) => ApiResponse = () => ({ status: 200, body: {} });
  listener: ((state: AuthSessionState) => void) | null = null;
  logins = 0;
  logouts = 0;

  readonly bridge: DesktopBridge = {
    getAppInfo: async () => { throw new Error('unused'); },
    openExternal: async () => ({ ok: true }),
    auth: {
      getSession: async () => this.state,
      login: async (): Promise<LoginResult> => {
        this.logins++;
        this.state = SIGNED_IN;
        return { ok: true };
      },
      cancelLogin: async () => undefined,
      logout: async () => {
        this.logouts++;
        this.state = SIGNED_OUT;
      },
      onSessionChanged: (listener) => {
        this.listener = listener;
        return () => (this.listener = null);
      },
    },
    api: {
      request: async (request) => {
        this.requests.push(request);
        return this.respond(request);
      },
    },
  };
}

function install(fake: FakeBridge | null, authRequired = true): void {
  Object.defineProperty(window, 'desktop', { value: fake?.bridge, configurable: true, writable: true });
  TestBed.configureTestingModule({
    providers: [provideZonelessChangeDetection(), provideRouter([]), { provide: AUTH_REQUIRED, useValue: authRequired }],
  });
}

async function runGuard(url = '/agents'): Promise<boolean | UrlTree> {
  return (await TestBed.runInInjectionContext(() =>
    authGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot))) as boolean | UrlTree;
}

afterEach(() => {
  Object.defineProperty(window, 'desktop', { value: undefined, configurable: true, writable: true });
});

describe('SessionService', () => {
  it('mirrors the main-process session and forwards login and logout', async () => {
    const fake = new FakeBridge();
    install(fake);
    const session = TestBed.inject(SessionService);
    expect(session.status()).toBe('loading');
    await session.whenReady();
    expect(session.status()).toBe('signed-out');

    await session.login();
    expect(fake.logins).toBe(1);
    expect(session.isAuthenticated()).toBe(true);
    expect(session.user()?.email).toBe('jane@example.test');

    fake.listener?.({ ...SIGNED_OUT, reason: 'expired' });
    expect(session.status()).toBe('signed-out');
    expect(session.endReason()).toBe('expired');

    fake.state = SIGNED_IN;
    await session.logout();
    expect(fake.logouts).toBe(1);
    expect(session.isAuthenticated()).toBe(false);
  });

  it('never exposes a token: the state only carries the identity', async () => {
    const fake = new FakeBridge();
    fake.state = SIGNED_IN;
    install(fake);
    const session = TestBed.inject(SessionService);
    await session.whenReady();
    expect(JSON.stringify({ user: session.user(), status: session.status() })).not.toMatch(/token/i);
  });

  it('is unsupported outside the desktop app in api mode, and open in mock mode', async () => {
    install(null);
    expect(TestBed.inject(SessionService).status()).toBe('unsupported');
    TestBed.resetTestingModule();
    install(null, false);
    expect(TestBed.inject(SessionService).isAuthenticated()).toBe(true);
  });
});

describe('authGuard', () => {
  it('redirects signed-out users to the login page with a return URL', async () => {
    install(new FakeBridge());
    const result = await runGuard('/settings/members');
    expect(result instanceof UrlTree).toBe(true);
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/login?returnUrl=%2Fsettings%2Fmembers');
  });

  it('lets signed-in users through, and everyone in mock mode', async () => {
    const fake = new FakeBridge();
    fake.state = SIGNED_IN;
    install(fake);
    expect(await runGuard()).toBe(true);
    TestBed.resetTestingModule();
    install(null, false);
    expect(await runGuard()).toBe(true);
  });

  it('only accepts in-app return URLs', () => {
    expect(safeReturnUrl('/agents?tab=1')).toBe('/agents?tab=1');
    for (const unsafe of ['//evil.example', 'https://evil.example', '/login', '/\\evil', '', null, undefined]) {
      expect(safeReturnUrl(unsafe)).toBe('/dashboard');
    }
  });
});

describe('BackendClient and IdentityApi', () => {
  it('sends only method, path, query and body through the bridge', async () => {
    const fake = new FakeBridge();
    fake.respond = () => ({ status: 200, body: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } });
    install(fake);
    await TestBed.inject(IdentityApi).listMembers(ORG, 0, 20, true);
    expect(fake.requests).toEqual([
      { method: 'GET', path: `/api/v1/organizations/${ORG}/members`, query: { page: 0, size: 20, includeSuspended: true } },
    ]);
  });

  it('turns problem details into ApiError and refreshes the session on 401', async () => {
    const fake = new FakeBridge();
    install(fake);
    const client = TestBed.inject(BackendClient);
    fake.respond = () => ({ status: 409, body: { status: 409, detail: 'The organization must keep at least one active owner.' } });
    await expect(client.get('/api/v1/me')).rejects.toMatchObject({ status: 409, message: 'The organization must keep at least one active owner.' });

    fake.respond = () => ({ status: 0, body: null, error: 'unauthenticated' });
    fake.state = { ...SIGNED_OUT, reason: 'expired' };
    const error = await client.get('/api/v1/me').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    await new Promise((resolve) => setTimeout(resolve));
    expect(TestBed.inject(SessionService).endReason()).toBe('expired');
  });

  it('refuses identifiers that could alter the path', () => {
    install(new FakeBridge());
    const api = TestBed.inject(IdentityApi);
    expect(() => api.getOrganization('../../actuator')).toThrow();
    expect(() => api.removeMember(ORG, `${ORG}/roles`)).toThrow();
  });
});

describe('permission-based UI', () => {
  function renderMembers(permissions: string[]): HTMLElement {
    const fake = new FakeBridge();
    fake.state = SIGNED_IN;
    fake.respond = (request) => request.path.endsWith('/members')
      ? { status: 200, body: { content: [{ membershipId: '0195f0a4-1c2d-7e3f-8a9b-000000000001', userId: 'u', email: 'bob@example.test', displayName: 'Bob', status: 'ACTIVE', joinedAt: '', statusChangedAt: '', roles: [] }], page: 0, size: 100, totalElements: 1, totalPages: 1 } }
      : { status: 200, body: [] };
    install(fake);
    TestBed.overrideProvider(WorkspaceStore, { useValue: { currentOrganizationId: signal(ORG), reload: () => undefined } });
    TestBed.overrideProvider(AccessStore, {
      useValue: {
        can: (p: string) => permissions.includes(p),
        organization: signal({ myMembershipId: 'me' }),
        reload: () => undefined,
      },
    });
    const fixture = TestBed.createComponent(MembersSettings);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(element: HTMLElement): Promise<string> {
    for (let i = 0; i < 5; i++) {
      await new Promise((resolve) => setTimeout(resolve));
      TestBed.tick();
    }
    return element.textContent ?? '';
  }

  it('hides management actions without the permission', async () => {
    const text = await settle(renderMembers(['member.read']));
    expect(text).toContain('bob@example.test');
    expect(text).not.toContain('Suspend');
    expect(text).not.toContain('Remove');
  });

  it('shows them when the API grants member.manage', async () => {
    const text = await settle(renderMembers(['member.read', 'member.manage']));
    expect(text).toContain('Suspend');
    expect(text).toContain('Remove');
  });

  it('shows nothing without member.read', async () => {
    const text = await settle(renderMembers([]));
    expect(text).toContain('No access');
    expect(text).not.toContain('bob@example.test');
  });
});

describe('demo scope', () => {
  it('serves demo data whatever real organization is selected', () => {
    const calls: unknown[][] = [];
    const api = demoScoped({ listAgents: (...args: unknown[]) => calls.push(args), updateAgent: (...args: unknown[]) => calls.push(args) }, ['listAgents']);
    api.listAgents(ORG);
    api.updateAgent('agent-1');
    expect(calls).toEqual([[DEMO_ORGANIZATION_ID], ['agent-1']]);
  });
});
