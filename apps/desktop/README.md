# AI Workforce Platform — Desktop

Desktop control center for a hybrid human + AI workforce, built with **Angular 22** (standalone components, signals, zoneless) and **Electron 45**.

Since Sprint B1 the desktop signs in with Keycloak (Authorization Code + PKCE in the system browser, driven by the Electron main process) and uses the Core API for the **user profile, organizations, members, roles and invitations** and, since Sprint B2, for the **workforce: employees, departments (hierarchy), positions and teams** (Organization section). Since Sprint B3, for **AI agents, model-provider connections, skills, tool permissions and team assignments** (AI agents section, `api` mode). Projects, communication and supervision still run on **demo data held in memory** (labelled “Demo data”), as does the whole agents demo of the `mock` build. No AI agent executes anything. See [`docs/architecture/DESKTOP_AUTHENTICATION_ARCHITECTURE.md`](../../docs/architecture/DESKTOP_AUTHENTICATION_ARCHITECTURE.md).

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
| Angular in the browser, demo data, no sign-in | `cd ui && npx ng serve --configuration mock` |
| Angular dev server for Electron (api mode) | `npm run ui:start` |
| Desktop app with live reload (Angular dev server + Electron) | `npm run dev` |
| Desktop app on the production build | `npm start` |
| All tests (Electron policy + Angular) | `npm test` (Electron main-process tests, then Angular: 64 specs) |
| Angular unit tests only (Vitest) | `npm run ui:test` |
| Electron security policy tests only (node:test) | `npm run electron:test` |
| Production build (Angular + Electron main) | `npm run build` |
| Unpacked desktop build (`release/win-unpacked`) | `npm run package:dir` |
| Installer (NSIS on Windows, DMG on macOS, AppImage on Linux) | `npm run package` |

In development, DevTools open with **F12** or **Ctrl+Shift+I**. They are disabled in packaged builds.

**Sign-in prerequisites (api mode):** the local stack (`docker compose ... up -d --wait`, see `infra/keycloak/README.md`) and the Core API on port 8081. Optional environment variables for the Electron process: `AIW_OIDC_ISSUER`, `AIW_OIDC_CLIENT_ID`, `AIW_API_BASE_URL`. Opt-in end-to-end test against the local Keycloak: `AIW_KEYCLOAK_ADMIN_PASSWORD=<from infra/docker/.env> npm run electron:test`.

To run the Angular dev server on another port, call the CLI directly (`cd ui && npx ng serve --port 4201`). `npm run ui:start -- --port 4201` does not forward the flag correctly.

Only one instance of the app can run per user profile. A second launch focuses the existing window and exits.

## Architecture notes

### Data layer
Components never touch data sources directly:

`feature component → signal store (core/state) → abstract API (core/data/api) → implementation`

`provideDataLayer(environment)` binds each abstract API to an implementation. With `dataSource: 'api'` (default) the workspace (`WorkspaceApi`) is backed by the Core API through `IdentityApi` → `BackendClient` → `window.desktop.api` (the main process adds the token); the other domains use the mock implementations through a demo scope (always the demo organization). With `dataSource: 'mock'` (`--configuration mock`, unit tests) everything is in memory and no sign-in is required.

`MessagingApi.incoming$` is the seam for the future WebSocket gateway. The mock never emits on it, and agents never fake a reply.

### Workforce (Sprint B2)
In `api` mode the **Organization** section (`/organization`) is the real workforce management (`features/workforce`): tabs *Structure* (department tree and detail), *Employees* (search, filters, pagination, department/position/manager/login assignment, status, teams), *Positions* (catalog and holders) and *Teams* (members, leader, department). Data comes from `WorkforceApi` → `BackendClient` → `window.desktop.api`; collections are kept by `WorkforceStore`, keyed by organization through `orgResource`, which tags every result with its organization and only exposes it while that organization is current (an answer arriving after an organization switch is never shown). Actions are shown according to the permissions the API reports for the current organization (`employee.*`, `department.*`, `position.*`, `team.*`); the API enforces them again. The Phase A demo Organization page remains for the `mock` build and the unit tests, and AI agents stay demo data (Phase B3).

### State
Every list is organization-scoped: switching organization (sidebar → Workspace) reloads all stores. Responses of the previous organization are discarded.

Mutations go through the API, then update the store with `mutate(organizationId, …)`. The update is applied only if that organization is still selected and its list is loaded. Otherwise the server state is reloaded, so a late response never leaks into another organization.

Kanban moves are optimistic and roll back on error. Switching organization (`OrganizationSwitchService`) returns to the root of the current section. Loading, empty and error states are handled on every page.

### Identity vs. provider
An `Agent` has an `identity` (name, department, role, responsibilities, skills) and a separate list of `providers` bindings (primary and optional fallback). The provider or model can be changed without touching the identity.

## Electron security

- `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true` (plus `app.enableSandbox()`), `webviewTag: false`.
- The preload exposes only `window.desktop.getAppInfo()`, `openExternal(url)`, `auth.{getSession, login, cancelLogin, logout, onSessionChanged}` and `api.request(...)`. There is no raw IPC, Node.js, filesystem, process or token access.
- OIDC runs in the main process (openid-client); the access token stays in main-process memory, the refresh token is encrypted with `safeStorage` (memory only when the OS cannot protect it). `api.request` only executes allowlisted Core API operations.
- IPC handlers reject calls that do not come from the main frame of an application page, or that carry unexpected arguments. `openExternal` only accepts `https:` URLs on an allowlist, without embedded credentials.
- Navigation away from the app (top frame and sub-frames) is blocked, new windows are denied, and every permission request is refused.
- Packaged builds refuse to start with debugging switches (`--remote-debugging-port`, `--inspect`, …). Electron fuses disable `ELECTRON_RUN_AS_NODE`, `NODE_OPTIONS` and `--inspect`, enforce asar integrity, and only load the app from the asar archive.
- Content Security Policy: `script-src 'self'`, no inline scripts, no `eval`, `connect-src 'self'`. It is applied to the `app://` protocol responses (production) and to dev-server responses.
- The production renderer is served from a privileged `app://workforce` scheme with path-traversal protection and an SPA fallback, so Angular deep links work in packaged builds.
- No client secret exists (public client). Tokens never reach the renderer; `localStorage` only holds UI preferences (including the last selected organization id, which the API re-authorizes on every call).

## Known limitations

- In `api` mode, AI agents, providers, skills, tool permissions and team assignments are real (Core API, Phase B3). In the `mock` build, and for projects, communication and supervision, the data is demo data: changes last for the current session only.
- Since Sprint C1 an active Anthropic-backed agent can be **run on request** from its detail page: the user writes an instruction and launches it; the run is asynchronous (the page polls), text generation only (no tool, shell, file or MCP action), and the result and history are read from the Core API. Nothing is ever started automatically, and no provider key is asked for or shown during a run. The Electron allowlist exposes only launch/list/read of executions, never the internal service endpoints.
- A provider API key is typed once in a write-only field, sent through the main process and cleared at once. It transits renderer memory and IPC before reaching the main process, and is never stored, displayed or returned. Real provider connectivity is not covered by automated tests (local fake servers only).
- The job title in the profile is local (the Core API stores only the display name).
- Agents do not execute tasks or reply, and approvals are not delivered to agents.
- Voice messages, calls and attachments are shown as disabled “coming soon” controls.
- The default Electron icon is used, and installers are unsigned.
