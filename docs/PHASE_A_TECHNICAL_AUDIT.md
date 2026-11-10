# Phase A — Technical Audit (Desktop Application)

**Scope:** `apps/desktop` (Angular 22 UI + Electron 45 shell).
**Date:** 2026-11-10.
**Method:** code inspection, failing tests written before each fix, real command execution, and end-to-end checks driven over the Chrome DevTools Protocol against the running Electron app (development build, production build and packaged build).

Every result marked **Verified** was observed while running the application or the test suites. Items marked **Recommendation** were not executed.

---

## 1. Overall assessment

Phase A is **functionally complete and ready to start Core API integration**, once the fixes in this audit are in place. They are all in the repository and covered by tests.

- **Architecture.** The layering (component → signal store → abstract API → implementation) holds. No component references the mock layer. Replacing the mocks with HTTP implementations only touches `provideDataLayer`.
- **Most significant defect.** A **High** organization-isolation bug: a mutation response arriving after an organization switch could display another organization's data, or cancel the new organization's load. It is fixed and covered by regression tests.
- **Electron security.** The core settings were correct from the start. The audit added defence in depth for packaged builds: debugging switches refused, Electron fuses, and stricter IPC sender checks.
- **Status.** All official commands succeed: `npm run ui:test`, `npm run build`, `npm run package:dir` and `npm test`. There are no console errors and no unexpected CSP violations during the functional run.

| Severity | Found | Fixed | Remaining |
| --- | --- | --- | --- |
| Critical | 0 | 0 | 0 |
| High | 1 | 1 | 0 |
| Medium | 5 | 5 | 0 |
| Low | 8 | 6 | 2 |
| Informational | 7 | 1 | 6 |

---

## 2. Architecture findings

### Verified
| Check | Result |
| --- | --- |
| Standalone components, `OnPush` everywhere | Yes |
| Lazy-loaded routes | Every feature (`loadComponent` / `loadChildren`). The production build emits one chunk per page. |
| Components never access mock repositories | Only `core/data/provide-data-layer.ts` and `core/data/mock/**` reference mock classes (grep). |
| Swapping mocks for HTTP needs no feature changes | Features inject stores; stores inject the abstract classes `WorkspaceApi`, `OrganizationApi`, `AgentApi`, `ProjectApi`, `MessagingApi`, `SupervisionApi`. |
| TypeScript strictness | `strict`, `noUnusedLocals/Parameters`, `noImplicitReturns`, `strictTemplates`, `strictStandalone`. No `any` and no `$any` in application code (grep). One template non-null assertion (`role()!`), guarded by `@if`. |
| Subscription cleanup | All manual subscriptions use `takeUntilDestroyed`. Document listeners (dropdown, tooltip) are removed through `DestroyRef`. |
| Form validation | Typed reactive forms with required, length, range, pattern and min-items validators. Errors are shown after touch or submit. |
| Error handling | Each list exposes `loading` and `error`. Pages render skeleton, empty and error states with Retry. Mutations surface errors as toasts. |

### Findings
| ID | Severity | Finding | Status |
| --- | --- | --- | --- |
| A-01 | **High** | **Cross-organization leak through late mutation responses.** `mutate()` applied every mutation result to whatever list was current when the response arrived. If an agent (or task, message, department, decision…) was created in org A and the user switched to org B before the response: (a) if B's list was still loading, the local write **cancelled B's load** and the screen showed **only A's agent**; (b) if B had loaded, A's agent was **appended to B's list**. Reproduced by `organization-isolation.spec.ts` (2 failing tests before the fix). | **Fixed.** `OrgCollection.mutate(organizationId, …)` only applies when that organization is still selected and its list is `resolved`/`local`. Otherwise the pending server load wins. All stores capture the organization before awaiting. |
| A-02 | Medium | **Approval decisions could be overwritten.** Neither the store nor the mock API checked `status === 'pending'`, so a second decision replaced the first. | **Fixed.** The store refuses non-pending or foreign approvals. The mock API returns an error (expected 409 behaviour). Tested. |
| A-03 | Medium | **Store actions accepted entities from another organization** (send a message to another organization's conversation, decide another organization's approval). | **Fixed.** Ownership guards in `CommunicationStore.send`, `SupervisionStore.decide` and realtime `incoming$`. Tested. |
| A-04 | Medium | **Stale selections after switching organization.** Detail pages (`/agents/:id`, `/projects/:id`) showed "not found", and the Agents page kept department and role filters pointing at the previous organization's IDs, which produced a misleading empty list. | **Fixed.** `OrganizationSwitchService` returns to the section root. The Agents page resets organization-specific filters. Verified end to end. |
| A-05 | Informational | Late **list** responses from the previous organization are discarded by `rxResource`, and a failed load shows an empty list plus an error, never stale data. | **Verified, no change needed** (2 tests). |
| A-06 | Informational | Mock APIs do not enforce organization ownership on `update*` endpoints (IDs are global in the fixture). | **Recommendation:** the Core API must enforce tenant scoping server-side (see §10). |
| A-07 | Informational | The "Active AI agents" KPI counts every agent that is not `offline`, including `idle` and `error`. | Product decision. Unchanged. |

