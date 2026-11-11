# AI Workforce Platform — Desktop (Phase A)

Desktop control center for a hybrid human + AI workforce, built with **Angular 22** (standalone components, signals, zoneless) and **Electron 45**.

Phase A is a frontend-only build: every screen runs on **mock data held in memory**. Nothing is sent to a server, no AI agent executes anything, and approval decisions are recorded locally only. The UI says so wherever it matters.

## Layout

```
apps/desktop/
├── package.json              Electron, packaging and orchestration scripts
├── scripts/launch-electron.cjs  Starts Electron with a clean environment
├── electron/
│   ├── tsconfig.json
│   └── src/
│       ├── main.ts               Window, lifecycle, single instance, menu
│       ├── preload.ts            contextBridge: typed `window.desktop` API
│       ├── ipc.ts                IPC handlers (sender + input validation)
│       ├── policy.ts             Pure security rules (URLs, paths, CSP), unit-tested
│       ├── security.ts           Session hardening, navigation/window restrictions
│       ├── renderer-protocol.ts  app://workforce protocol serving the Angular build
│       ├── config.ts             Paths, dev-server resolution
│       └── shared/desktop-api.ts IPC contract (types only)
│   └── test/policy.test.ts       node:test suite for policy.ts
└── ui/                       Angular application
    └── src/
        ├── environments/         Data source + API base URL (no secrets)
        ├── styles/               Design tokens, base, controls, page primitives, overlays
        ├── testing/              TestBed helpers for the mock data layer
        └── app/
            ├── core/
            │   ├── models/       Domain interfaces (Organization, Agent, Task, Approval…)
            │   ├── data/api/     Abstract API contracts (one per domain)
            │   ├── data/mock/    Mock implementations + fixtures (2 organizations)
            │   ├── state/        Signal stores per domain (organization-scoped)
            │   ├── services/     Toasts, confirmations, preferences
            │   └── desktop/      Typed bridge to Electron, browser fallback
            ├── shared/           Design-system components, directives, pipes, utils
            ├── layout/           Shell: sidebar, topbar, global search, hosts
            └── features/         dashboard, agents, organization, projects,
                                  communication, activity-center, settings, profile, help
```

## Prerequisites

- Node.js 24 and npm 11

## Install

```bash
cd apps/desktop
npm install            # Electron + tooling
npm run ui:install     # Angular application
```

npm 11 does not run package install scripts by default. Electron downloads its binary on first launch, so the first start takes a little longer.

## Run

| Goal | Command (from `apps/desktop`) |
| --- | --- |
| Angular in the browser (http://localhost:4200) | `npm run ui:start` |
| Desktop app with live reload (Angular dev server + Electron) | `npm run dev` |
| Desktop app on the production build | `npm start` |
| All tests (Electron policy + Angular) | `npm test` |
| Angular unit tests only (Vitest) | `npm run ui:test` |
| Electron security policy tests only (node:test) | `npm run electron:test` |
| Production build (Angular + Electron main) | `npm run build` |
| Unpacked desktop build (`release/win-unpacked`) | `npm run package:dir` |
| Installer (NSIS on Windows, DMG on macOS, AppImage on Linux) | `npm run package` |

In development, DevTools open with **F12** or **Ctrl+Shift+I**. They are disabled in packaged builds.

To run the Angular dev server on another port, call the CLI directly (`cd ui && npx ng serve --port 4201`). `npm run ui:start -- --port 4201` does not forward the flag correctly.

Only one instance of the app can run per user profile. A second launch focuses the existing window and exits.

## Architecture notes

### Data layer
Components never touch data sources directly:

`feature component → signal store (core/state) → abstract API (core/data/api) → implementation`

`provideDataLayer(environment)` binds each abstract API to its **mock** implementation. To connect the Spring Boot Core API, add HTTP implementations of the same abstract classes, register them for `dataSource: 'api'`, and leave the features as they are. The base URL is only read through the `API_CONFIG` token.

`MessagingApi.incoming$` is the seam for the future WebSocket gateway. The mock never emits on it, and agents never fake a reply.

### State
Every list is organization-scoped: switching organization (sidebar → Workspace) reloads all stores. Responses of the previous organization are discarded.

Mutations go through the API, then update the store with `mutate(organizationId, …)`. The update is applied only if that organization is still selected and its list is loaded. Otherwise the server state is reloaded, so a late response never leaks into another organization.

Kanban moves are optimistic and roll back on error. Switching organization (`OrganizationSwitchService`) returns to the root of the current section. Loading, empty and error states are handled on every page.

### Identity vs. provider
An `Agent` has an `identity` (name, department, role, responsibilities, skills) and a separate list of `providers` bindings (primary and optional fallback). The provider or model can be changed without touching the identity.

## Electron security

- `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true` (plus `app.enableSandbox()`), `webviewTag: false`.
- The preload exposes only `window.desktop.getAppInfo()` and `window.desktop.openExternal(url)`. There is no raw IPC, Node.js, filesystem or process access.
- IPC handlers reject calls that do not come from the main frame of an application page, or that carry unexpected arguments. `openExternal` only accepts `https:` URLs on an allowlist, without embedded credentials.
- Navigation away from the app (top frame and sub-frames) is blocked, new windows are denied, and every permission request is refused.
- Packaged builds refuse to start with debugging switches (`--remote-debugging-port`, `--inspect`, …). Electron fuses disable `ELECTRON_RUN_AS_NODE`, `NODE_OPTIONS` and `--inspect`, enforce asar integrity, and only load the app from the asar archive.
- Content Security Policy: `script-src 'self'`, no inline scripts, no `eval`, `connect-src 'self'`. It is applied to the `app://` protocol responses (production) and to dev-server responses.
- The production renderer is served from a privileged `app://workforce` scheme with path-traversal protection and an SPA fallback, so Angular deep links work in packaged builds.
- No credentials, tokens or provider secrets are stored in the client. `localStorage` only holds UI preferences.

## Known limitations

- Mock data only: changes last for the current session and are lost on restart.
- No authentication. The signed-in user is a fixture.
- Agents do not execute tasks or reply, and approvals are not delivered to agents.
- Voice messages, calls and attachments are shown as disabled “coming soon” controls.
- The default Electron icon is used, and installers are unsigned.
