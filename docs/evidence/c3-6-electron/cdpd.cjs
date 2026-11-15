// CDP daemon: attaches to the REAL Electron renderer, records console/exception/log/CSP events, and serves commands on 127.0.0.1:9444
//   POST /eval  (body = JS expression)        -> JSON result
//   GET  /shot?name=x[&full=1]                -> saves PNG to SHOTS dir
//   POST /viewport (json {w,h,dark})          -> Emulation override
//   POST /key (json {key})                    -> Input.dispatchKeyEvent
//   GET  /events?since=N                      -> recorded events
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const CDP_PORT = process.argv[2];
const SHOTS = process.argv[3];
fs.mkdirSync(SHOTS, { recursive: true });
const events = [];
let ws, id = 0;
const pending = new Map();
const send = (method, params = {}) => new Promise((resolve) => { const i = ++id; pending.set(i, resolve); ws.send(JSON.stringify({ id: i, method, params })); });
async function connect() {
  for (let i = 0; i < 80; i++) {
    try {
      const targets = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json`)).json();
      const page = targets.find((t) => t.type === 'page');
      if (page) {
        ws = new WebSocket(page.webSocketDebuggerUrl);
        await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
        ws.onmessage = (m) => {
          const d = JSON.parse(m.data);
          if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); return; }
          const p = d.params || {};
          if (d.method === 'Runtime.consoleAPICalled') events.push({ k: 'console', level: p.type, text: (p.args || []).map((a) => a.value ?? a.description ?? '').join(' ').slice(0, 400) });
          else if (d.method === 'Runtime.exceptionThrown') events.push({ k: 'exception', text: (p.exceptionDetails?.exception?.description || p.exceptionDetails?.text || '').slice(0, 500) });
          else if (d.method === 'Log.entryAdded') events.push({ k: 'log', level: p.entry.level, source: p.entry.source, text: (p.entry.text || '').slice(0, 400), url: p.entry.url });
          else if (d.method === 'Page.frameNavigated') events.push({ k: 'navigated', url: p.frame.url });
          else if (d.method === 'Page.javascriptDialogOpening') events.push({ k: 'dialog', text: p.message });
        };
        for (const m of ['Runtime.enable', 'Log.enable', 'Page.enable', 'Network.enable']) await send(m);
        events.push({ k: 'attached', url: page.url });
        return;
      }
    } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error('no page target');
}
const body = (req) => new Promise((r) => { let b = ''; req.on('data', (c) => (b += c)); req.on('end', () => r(b)); });
http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x');
  const out = (o, code = 200) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(o)); };
  try {
    if (u.pathname === '/eval') {
      const r = await send('Runtime.evaluate', { expression: await body(req), awaitPromise: true, returnByValue: true });
      return out(r.result?.exceptionDetails ? { error: r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text } : { value: r.result?.result?.value });
    }
    if (u.pathname === '/shot') {
      const r = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: u.searchParams.get('full') === '1' });
      const file = path.join(SHOTS, u.searchParams.get('name') + '.png');
      fs.writeFileSync(file, Buffer.from(r.result.data, 'base64'));
      return out({ file, bytes: fs.statSync(file).size });
    }
    if (u.pathname === '/viewport') {
      const { w, h, dark } = JSON.parse(await body(req));
      if (w) await send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: false });
      else await send('Emulation.clearDeviceMetricsOverride');
      await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: dark ? 'dark' : 'light' }] });
      return out({ ok: true });
    }
    if (u.pathname === '/key') {
      const { key } = JSON.parse(await body(req));
      await send('Input.dispatchKeyEvent', { type: 'keyDown', key });
      await send('Input.dispatchKeyEvent', { type: 'keyUp', key });
      return out({ ok: true });
    }
    if (u.pathname === '/events') return out({ total: events.length, events: events.slice(Number(u.searchParams.get('since') || 0)) });
    if (u.pathname === '/reconnect') { await connect(); return out({ ok: true }); }
    out({ error: 'unknown' }, 404);
  } catch (e) { out({ error: String(e) }, 500); }
}).listen(9444, '127.0.0.1');
connect().then(() => console.log('cdpd attached')).catch((e) => { console.error(e); process.exit(1); });
