import assert from 'node:assert/strict';
import path from 'node:path';
import { describe, it } from 'node:test';
import {
  developmentCsp,
  isAllowedExternalUrl,
  isAppUrl,
  parseDevServerUrl,
  productionCsp,
  resolveRendererPath,
} from '../src/policy';

const ROOT = path.resolve('renderer-root');
const dev = new URL('http://localhost:4200');

describe('isAppUrl', () => {
  it('accepts the bundled origin and the dev server only', () => {
    assert.equal(isAppUrl('app://workforce/agents', null), true);
    assert.equal(isAppUrl('http://localhost:4200/agents', dev), true);
    assert.equal(isAppUrl('http://localhost:4200/agents', null), false);
    assert.equal(isAppUrl('app://evil/agents', null), false);
    assert.equal(isAppUrl('https://workforce/agents', null), false);
    assert.equal(isAppUrl('file:///C:/Windows/win.ini', dev), false);
    assert.equal(isAppUrl('http://localhost:4300/', dev), false);
    assert.equal(isAppUrl('not a url', dev), false);
  });
});

describe('isAllowedExternalUrl', () => {
  it('only allows https URLs on allowlisted hosts without credentials', () => {
    assert.equal(isAllowedExternalUrl('https://github.com/org/repo'), true);
    assert.equal(isAllowedExternalUrl('http://github.com/'), false);
    assert.equal(isAllowedExternalUrl('https://github.com.evil.example/'), false);
    assert.equal(isAllowedExternalUrl('https://user:pass@github.com/'), false);
    assert.equal(isAllowedExternalUrl('file:///C:/Windows/System32/calc.exe'), false);
    assert.equal(isAllowedExternalUrl('javascript:alert(1)'), false);
    assert.equal(isAllowedExternalUrl(42), false);
    assert.equal(isAllowedExternalUrl(''), false);
    assert.equal(isAllowedExternalUrl(`https://github.com/${'a'.repeat(3000)}`), false);
  });
});

describe('parseDevServerUrl', () => {
  it('accepts local http servers only', () => {
    assert.equal(parseDevServerUrl('http://localhost:4200')?.port, '4200');
    assert.equal(parseDevServerUrl('http://127.0.0.1:4200')?.hostname, '127.0.0.1');
    assert.equal(parseDevServerUrl('https://localhost:4200'), null);
    assert.equal(parseDevServerUrl('http://evil.example:4200'), null);
    assert.equal(parseDevServerUrl('garbage'), null);
  });
});

describe('resolveRendererPath', () => {
  const resolve = (url: string) => resolveRendererPath(ROOT, url);

  it('maps app URLs inside the renderer root', () => {
    assert.deepEqual(resolve('app://workforce/main-ABC.js'), {
      kind: 'file',
      filePath: path.join(ROOT, 'main-ABC.js'),
      isAsset: true,
    });
    assert.deepEqual(resolve('app://workforce/agents/agt-nova'), {
      kind: 'file',
      filePath: path.join(ROOT, 'agents', 'agt-nova'),
      isAsset: false,
    });
  });

  it('rejects path traversal hidden in encoded separators', () => {
    for (const attempt of [
      'app://workforce/..%2f..%2fpackage.json',
      'app://workforce/%2e%2e%2f%2e%2e%2fpackage.json',
      'app://workforce/..%5c..%5cpackage.json',
    ]) {
      assert.deepEqual(resolve(attempt), { kind: 'reject', status: 403 }, attempt);
    }
  });

  it('keeps URL-normalised dot segments inside the root', () => {
    // The WHATWG parser collapses `..` / `%2e%2e` segments before the path is read.
    for (const attempt of ['app://workforce/%2e%2e/%2e%2e/secret', 'app://workforce/../../secret']) {
      const result = resolve(attempt);
      assert.equal(result.kind, 'file', attempt);
      if (result.kind === 'file') {
        assert.ok(result.filePath.startsWith(ROOT + path.sep), result.filePath);
      }
    }
  });

  it('rejects foreign hosts, NUL bytes and malformed encodings', () => {
    assert.deepEqual(resolve('app://evil/index.html'), { kind: 'reject', status: 404 });
    assert.deepEqual(resolve('app://workforce/index.html%00.js'), { kind: 'reject', status: 400 });
    assert.deepEqual(resolve('app://workforce/%E0%A4%A'), { kind: 'reject', status: 400 });
  });
});

describe('Content Security Policy', () => {
  it('forbids inline scripts, eval and remote connections in production', () => {
    const csp = productionCsp();
    assert.match(csp, /script-src 'self'(;|$)/);
    assert.doesNotMatch(csp, /unsafe-eval/);
    assert.match(csp, /connect-src 'self'(;|$)/);
    assert.match(csp, /object-src 'none'/);
    assert.match(csp, /frame-ancestors 'none'/);
  });

  it('only adds the dev-server socket in development', () => {
    assert.match(developmentCsp(dev), /connect-src 'self' ws:\/\/localhost:4200/);
    assert.doesNotMatch(developmentCsp(dev), /unsafe-eval/);
  });
});
