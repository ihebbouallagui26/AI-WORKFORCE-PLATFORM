import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  constantTimeEquals,
  evaluateCallback,
  loopbackRedirectUri,
  parseAuthConfig,
  resolveApiRequest,
} from '../src/auth/auth-policy';

const API = new URL('http://localhost:8081');
const ORG = '0195f0a4-1c2d-7e3f-8a9b-0c1d2e3f4a5b';
const STATE = 'state-abc_123';

describe('parseAuthConfig', () => {
  it('defaults to the local development stack', () => {
    const result = parseAuthConfig({});
    assert.ok(result.ok);
    assert.equal(result.config.issuer.toString(), 'http://localhost:8082/realms/ai-workforce');
    assert.equal(result.config.clientId, 'ai-workforce-desktop');
    assert.equal(result.config.apiBaseUrl.origin, 'http://localhost:8081');
    assert.match(result.config.scope, /\bopenid\b/);
  });

  it('requires HTTPS except for loopback hosts', () => {
    assert.equal(parseAuthConfig({ AIW_OIDC_ISSUER: 'http://idp.example.com/realms/x' }).ok, false);
    assert.equal(parseAuthConfig({ AIW_API_BASE_URL: 'http://api.example.com' }).ok, false);
    assert.equal(parseAuthConfig({ AIW_OIDC_ISSUER: 'https://idp.example.com/realms/x' }).ok, true);
    assert.equal(parseAuthConfig({ AIW_OIDC_ISSUER: 'https://user:pw@idp.example.com/realms/x' }).ok, false);
    assert.equal(parseAuthConfig({ AIW_API_BASE_URL: 'https://api.example.com/some/path' }).ok, false);
    assert.equal(parseAuthConfig({ AIW_OIDC_CLIENT_ID: 'bad client' }).ok, false);
    assert.equal(parseAuthConfig({ AIW_OIDC_ISSUER: 'not a url' }).ok, false);
  });
});

describe('loopbackRedirectUri', () => {
  it('uses 127.0.0.1 and the registered path', () => {
    assert.equal(loopbackRedirectUri(51234), 'http://127.0.0.1:51234/callback');
    assert.throws(() => loopbackRedirectUri(0));
    assert.throws(() => loopbackRedirectUri(70000));
  });
});

describe('evaluateCallback', () => {
  it('accepts exactly one code with the expected state', () => {
    const decision = evaluateCallback('GET', `/callback?code=abc&state=${STATE}`, 5000, STATE);
    assert.equal(decision.kind, 'accept');
    assert.equal(decision.kind === 'accept' && decision.url.searchParams.get('code'), 'abc');
  });

  it('rejects wrong state, missing state, duplicates and missing code', () => {
    assert.deepEqual(evaluateCallback('GET', '/callback?code=abc&state=other', 5000, STATE), { kind: 'reject', status: 400 });
    assert.deepEqual(evaluateCallback('GET', '/callback?code=abc', 5000, STATE), { kind: 'reject', status: 400 });
    assert.deepEqual(evaluateCallback('GET', `/callback?code=a&state=${STATE}&state=${STATE}`, 5000, STATE),
      { kind: 'reject', status: 400 });
    assert.deepEqual(evaluateCallback('GET', `/callback?code=a&code=b&state=${STATE}`, 5000, STATE),
      { kind: 'reject', status: 400 });
    assert.deepEqual(evaluateCallback('GET', `/callback?state=${STATE}`, 5000, STATE), { kind: 'reject', status: 400 });
  });

  it('rejects other paths and methods', () => {
    assert.deepEqual(evaluateCallback('GET', `/other?code=a&state=${STATE}`, 5000, STATE), { kind: 'reject', status: 404 });
    assert.deepEqual(evaluateCallback('POST', `/callback?code=a&state=${STATE}`, 5000, STATE), { kind: 'reject', status: 405 });
    assert.deepEqual(evaluateCallback('GET', 'http://evil/callback', 5000, STATE), { kind: 'reject', status: 400 });
    assert.deepEqual(evaluateCallback('GET', undefined, 5000, STATE), { kind: 'reject', status: 400 });
  });

  it('reports provider errors with a sanitized code', () => {
    assert.deepEqual(evaluateCallback('GET', `/callback?error=access_denied&state=${STATE}`, 5000, STATE),
      { kind: 'provider-error', error: 'access_denied' });
    assert.deepEqual(evaluateCallback('GET', `/callback?error=%3Cscript%3E&state=${STATE}`, 5000, STATE),
      { kind: 'provider-error', error: 'invalid_response' });
  });

  it('compares secrets without early exit semantics', () => {
    assert.equal(constantTimeEquals('abc', 'abc'), true);
    assert.equal(constantTimeEquals('abc', 'abd'), false);
    assert.equal(constantTimeEquals('abc', 'abcd'), false);
  });
});

