import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';
import { ApiProxy } from '../src/auth/api-proxy';
import { AuthSession, type OidcProvider, type PkceChecks, type TokenSet } from '../src/auth/auth-session';
import { startLoopbackServer } from '../src/auth/loopback-server';
import { EncryptedFileTokenStore, MemoryTokenStore, createTokenStore, type TokenCipher } from '../src/auth/token-store';

const ISSUER = 'https://idp.example.test/realms/ai-workforce';

/** Fake provider recording calls; tokens are opaque strings. */
class FakeProvider implements OidcProvider {
  readonly calls: string[] = [];
  exchanged: { url: URL; checks: PkceChecks } | null = null;
  refreshCount = 0;
  failRefresh = false;
  revoked: string[] = [];
  private counter = 0;

  constructor(private readonly lifetimeMs = 300_000) {}

  async newChecks(): Promise<PkceChecks> {
    this.counter++;
    return { verifier: `verifier-${this.counter}-${'v'.repeat(43)}`, state: `state-${this.counter}`, nonce: `nonce-${this.counter}` };
  }

  async authorizationUrl(redirectUri: string, checks: PkceChecks): Promise<URL> {
    const url = new URL(`${ISSUER}/protocol/openid-connect/auth`);
    url.searchParams.set('redirect_uri', redirectUri);
    url.searchParams.set('state', checks.state);
    url.searchParams.set('code_challenge_method', 'S256');
    return url;
  }

  async exchangeCode(url: URL, checks: PkceChecks): Promise<TokenSet> {
    this.exchanged = { url, checks };
    return this.tokens('initial');
  }

  async refresh(refreshToken: string): Promise<TokenSet> {
    this.calls.push(`refresh:${refreshToken}`);
    this.refreshCount++;
    await new Promise((resolve) => setTimeout(resolve, 10));
    if (this.failRefresh) {
      throw new Error('invalid_grant');
    }
    return this.tokens(`r${this.refreshCount}`);
  }

  async revoke(refreshToken: string): Promise<void> {
    this.revoked.push(refreshToken);
  }

  async endSessionUrl(): Promise<URL | null> {
    return new URL(`${ISSUER}/protocol/openid-connect/logout`);
  }

  private tokens(tag: string): TokenSet {
    return {
      accessToken: `access-${tag}`,
      refreshToken: `refresh-${tag}`,
      idToken: `id-${tag}`,
      expiresAt: Date.now() + this.lifetimeMs,
      claims: { sub: 'user-1', email: 'jane@example.test', emailVerified: true, name: 'Jane' },
    };
  }
}

/** Plays the system browser: follows the authorization URL back to the loopback callback. */
function browser(params: (url: URL) => string, opened: URL[] = []) {
  return async (url: URL) => {
    opened.push(url);
    if (!url.pathname.endsWith('/auth')) {
      return;
    }
    const redirect = url.searchParams.get('redirect_uri')!;
    setTimeout(() => void fetch(`${redirect}?${params(url)}`).catch(() => undefined), 5);
  };
}

const success = (url: URL) => `code=the-code&state=${url.searchParams.get('state')}`;