---

## 3. Security findings (Electron)

### Verified baseline
| Control | Evidence |
| --- | --- |
| `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`, `webviewTag: false`, `webSecurity: true` | `main.ts`. In the page, `typeof require/process/module` are all `undefined`. |
| `app.enableSandbox()` before `ready` | `main.ts` |
| Preload exposes only `getAppInfo`, `openExternal` | `Object.keys(window.desktop)` returns exactly these two. The object is frozen. No raw `ipcRenderer`. |
| External URL allowlist | `openExternal('https://evil.example/')` and `file:///…/calc.exe` both return `not-allowed`. |
| New windows denied | `window.open('https://evil.example/')` returns `null`. |
| CSP enforced | Inline script blocked (`script-src-elem`). Page-originated string eval blocked (`script-src`). `fetch('https://example.com')` blocked (`connect-src`). Checked in production, development and packaged builds. |
| `app://` protocol | Standard + secure scheme. Deep links (`/agents/deep/link`) return 200 with index.html. Missing assets return 404. Every response carries the CSP. |
| Path traversal | `..%2f`, `..%5c` → 403. `%00` → 400. A foreign host is blocked. Dot segments are normalised by the URL parser and stay inside the root. Checked through the real protocol and in unit tests. |
| Permissions | All requests, checks and device permissions are denied (`hardenSession`). |

### Findings
| ID | Severity | Finding | Status |
| --- | --- | --- | --- |
| S-01 | Medium | **Packaged build accepted `--remote-debugging-port`.** This exposes a DevTools endpoint that can drive the renderer, including the desktop bridge. Observed during the initial Phase A verification. | **Fixed.** Packaged builds exit (code 1) on `remote-debugging-port/pipe`, `inspect`, `inspect-brk`, `remote-allow-origins`. Verified: exit 1 and no listening port. |
| S-02 | Medium | **Electron fuses not configured.** `ELECTRON_RUN_AS_NODE`, `NODE_OPTIONS` and `--inspect` could turn the packaged binary into a Node.js runtime. | **Fixed.** `electronFuses`: `runAsNode`, `enableNodeOptionsEnvironmentVariable` and `enableNodeCliInspectArguments` are disabled; `enableEmbeddedAsarIntegrityValidation`, `onlyLoadAppFromAsar` and `enableCookieEncryption` are enabled; `grantFileProtocolExtraPrivileges` is disabled. Verified: with `ELECTRON_RUN_AS_NODE=1` the packaged app still starts as the app. |
| S-03 | Low | **Second instance kept initializing.** `app.quit()` is asynchronous, so the `whenReady` handler of a second instance still ran. | **Fixed.** Guarded by `isPrimaryInstance`. Verified: the second instance exits with code 0 and opens no window. |
| S-04 | Low | **IPC sender check accepted any frame with an app URL** and ignored extra arguments. | **Fixed.** Only the main frame of an application page is trusted, and unexpected argument counts are rejected. |
| S-05 | Low | **Sub-frame navigation not restricted** (`will-frame-navigate`). | **Fixed.** |
| S-06 | Low | **Protocol edge cases:** NUL bytes and non-GET methods were not explicitly rejected, and error responses had no CSP header. | **Fixed:** 400 / 405, and CSP on every response. Verified. |
| S-07 | Informational | **Security rules had no automated tests.** | **Fixed.** Pure rules moved to `electron/src/policy.ts` (no Electron import), with 9 `node:test` tests (`npm run electron:test`). |
| S-08 | Low | `style-src 'unsafe-inline'` is required by Angular's runtime component styles. | **Remaining.** Accepted risk: no inline scripts are allowed. Possible later: CSP nonces via `ngCspNonce`. |
| S-09 | Informational | The `DesktopBridge` contract is duplicated (`electron/src/shared/desktop-api.ts` and `ui/src/app/core/desktop/desktop-api.ts`). | **Remaining.** Must be kept in sync manually. |

