import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { parseAuthConfig } from '../src/auth/auth-policy';
import { AuthSession } from '../src/auth/auth-session';
import { OpenIdProvider } from '../src/auth/openid-provider';
import { MemoryTokenStore } from '../src/auth/token-store';

/**
 * End-to-end check of the production OIDC code (openid-client) against the local Keycloak
 * (`docker compose up`). Opt-in: set AIW_KEYCLOAK_ADMIN_PASSWORD (from infra/docker/.env) to run.
 * The system browser is simulated by submitting the Keycloak login form.
 */
const adminPassword = process.env['AIW_KEYCLOAK_ADMIN_PASSWORD'];
const parsed = parseAuthConfig(process.env);

describe('desktop login against the local Keycloak', { skip: !adminPassword && 'AIW_KEYCLOAK_ADMIN_PASSWORD not set' }, () => {
  it('signs in with PKCE, refreshes with rotation and logs out with revocation', async () => {
    assert.ok(parsed.ok);
    const { issuer } = parsed.config;
    const base = issuer.origin;
    const realm = issuer.pathname.split('/').pop()!;
    const email = `desktop.${Date.now()}@example.test`;
    const password = `Desktop-${crypto.randomUUID()}`;

    const admin = await (await fetch(`${base}/realms/master/protocol/openid-connect/token`, {
      method: 'POST',
      body: new URLSearchParams({ grant_type: 'password', client_id: 'admin-cli', username: process.env['AIW_KEYCLOAK_ADMIN_USERNAME'] ?? 'admin', password: adminPassword! }),
    })).json() as { access_token: string };
    const created = await fetch(`${base}/admin/realms/${realm}/users`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${admin.access_token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: email, email, emailVerified: true, enabled: true, firstName: 'Desk', lastName: 'Top',
        credentials: [{ type: 'password', value: password, temporary: false }] }),
    });
    assert.equal(created.status, 201);

    const provider = new OpenIdProvider(parsed.config);
    const store = new MemoryTokenStore();
    const session = new AuthSession({
      provider,
      store,
      openBrowser: async (url) => {
        if (!url.pathname.endsWith('/auth')) {
          return; // end-session page after logout
        }
        const page = await fetch(url, { redirect: 'manual' });
        const cookies = page.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ');
        const action = /id="kc-form-login"[^>]*action="([^"]+)"/.exec(await page.text())![1]!.replaceAll('&amp;', '&');
        const submitted = await fetch(action, {
          method: 'POST',
          redirect: 'manual',
          headers: { Cookie: cookies, 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({ username: email, password }),
        });
        const location = submitted.headers.get('location')!;
        assert.match(location, /^http:\/\/127\.0\.0\.1:\d+\/callback\?/);
        await fetch(location); // the browser follows the redirect to the loopback server
      },
    });

    assert.deepEqual(await session.login(), { ok: true });
    assert.equal(session.state().user?.email, email);
    const firstRefresh = store.load();
    assert.ok(firstRefresh);

    const access = await session.accessToken(true);
    assert.ok(access);
    const claims = JSON.parse(Buffer.from(access.split('.')[1]!, 'base64url').toString()) as { aud: string | string[] };
    assert.ok([claims.aud].flat().includes('ai-workforce-api'));
    assert.notEqual(store.load(), firstRefresh, 'refresh token rotated');
    // The previous refresh token can no longer be used.
    await assert.rejects(provider.refresh(firstRefresh));

    const rotated = store.load()!;
    await session.logout();
    assert.equal(store.load(), null);
    await assert.rejects(provider.refresh(rotated));
  });
});