describe('AuthSession login', () => {
  it('completes Authorization Code + PKCE through the loopback callback', async () => {
    const provider = new FakeProvider();
    const store = new MemoryTokenStore();
    const opened: URL[] = [];
    const session = new AuthSession({ provider, store, openBrowser: browser(success, opened) });

    assert.deepEqual(await session.login(), { ok: true });

    assert.equal(opened.length, 1);
    const redirect = new URL(opened[0]!.searchParams.get('redirect_uri')!);
    assert.equal(redirect.hostname, '127.0.0.1');
    assert.equal(redirect.pathname, '/callback');
    assert.equal(provider.exchanged?.url.searchParams.get('code'), 'the-code');
    assert.equal(provider.exchanged?.checks.state, 'state-1');
    assert.equal(store.load(), 'refresh-initial');
    const state = session.state();
    assert.equal(state.status, 'signed-in');
    assert.equal(state.user?.email, 'jane@example.test');
    assert.doesNotMatch(JSON.stringify(state), /access-|refresh-|id-initial|verifier|nonce/);
    assert.equal(await session.accessToken(), 'access-initial');
    // The loopback server is closed once the login is over.
    await assert.rejects(fetch(`${redirect.origin}/callback`));
  });

  it('ignores callbacks with a forged state and keeps waiting for the real one', async () => {
    const provider = new FakeProvider();
    const session = new AuthSession({
      provider,
      store: new MemoryTokenStore(),
      openBrowser: async (url) => {
        const redirect = url.searchParams.get('redirect_uri')!;
        const forged = await fetch(`${redirect}?code=evil&state=forged`);
        assert.equal(forged.status, 400);
        const otherPath = await fetch(redirect.replace('/callback', '/steal'));
        assert.equal(otherPath.status, 404);
        await fetch(`${redirect}?code=good&state=${url.searchParams.get('state')}`);
      },
    });
    assert.deepEqual(await session.login(), { ok: true });
    assert.equal(provider.exchanged?.url.searchParams.get('code'), 'good');
  });

  it('reports a denied login without signing in', async () => {
    const session = new AuthSession({
      provider: new FakeProvider(),
      store: new MemoryTokenStore(),
      openBrowser: browser((url) => `error=access_denied&state=${url.searchParams.get('state')}`),
    });
    assert.deepEqual(await session.login(), { ok: false, reason: 'denied' });
    assert.equal(session.state().status, 'signed-out');
  });

  it('times out, and refuses concurrent logins', async () => {
    const session = new AuthSession({
      provider: new FakeProvider(),
      store: new MemoryTokenStore(),
      openBrowser: async () => undefined,
      loginTimeoutMs: 150,
    });
    const first = session.login();
    assert.deepEqual(await session.login(), { ok: false, reason: 'busy' });
    assert.equal(session.state().status, 'signing-in');
    assert.deepEqual(await first, { ok: false, reason: 'timeout' });
    assert.equal(session.state().status, 'signed-out');
  });

  it('can be cancelled', async () => {
    const session = new AuthSession({ provider: new FakeProvider(), store: new MemoryTokenStore(), openBrowser: async () => undefined });
    const pending = session.login();
    await new Promise((resolve) => setTimeout(resolve, 20));
    session.cancelLogin();
    assert.deepEqual(await pending, { ok: false, reason: 'cancelled' });
  });
});

describe('AuthSession tokens', () => {
  async function signedIn(provider: FakeProvider, store = new MemoryTokenStore()) {
    const session = new AuthSession({ provider, store, openBrowser: browser(success), refreshSkewMs: 30_000 });
    await session.login();
    return { session, store };
  }

  it('refreshes once (single flight) before expiry and stores the rotated refresh token', async () => {
    const provider = new FakeProvider(10_000); // expires within the skew: always refreshed
    const { session, store } = await signedIn(provider);
    const [a, b] = await Promise.all([session.accessToken(), session.accessToken()]);
    assert.equal(provider.refreshCount, 1);
    assert.equal(a, 'access-r1');
    assert.equal(b, 'access-r1');
    assert.deepEqual(provider.calls, ['refresh:refresh-initial']);
    assert.equal(store.load(), 'refresh-r1');
  });

  it('ends the session when the refresh token is rejected', async () => {
    const provider = new FakeProvider(10_000);
    const { session, store } = await signedIn(provider);
    const changes: string[] = [];
    session.onChange((state) => changes.push(`${state.status}:${state.reason}`));
    provider.failRefresh = true;
    assert.equal(await session.accessToken(), null);
    assert.equal(session.state().status, 'signed-out');
    assert.equal(session.state().reason, 'expired');
    assert.equal(store.load(), null);
    assert.deepEqual(changes, ['signed-out:expired']);
  });

  it('logout revokes the refresh token, wipes local state and ends the provider session', async () => {
    const provider = new FakeProvider();
    const opened: URL[] = [];
    const store = new MemoryTokenStore();
    const session = new AuthSession({ provider, store, openBrowser: browser(success, opened) });
    await session.login();
    await session.logout();
    assert.deepEqual(provider.revoked, ['refresh-initial']);
    assert.equal(store.load(), null);
    assert.equal(session.state().status, 'signed-out');
    assert.equal(session.state().reason, null);
    assert.equal(await session.accessToken(), null);
    assert.match(opened.at(-1)!.pathname, /logout$/);
  });

  it('restores a session from the stored refresh token, or discards an invalid one', async () => {
    const provider = new FakeProvider();
    const store = new MemoryTokenStore();
    store.save('refresh-stored');
    const session = new AuthSession({ provider, store, openBrowser: async () => undefined });
    assert.equal((await session.restore()).status, 'signed-in');
    assert.deepEqual(provider.calls, ['refresh:refresh-stored']);

    const failing = new FakeProvider();
    failing.failRefresh = true;
    const otherStore = new MemoryTokenStore();
    otherStore.save('refresh-revoked');
    const restored = await new AuthSession({ provider: failing, store: otherStore, openBrowser: async () => undefined }).restore();
    assert.equal(restored.status, 'signed-out');
    assert.equal(otherStore.load(), null);
  });
});

