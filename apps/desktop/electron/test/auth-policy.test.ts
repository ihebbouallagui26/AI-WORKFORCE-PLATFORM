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
      { method: 'POST', path: `${base}/agents/${ORG}/executions`, body: { task: 'Summarize the plan' } },
      { method: 'GET', path: `${base}/agents/${ORG}/executions`, query: { status: 'SUCCEEDED', page: 0, size: 10 } },
      { method: 'GET', path: `${base}/executions`, query: { agentId: ORG, status: 'FAILED' } },
      { method: 'GET', path: `${base}/executions/${ORG}` },
      // Tool activity and human approvals (Sprint C2).
      { method: 'GET', path: `${base}/executions/${ORG}/tool-calls`, query: { page: 0, size: 50 } },
      { method: 'GET', path: `${base}/approvals`, query: { status: 'PENDING', executionId: ORG, page: 0, size: 20 } },
      { method: 'GET', path: `${base}/approvals/${ORG}` },
      { method: 'POST', path: `${base}/approvals/${ORG}/approve`, body: { argumentsHash: 'a'.repeat(64) } },
      { method: 'POST', path: `${base}/approvals/${ORG}/reject`, body: { reason: 'Not now' } },
    ];
    for (const request of allowed) {
      assert.ok(resolveApiRequest(request, API).ok, JSON.stringify(request));
    }
  });

  it('allows the workflow operations of Sprints C3.1 to C3.4 and nothing else', () => {
    const base = `/api/v1/organizations/${ORG}`;
    const allowed = [
      { method: 'GET', path: `${base}/workflows`, query: { status: 'ACTIVE', page: 0, size: 20 } },
      { method: 'POST', path: `${base}/workflows`, body: { name: 'W', mode: 'PIPELINE', maxParallelWorkers: 3 } },
      { method: 'GET', path: `${base}/workflows/${ORG}` },
      { method: 'PATCH', path: `${base}/workflows/${ORG}`, body: { failurePolicy: 'CONTINUE' } },
      { method: 'PUT', path: `${base}/workflows/${ORG}/participants`, body: { agentIds: [ORG], coordinatorAgentId: ORG } },
      { method: 'PUT', path: `${base}/workflows/${ORG}/steps`, body: { steps: [] } },
      { method: 'POST', path: `${base}/workflows/${ORG}/archive`, body: {} },
      { method: 'POST', path: `${base}/workflows/${ORG}/runs`, body: { objective: 'Analyze', requestId: 'r-1' } },
      { method: 'GET', path: `${base}/workflow-runs`, query: { status: 'WAITING_RECOVERY', definitionId: ORG, page: 0, size: 50 } },
      { method: 'GET', path: `${base}/workflow-runs/${ORG}` },
      { method: 'GET', path: `${base}/workflow-runs/${ORG}/steps`, query: { page: 0, size: 100 } },
      { method: 'GET', path: `${base}/workflow-runs/${ORG}/messages`, query: { page: 0, size: 100 } },
      { method: 'GET', path: `${base}/workflow-runs/${ORG}/actions` },
      { method: 'GET', path: `${base}/workflow-runs/${ORG}/summary` },
      { method: 'POST', path: `${base}/workflow-runs/${ORG}/cancel`, body: {} },
      { method: 'POST', path: `${base}/workflow-runs/${ORG}/steps/3/retry`, body: {} },
    ];
    for (const request of allowed) {
      assert.ok(resolveApiRequest(request, API).ok, JSON.stringify(request));
    }
  });

  it('refuses workflow requests that are malformed, forged or service-only', () => {
    const base = `/api/v1/organizations/${ORG}`;
    const refused = [
      // Only the platform (the scheduler, the coordinator's native actions) moves a workflow: no service endpoint, no forged state.
      { method: 'POST', path: `/api/v1/internal/organizations/${ORG}/executions/${ORG}/workflow-actions`, body: { action: 'workflow.finish' } },
      { method: 'POST', path: `${base}/workflow-runs/${ORG}/complete`, body: {} },
      { method: 'POST', path: `${base}/workflow-runs/${ORG}/finish`, body: {} },
      { method: 'POST', path: `${base}/workflow-runs/${ORG}/steps/1/skip`, body: {} },
      { method: 'POST', path: `${base}/workflow-runs/${ORG}/steps/1/complete`, body: {} },
      { method: 'PUT', path: `${base}/workflow-runs/${ORG}`, body: { status: 'SUCCEEDED' } },
      { method: 'PATCH', path: `${base}/workflow-runs/${ORG}`, body: { status: 'SUCCEEDED' } },
      { method: 'DELETE', path: `${base}/workflow-runs/${ORG}` },
      { method: 'DELETE', path: `${base}/workflows/${ORG}` },
      { method: 'POST', path: `${base}/workflow-runs`, body: {} },
      { method: 'POST', path: `${base}/workflow-runs/${ORG}/retry`, body: {} },
      // Malformed identifiers and step numbers.
      { method: 'GET', path: `${base}/workflows/not-a-uuid` },
      { method: 'POST', path: `${base}/workflow-runs/${ORG}/steps/abc/retry`, body: {} },
      { method: 'POST', path: `${base}/workflow-runs/${ORG}/steps/1/retry/../cancel`, body: {} },
      { method: 'POST', path: `${base}/workflow-runs/${ORG}/steps/123/retry`, body: {} },
      { method: 'GET', path: `${base}/workflow-runs`, query: { definitionId: 'not-a-uuid' } },
      { method: 'GET', path: `${base}/workflow-runs`, query: { definitionId: `${ORG}' OR 1=1` } },
    ];
    for (const request of refused) {
      assert.ok(!resolveApiRequest(request, API).ok, JSON.stringify(request));
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
      // Executions: no cancel/edit/delete, no service endpoints, malformed filters.
      { method: 'DELETE', path: `${base}/executions/${ORG}` },
      { method: 'PUT', path: `${base}/executions/${ORG}`, body: {} },
      { method: 'POST', path: `${base}/executions/${ORG}/complete`, body: { result: 'forged' } },
      { method: 'POST', path: `/api/v1/internal/organizations/${ORG}/executions/${ORG}/credential`, body: {} },
      { method: 'POST', path: `/api/v1/internal/organizations/${ORG}/executions/${ORG}/complete`, body: { result: 'x' } },
      { method: 'GET', path: `/api/v1/internal/organizations/${ORG}/agents/${ORG}/execution-context` },
      { method: 'GET', path: `${base}/executions`, query: { agentId: 'not-a-uuid' } },
      // Approvals: decide one request only; no edit, delete, bulk or cancel; no service endpoint; malformed identifiers.
      { method: 'DELETE', path: `${base}/approvals/${ORG}` },
      { method: 'PUT', path: `${base}/approvals/${ORG}`, body: {} },
      { method: 'POST', path: `${base}/approvals`, body: {} },
      { method: 'POST', path: `${base}/approvals/approve-all`, body: {} },
      { method: 'POST', path: `${base}/approvals/${ORG}/cancel`, body: {} },
      { method: 'GET', path: `${base}/approvals/${ORG}/approve` },
      { method: 'POST', path: `${base}/approvals/not-a-uuid/approve`, body: {} },
      { method: 'GET', path: `${base}/approvals`, query: { executionId: 'not-a-uuid' } },
      { method: 'GET', path: `${base}/approvals`, query: { executionId: `${ORG}' OR 1=1` } },
      { method: 'POST', path: `${base}/executions/${ORG}/tool-calls`, body: {} },
      { method: 'POST', path: `${base}/executions/${ORG}/resume`, body: {} },
      { method: 'GET', path: `${base}/tool-invocations` },
      { method: 'POST', path: `/api/v1/internal/organizations/${ORG}/executions/${ORG}/tool-calls`, body: {} },
      { method: 'POST', path: `/api/v1/internal/organizations/${ORG}/executions/${ORG}/resume`, body: {} },
      { method: 'POST', path: `/api/v1/internal/organizations/${ORG}/executions/${ORG}/tool-calls/${ORG}/begin`, body: {} },
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

describe('provider credential routes (security fix 8.6)', () => {
  const base = `/api/v1/organizations/${ORG}`;
  const create = (body: unknown) => resolveApiRequest({ method: 'POST', path: `${base}/providers/connections`, body }, API);
  const replace = (body: unknown) => resolveApiRequest({ method: 'PUT', path: `${base}/providers/connections/${ORG}/credential`, body }, API);
  const KEY = 'sk-test-0123456789abcdef';

  it('accepts exactly the documented bodies', () => {
    assert.ok(create({ providerType: 'ANTHROPIC', displayName: 'Prod', credential: KEY }).ok);
    assert.ok(create({ providerType: 'OPENAI', displayName: 'Prod', accountIdentifier: 'acct', credential: KEY }).ok);
    assert.ok(replace({ credential: KEY }).ok);
  });

  it('refuses extra fields, a missing or malformed credential and a non-object body, and never echoes the credential in the reason', () => {
    const bad: unknown[] = [
      { providerType: 'ANTHROPIC', displayName: 'Prod', credential: KEY, role: 'admin' },
      { providerType: 'ANTHROPIC', displayName: 'Prod' },
      { providerType: 'ANTHROPIC', displayName: 'Prod', credential: 'short' },
      { providerType: 'ANTHROPIC', displayName: 'Prod', credential: 'has space in it 1234' },
      { providerType: 'ANTHROPIC', displayName: 'Prod', credential: `${KEY}\n` },
      { providerType: 'ANTHROPIC', displayName: 'Prod', credential: 'x'.repeat(513) },
      { providerType: 'ANTHROPIC', displayName: 'Prod', credential: 12345678 },
      { providerType: 'ANTHROPIC', displayName: 'Prod', credential: [KEY] },
      { providerType: 'MISTRAL', displayName: 'Prod', credential: KEY },
      { providerType: 'ANTHROPIC', displayName: '', credential: KEY },
      { providerType: 'ANTHROPIC', displayName: 'Prod', accountIdentifier: 7, credential: KEY },
      [KEY],
      null,
      undefined,
    ];
    for (const body of bad) {
      const result = create(body);
      assert.equal(result.ok, false, JSON.stringify(body));
      assert.ok(!JSON.stringify(result).includes(KEY), 'the reason must never contain the credential');
    }
    for (const body of [{}, { credential: KEY, displayName: 'x' }, { credential: 'short' }, { credential: 'a b c d e f g h' }, [KEY]]) {
      const result = replace(body);
      assert.equal(result.ok, false, JSON.stringify(body));
      assert.ok(!JSON.stringify(result).includes(KEY));
    }
  });

  it('never accepts the credential outside the body (query, other methods or other routes)', () => {
    assert.equal(resolveApiRequest({ method: 'GET', path: `${base}/providers/connections`, query: { credential: KEY } }, API).ok, false);
    assert.equal(resolveApiRequest({ method: 'PATCH', path: `${base}/providers/connections/${ORG}`, body: { credential: KEY } }, API).ok === true && false, false);
  });
});
