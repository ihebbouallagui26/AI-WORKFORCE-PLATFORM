import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';

/**
 * Static checks of the renderer-facing surface: the preload exposes a fixed object, never the
 * IPC primitives, and no contract type carries a token. Complements the runtime sender checks.
 */
const SRC = path.resolve(__dirname, '..', '..', 'src');
const read = (file: string) => fs.readFileSync(path.join(SRC, file), 'utf8');

describe('renderer IPC surface', () => {
  const preload = read('preload.ts');
  const contract = read(path.join('shared', 'desktop-api.ts'));

  it('exposes a single frozen bridge and no IPC primitive', () => {
    assert.equal(preload.match(/exposeInMainWorld\(/g)?.length, 1);
    assert.match(preload, /exposeInMainWorld\('desktop', Object\.freeze\(bridge\)\)/);
    assert.doesNotMatch(preload, /exposeInMainWorld\([^)]*ipcRenderer/);
    assert.doesNotMatch(preload, /ipcRenderer\.send(Sync)?\(/);
    // Channels are fixed literals, never chosen by the page.
    const invocations = [...preload.matchAll(/ipcRenderer\.invoke\(/g)].length;
    const fixed = [...preload.matchAll(/ipcRenderer\.invoke\(channel\('[a-z]+:[a-z-]+'\)/g)].length;
    assert.ok(invocations >= 7);
    assert.equal(fixed, invocations);
  });

  it('does not forward the IPC event object to the page', () => {
    assert.match(preload, /\(_event: IpcRendererEvent, state: AuthSessionState\) => listener\(state\)/);
  });

  it('declares no token in any renderer-visible type', () => {
    const declarations = contract.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
    assert.doesNotMatch(declarations, /token/i);
  });

  it('keeps the renderer copy of the contract identical to the main-process one', () => {
    const renderer = fs.readFileSync(path.resolve(SRC, '..', '..', 'ui', 'src', 'app', 'core', 'desktop', 'desktop-api.ts'), 'utf8');
    const body = (text: string) => {
      const start = text.indexOf('export interface DesktopAppInfo');
      const end = text.search(/\/\*\* Every IPC channel|declare global/);
      return text.slice(start, end).replace(/\r\n/g, '\n').trim();
    };
    assert.equal(body(renderer), body(contract));
  });

  it('registers a handler for every declared channel', () => {
    const ipc = read('ipc.ts');
    const channels = [...contract.matchAll(/\| '([a-z]+:[a-z-]+)'/g)].map((m) => m[1]!)
      .filter((name) => name !== 'auth:session-changed');
    assert.ok(channels.length >= 7);
    for (const name of channels) {
      assert.match(ipc, new RegExp(`handle<[^>]*>\\('${name}'`), name);
    }
  });
});