describe('token storage', () => {
  const cipher: TokenCipher = {
    isAvailable: () => true,
    encrypt: (plain) => Buffer.from(Buffer.from(plain, 'utf8').map((byte) => byte ^ 0x5a)),
    decrypt: (data) => Buffer.from(data.map((byte) => byte ^ 0x5a)).toString('utf8'),
  };

  it('encrypts the refresh token at rest with the OS cipher', () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'aiw-auth-'));
    const store = createTokenStore(directory, cipher);
    assert.equal(store.mode, 'secure');
    store.save('refresh-secret-value');
    const raw = fs.readFileSync(path.join(directory, 'session.bin'));
    assert.equal(raw.includes(Buffer.from('refresh-secret-value')), false);
    assert.equal(new EncryptedFileTokenStore(directory, cipher).load(), 'refresh-secret-value');
    store.clear();
    assert.equal(fs.existsSync(path.join(directory, 'session.bin')), false);
  });

  it('discards undecryptable data and falls back to memory without OS protection', () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'aiw-auth-'));
    fs.writeFileSync(path.join(directory, 'session.bin'), 'garbage');
    const broken: TokenCipher = { ...cipher, decrypt: () => { throw new Error('cannot decrypt'); } };
    assert.equal(new EncryptedFileTokenStore(directory, broken).load(), null);
    assert.equal(fs.existsSync(path.join(directory, 'session.bin')), false);

    const memory = createTokenStore(directory, { ...cipher, isAvailable: () => false });
    assert.equal(memory.mode, 'session-only');
    memory.save('in-memory');
    assert.equal(fs.readdirSync(directory).length, 0);
  });
});

describe('loopback server', () => {
  it('binds to 127.0.0.1 only and answers without caching', async () => {
    const server = await startLoopbackServer('s', 2_000);
    const redirect = new URL(server.redirectUri);
    assert.equal(redirect.hostname, '127.0.0.1');
    const response = await fetch(`${server.redirectUri}?code=c&state=s`);
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.doesNotMatch(await response.text(), /\bc\b.*code|token/i);
    assert.deepEqual((await server.result).kind, 'accept');
  });
});

describe('ApiProxy', () => {
  it('adds the bearer token in the main process and retries once after a 401', async () => {
    const seen: Array<{ url: string; auth: string | null }> = [];
    let first = true;
    const fakeFetch = (async (input: URL, init: RequestInit) => {
      seen.push({ url: input.toString(), auth: new Headers(init.headers).get('authorization') });
      const status = first ? 401 : 200;
      first = false;
      return new Response(JSON.stringify({ ok: status }), { status, headers: { 'Set-Cookie': 'x=y', 'X-Secret': 's' } });
    }) as typeof fetch;
    const tokens = { accessToken: async (force?: boolean) => (force ? 'fresh' : 'stale') };
    const proxy = new ApiProxy(new URL('http://localhost:8081'), tokens, fakeFetch);

    const response = await proxy.request({ method: 'GET', path: '/api/v1/me' });
    assert.deepEqual(response, { status: 200, body: { ok: 200 } });
    assert.deepEqual(seen.map((s) => s.auth), ['Bearer stale', 'Bearer fresh']);
  });

  it('never calls the network for refused requests or without a session', async () => {
    let called = false;
    const fakeFetch = (async () => {
      called = true;
      return new Response('{}');
    }) as unknown as typeof fetch;
    const signedOut = new ApiProxy(new URL('http://localhost:8081'), { accessToken: async () => null }, fakeFetch);
    assert.equal((await signedOut.request({ method: 'GET', path: '/actuator/env' })).error, 'not-allowed');
    assert.equal((await signedOut.request({ method: 'GET', path: '/api/v1/me' })).error, 'unauthenticated');
    assert.equal(called, false);
  });
});
