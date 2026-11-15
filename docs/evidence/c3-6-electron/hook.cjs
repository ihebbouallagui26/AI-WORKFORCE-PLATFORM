// Loaded into the REAL Electron main process with NODE_OPTIONS=--require. It replaces ONLY the "open the system browser" step of the
// login (shell.openExternal) by a script that submits the Keycloak login form as a person would. Everything else (loopback server,
// PKCE, state/nonce, code exchange, token store, IPC) is the application's own code. Nothing secret is written to the log.
const fs = require('node:fs');
if (process.type === 'browser') {
  const LOG = process.env.C36_HOOK_LOG;
  const note = (o) => { try { fs.appendFileSync(LOG, JSON.stringify({ t: Date.now(), ...o }) + '\n'); } catch { /* ignore */ } };
  const install = () => {
    const { shell } = require('electron'); // not resolvable until Electron finished bootstrapping: retried below
    if (!shell || shell.__c36) { return !!shell; }
    const original = shell.openExternal.bind(shell);
    shell.openExternal = async (target) => {
      const url = new URL(String(target));
      const isAuth = url.pathname.endsWith('/auth');
      note({ event: 'openExternal', protocol: url.protocol, host: url.host, path: url.pathname, params: [...url.searchParams.keys()].sort(), isAuth });
      if (!isAuth || process.env.C36_REAL_BROWSER === '1') {
        return original(target);
      }
      const page = await fetch(url, { redirect: 'manual' });
      const cookies = page.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ');
      const m = /id="kc-form-login"[^>]*action="([^"]+)"/.exec(await page.text());
      if (!m) { note({ event: 'login-form-not-found', status: page.status }); return; }
      const submit = await fetch(m[1].replaceAll('&amp;', '&'), { method: 'POST', redirect: 'manual', headers: { Cookie: cookies, 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ username: process.env.C36_USER, password: process.env.C36_PW }) });
      const loc = submit.headers.get('location');
      note({ event: 'login-submitted', status: submit.status, redirectsToLoopback: !!loc && /^http:\/\/(127\.0\.0\.1|localhost)/.test(loc) });
      if (loc) { const cb = await fetch(loc); note({ event: 'callback-delivered', status: cb.status }); }
    };
    shell.__c36 = true;
    note({ event: 'hook-installed', pid: process.pid, electron: process.versions.electron, chrome: process.versions.chrome });
    return true;
  };
  const timer = setInterval(() => { try { if (install()) { clearInterval(timer); } } catch { /* electron not ready yet */ } }, 5);
}