No security setting was weakened during the audit.

---

## 4. Functional testing results

All checks below are **Verified**. They ran on the production build loaded through `app://`, at 1440×900 (plus 1100×720 and 1920×1080 in the earlier Phase A run), against a separate user profile.

| Area | Checks | Result |
| --- | --- | --- |
| Navigation | 12 routes, including all 4 settings sections and the 404 page. Each renders its heading. Deep-link reload works. Breadcrumbs and titles are correct. | PASS |
| Dashboard | KPIs, projects table, approvals, agent status, activity, meetings, reports | PASS |
| Agents | Search by skill; status and department filters; clear filters; empty state; grid/table toggle; sort; create with validation (9 errors shown); created agent detail; edit | PASS |
| Organization | Tree navigation; organization chart; member filter (people/AI); employee modal; department creation | PASS |
| Projects | Kanban **drag and drop with real DragEvents** (ORB-106 → Done); move via card menu; task form validation; task creation (key ORB-111); task edit (priority); task list sorting | PASS |
| Communication | Send disabled when empty; Shift+Enter keeps the draft; Enter sends; message marked "Local only"; **no fabricated agent reply** | PASS |
| Activity Center | Cancel keeps the request pending; reject 5 → 4; decided cards are read-only; execution status filter (11 → 1) | PASS |
| Settings / Profile | Save disabled when pristine; required-name validation; save propagates to the sidebar; profile save | PASS |
| Multi-organization | Switching from `/agents/agt-nova` lands on `/agents` with only Helix agents (Muse, Tempo); department filter reset; a Northwind project URL shows "Project not found" in Helix | PASS |
| Decorative controls | The 4 "coming soon" controls (attach, voice, call, video) are all `aria-disabled`, labelled and have a tooltip. No other inert control was found. | PASS |
| Console | 39/39 audit checks with **no console errors or warnings** from the application | PASS |

Unit and integration tests (Vitest, 27): agent filtering, stores, pipes, URL allowlist, **organization isolation** (5), and approval and message ownership guards.

---

## 5. Commands executed

| Command | Result |
| --- | --- |
| `npm run ui:test` | ✅ 5 files, **27/27** passed |
| `npm run electron:test` | ✅ **9/9** passed |
| `npm test` (both) | ✅ exit 0 |
| `npm run build` | ✅ exit 0. Initial bundle 441.7 kB (114.9 kB transferred). No budget warning. |
| `npm run package:dir` | ✅ exit 0. Fuses applied, asar integrity updated. `release/win-unpacked/resources/app.asar` |
| Packaged binary | ✅ Starts on "Overview"; refuses `--remote-debugging-port` (exit 1); ignores `ELECTRON_RUN_AS_NODE`; single instance enforced |
| Dev startup (`ng serve` + Electron with `--dev-server-url`) | ✅ 5 routes loaded, no errors, dev CSP active (eval blocked). Run on port 4201 because the user's own `npm run dev` occupied port 4200. |
| `wait-on http-get://localhost:4200` (used by `npm run dev`) | ✅ exit 0 |
| `npm audit` (ui) | ✅ 0 vulnerabilities |
| `npm audit` (desktop tooling) | ⚠️ 6 moderate in `minimatch` via `electron-builder` (build-time only; the packaged app contains no `node_modules`) |

**Not executed:** `npm run package` (NSIS installer) and macOS/Linux targets.

---

## 6. Bugs discovered

A-01, A-02, A-03, A-04, S-01, S-02, S-03, S-04, S-05, S-06, plus:

| ID | Severity | Finding |
| --- | --- | --- |
| B-01 | Low | `electron/tsconfig.json` used the deprecated `moduleResolution: node10`, silenced with `ignoreDeprecations`. |
| B-02 | Low | `electron/dist` was never cleaned, so files from removed modules could be packaged. |
| B-03 | Informational | `npm run ui:start -- --port <n>` does not forward the port to the Angular CLI. |

## 7. Bugs fixed

All of the above except B-03, which is documented in `apps/desktop/README.md`.