describe('resolveApiRequest', () => {
  it('allows the declared operations only', () => {
    const ok = resolveApiRequest(
      { method: 'GET', path: `/api/v1/organizations/${ORG}/members`, query: { page: 0, size: 20 } }, API);
    assert.ok(ok.ok);
    assert.equal(ok.url.toString(), `http://localhost:8081/api/v1/organizations/${ORG}/members?page=0&size=20`);
    assert.ok(resolveApiRequest({ method: 'POST', path: '/api/v1/invitations/accept', body: { token: 'x' } }, API).ok);
    assert.ok(resolveApiRequest(
      { method: 'PUT', path: `/api/v1/organizations/${ORG}/members/${ORG}/roles`, body: { roleIds: [] } }, API).ok);
  });

  it('refuses anything outside the allowlist', () => {
    const refused = [
      { method: 'GET', path: '/actuator/env' },
      { method: 'DELETE', path: `/api/v1/organizations/${ORG}` },
      { method: 'GET', path: `/api/v1/organizations/${ORG}/../../actuator/env` },
      { method: 'GET', path: '/api/v1/organizations/not-a-uuid' },
      { method: 'GET', path: 'http://evil.example/api/v1/me' },
      { method: 'GET', path: '//evil.example/api/v1/me' },
      { method: 'GET', path: '/api/v1/me?x=1' },
      { method: 'GET', path: '/api/v1/organizations', query: { sort: 'name' } },
      { method: 'GET', path: '/api/v1/organizations', query: { page: '1; drop' } },
      { method: 'GET', path: '/api/v1/organizations', query: JSON.parse('{"__proto__":"1"}') },
      { method: 'GET', path: '/api/v1/organizations', query: { constructor: '1' } },
      { method: 'TRACE', path: '/api/v1/me' },
    ];
    for (const request of refused) {
      assert.equal(resolveApiRequest(request, API).ok, false, JSON.stringify(request));
    }
  });

  it('allows the workforce operations of Sprint B2 with strict filters', () => {
    const base = `/api/v1/organizations/${ORG}`;
    const allowed = [
      { method: 'GET', path: `${base}/employees`, query: { q: 'ines dupont', status: 'ACTIVE', departmentId: ORG, positionId: ORG, managerId: ORG, page: 0, size: 20 } },
      { method: 'POST', path: `${base}/employees`, body: { displayName: 'x' } },
      { method: 'PUT', path: `${base}/employees/${ORG}/manager`, body: { managerId: null } },
      { method: 'PUT', path: `${base}/employees/${ORG}/status`, body: { status: 'INACTIVE' } },
      { method: 'GET', path: `${base}/departments/tree`, query: { includeArchived: true } },
      { method: 'PUT', path: `${base}/departments/${ORG}/parent`, body: { parentId: null } },
      { method: 'DELETE', path: `${base}/departments/${ORG}` },
      { method: 'POST', path: `${base}/positions/${ORG}/archive`, body: {} },
      { method: 'GET', path: `${base}/teams/${ORG}/members`, query: { includeFormer: false } },
      { method: 'DELETE', path: `${base}/teams/${ORG}/members/${ORG}` },
      { method: 'GET', path: `${base}/employees/${ORG}/teams` },
    ];
    for (const request of allowed) {
      assert.ok(resolveApiRequest(request, API).ok, JSON.stringify(request));
    }
  });

  it('refuses workforce requests that are malformed or not part of the API', () => {
    const base = `/api/v1/organizations/${ORG}`;
    const refused = [
      { method: 'DELETE', path: `${base}/employees/${ORG}` },
      { method: 'POST', path: `${base}/employees/${ORG}/delete` },
      { method: 'PUT', path: `${base}/employees/not-a-uuid/manager`, body: {} },
      { method: 'GET', path: `${base}/employees`, query: { departmentId: 'x; drop table' } },
      { method: 'GET', path: `${base}/employees`, query: { status: 'active' } },
      { method: 'GET', path: `${base}/employees`, query: { q: 'a'.repeat(101) } },
      { method: 'GET', path: `${base}/employees`, query: { q: 'line' + String.fromCharCode(10) + 'break' } },
      { method: 'GET', path: `${base}/employees`, query: { sort: 'salary' } },
      { method: 'GET', path: `${base}/salaries` },
    ];
    for (const request of refused) {
      assert.equal(resolveApiRequest(request, API).ok, false, JSON.stringify(request));
    }
  });

  it('allows the agent, provider, skill and tool operations of Sprint B3', () => {
    const base = `/api/v1/organizations/${ORG}`;
    const allowed = [
      { method: 'GET', path: `${base}/agents`, query: { q: 'atlas', status: 'ACTIVE', departmentId: ORG, page: 0, size: 20 } },
      { method: 'POST', path: `${base}/agents`, body: { name: 'Atlas' } },
      { method: 'POST', path: `${base}/agents/${ORG}/activate`, body: {} },
      { method: 'PUT', path: `${base}/agents/${ORG}/tools`, body: { grants: [] } },
      { method: 'PUT', path: `${base}/agents/${ORG}/memory-config`, body: { enabled: false } },
      { method: 'POST', path: `${base}/agents/${ORG}/providers`, body: { modelId: 'm' } },
      { method: 'PATCH', path: `${base}/agents/${ORG}/providers/${ORG}`, body: { makePrimary: true } },
      { method: 'GET', path: `${base}/providers/connections`, query: { type: 'OPENAI', status: 'VALID' } },
      { method: 'POST', path: `${base}/providers/connections`, body: { providerType: 'OPENAI', displayName: 'x', credential: 'write-only' } },
      { method: 'PUT', path: `${base}/providers/connections/${ORG}/credential`, body: { credential: 'write-only' } },
      { method: 'POST', path: `${base}/providers/connections/${ORG}/test`, body: {} },
      { method: 'GET', path: `${base}/providers/connections/${ORG}/models` },
      { method: 'GET', path: '/api/v1/providers/types' },
      { method: 'GET', path: `${base}/skills`, query: { active: true, category: 'Engineering' } },
      { method: 'GET', path: `${base}/tools` },
      { method: 'POST', path: `${base}/teams/${ORG}/agents`, body: { agentId: ORG } },
    ];
    for (const request of allowed) {
      assert.ok(resolveApiRequest(request, API).ok, JSON.stringify(request));
    }
  });

  it('refuses agent and provider requests that are malformed or not part of the API', () => {
    const base = `/api/v1/organizations/${ORG}`;
    const refused = [
      { method: 'DELETE', path: `${base}/agents/${ORG}` },
      { method: 'GET', path: `${base}/providers/connections/${ORG}/credential` },
      { method: 'GET', path: `${base}/providers/connections/${ORG}/secret` },
      { method: 'POST', path: `${base}/agents/${ORG}/run`, body: {} },
      { method: 'POST', path: `${base}/agents/${ORG}/execute`, body: {} },
      { method: 'POST', path: `${base}/tools/execute`, body: {} },
      { method: 'GET', path: `${base}/agents`, query: { type: 'openai' } },
      { method: 'GET', path: `${base}/skills`, query: { category: '<script>' } },
      { method: 'GET', path: `${base}/skills`, query: { active: 'maybe' } },
      { method: 'PUT', path: `${base}/providers/connections/${ORG}/credential`, query: { credential: 'in-the-url' }, body: {} },
    ];
    for (const request of refused) {
      assert.equal(resolveApiRequest(request, API).ok, false, JSON.stringify(request));
    }
  });

  it('validates bodies', () => {
    assert.equal(resolveApiRequest('GET /api/v1/me', API).ok, false);
    assert.equal(resolveApiRequest({ method: 'GET', path: '/api/v1/me', body: { a: 1 } }, API).ok, false);
    assert.equal(resolveApiRequest({ method: 'PATCH', path: '/api/v1/me', body: 'text' }, API).ok, false);
    assert.equal(resolveApiRequest({ method: 'PATCH', path: '/api/v1/me', body: { d: 'x'.repeat(70_000) } }, API).ok, false);
  });

  it('never lets the renderer choose headers or another origin', () => {
    const resolved = resolveApiRequest(
      { method: 'GET', path: '/api/v1/me', headers: { Authorization: 'Bearer stolen' } }, API);
    assert.ok(resolved.ok);
    assert.equal(resolved.url.origin, API.origin);
    assert.equal('headers' in resolved, false);
  });
});