| Fix | Files |
| --- | --- |
| Organization-scoped mutations | `core/state/collection.ts`, all `*.store.ts` |
| Approval finality and ownership guards | `supervision.store.ts`, `mock-supervision.api.ts`, `communication.store.ts` |
| Organization switch navigation and filter reset | `core/services/organization-switch.service.ts`, `layout/sidebar/sidebar.ts`, `features/profile/profile-page.ts`, `features/agents/agents-page.ts` |
| Packaged debugging refusal, IPC main-frame check, sub-frame navigation, single instance | `electron/src/security.ts`, `ipc.ts`, `main.ts` |
| Fuses | `apps/desktop/package.json` (`build.electronFuses`) |
| Testable security policy, protocol hardening | `electron/src/policy.ts`, `renderer-protocol.ts`, `electron/test/policy.test.ts` |
| Build hygiene | `electron/tsconfig.json` (`node16`), `electron:clean`, `electron:test`, `test` scripts |
| New regression tests | `core/state/organization-isolation.spec.ts`, additions to `stores.spec.ts` |

---

## 8. Remaining known limitations

| Severity | Item |
| --- | --- |
| Low | The sidebar "Settings" item is highlighted only on the General section. |
| Low | `style-src 'unsafe-inline'` (see S-08). |
| Informational | Mock data only, lost on restart. No authentication. Agents never execute or reply. Approvals are not delivered to agents. |
| Informational | The Kanban scrolls horizontally at 1440 px (6 columns). |
| Informational | Default Electron icon. Installers unsigned. NSIS, DMG and AppImage not built during the audit. |
| Informational | Build-tooling advisories (`minimatch`), not shipped. |
| Informational | The DesktopBridge contract is duplicated across the two packages (S-09). |

---

## 9. Production build status

| Item | Status |
| --- | --- |
| Angular production config | AOT, optimization on, `inlineCritical: false` (CSP-compatible), output hashing, budgets respected (441.7 kB / 600 kB warning) |
| Environments | `environment.ts` / `environment.development.ts`. API and WebSocket URLs only, no secrets. Only read through `API_CONFIG`. |
| Asset paths | Base href `/` served by `app://workforce/`. Fonts and icons are bundled (no remote requests). |
| Deep links | Verified in production and packaged builds |
| Packaging | `electron-builder` 26.18: asar + integrity, fuses, NSIS/DMG/AppImage targets declared, `files` limited to `electron/dist/**/*.js` + renderer |
| Version compatibility | Node 24.18 (Angular requires `^24.15`) · TypeScript 6.0.3 (Angular requires `>=6.0 <6.1`) · Angular 22.2.5 · Electron 45.2.3 (Chromium 156) · Vitest 5.1 — **compatible** |
| Environment variables | `ELECTRON_RUN_AS_NODE` is neutralised by `scripts/launch-electron.cjs` in development and ignored by the packaged binary (fuse) |

**Status: production build and unpacked packaging verified. Signed installers not yet produced.**

---

## 10. Recommendations before Spring Boot integration

These are recommendations; none of them was executed.

1. **Contract first.** Write OpenAPI specs in `contracts/openapi` for the six API areas, matching `core/models`, and generate or validate TypeScript types from them in CI.
2. **HTTP implementations.** Add `Http*Api` classes extending the abstract APIs and register them in `provideDataLayer` for `dataSource: 'api'`. Keep the mocks for tests and offline demos.
3. **Tenant isolation on the server.** Every endpoint must scope by organization from the authenticated principal, not from client-supplied IDs (A-06). Return 404/403 for foreign IDs.
4. **Idempotent and final decisions.** `POST /approvals/{id}/decision` should return **409** when the request is no longer pending. The client already handles this as an error (A-02).
5. **Authentication.** Add OIDC with PKCE through the system browser. Store tokens in main-process memory or the OS keychain (`safeStorage`), never in `localStorage`. Attach them with an HTTP interceptor. A refresh flow will need an explicit, validated IPC capability.
6. **CSP update.** Add the Core API and WebSocket origins to `connect-src` (production and development builders in `policy.ts`), and cover them with a test.
7. **Realtime.** Implement `MessagingApi.incoming$` on WebSocket/STOMP. The store already drops messages for foreign conversations. Add reconnect with backoff and an ordering key.
8. **Error model.** Standardise on RFC 7807 problem details, and map them in the HTTP layer to the `Error` messages the UI already surfaces.
9. **Pagination.** Activity, executions and messages will grow: add cursor pagination to the APIs before connecting real data.
10. **E2E in CI.** Port the CDP checks used in this audit to Playwright (`_electron.launch`) and run them on every pull request, together with `npm test` and `npm run package:dir`.
11. **Release.** Add an application icon, Windows code signing and macOS notarization before distributing installers.
