# Desktop Authentication Architecture

Sprint B1. The Electron **main process** is the OAuth client. The Angular renderer never sees a token, never talks to Keycloak and never calls the API directly.

## 1. Login flow (Authorization Code + PKCE, RFC 8252)

```
Renderer ──auth:login──▶ Main process
                          1. random verifier / state / nonce (openid-client)
                          2. loopback server on 127.0.0.1:<ephemeral>/callback
                          3. shell.openExternal(authorization URL)   (only the issuer origin may be opened)
System browser ──────────▶ Keycloak login (password typed in the browser, never in the app)
Keycloak ──302──▶ http://127.0.0.1:<port>/callback?code&state
                          4. callback validated: GET, exact path, single state value equal (constant time),
                             exactly one code; anything else is ignored (400/404/405) without ending the login
                          5. code exchange with code_verifier; openid-client validates issuer, state,
                             ID token signature, nonce, audience
                          6. loopback server closed; browser shows "You can close this window"
Main process ──auth:session-changed { status, user, storage }──▶ Renderer
```

Timeout 5 minutes; one login at a time (`busy`); cancellable. Library: **openid-client 6.8.9** (pinned) — no hand-written JWT or crypto code. Plain HTTP is allowed only when the issuer is a loopback host (`allowInsecureRequests` is not used otherwise).

## 2. Tokens

| Token | Where | Lifetime |
| --- | --- | --- |
| Access token | main-process memory only | 5 min, refreshed 30 s before expiry |
| Refresh token | `safeStorage` (DPAPI / Keychain / libsecret) → `userData/auth/session.bin` (0600, atomic write) | rotated on every refresh |
| ID token | memory, used only as `id_token_hint` at logout | — |

If the OS cannot protect secrets (`safeStorage` unavailable, or Linux `basic_text` backend), the refresh token stays **in memory only** (`storage: 'session-only'`): the session ends when the app closes. Nothing is ever written to `localStorage`, cookies or files readable by the renderer.

- **Refresh**: single-flight; rotated refresh token persisted immediately; failure (expired, revoked, reused) ends the session with `reason: 'expired'`.
- **Restore**: at start-up the stored refresh token is exchanged; an invalid one is deleted.
- **Logout**: refresh token revoked at the provider (RFC 7009), local state and file wiped, then the provider's end-session page opened in the browser (`id_token_hint`) so the SSO session ends too.

## 3. API calls (`api:request`)

The renderer sends `{ method, path, query?, body? }`. The main process (`auth/api-proxy.ts`, `auth/auth-policy.ts`):

- matches method + path against an **explicit allowlist** of Core API operations (UUID-shaped path parameters, no `..`, no other origin, no query string in the path). Since Sprint B2 the list includes the workforce operations (departments, positions, employees, teams — one entry per method and path; there is no employee delete);
- accepts only `page`, `size`, `includeSuspended` and the workforce filters (`q` ≤ 100 characters without control characters, `status`, `departmentId`, `positionId`, `managerId`, `parentId` as UUIDs, `rootOnly`, `includeArchived`, `includeFormer`) with strict patterns; JSON bodies ≤ 64 KB, never for GET/DELETE;
- adds `Authorization: Bearer <access token>`, `redirect: 'error'`, 15 s timeout, 2 MB response cap;
- on 401 refreshes once and retries; then reports `unauthenticated`;
- returns only `{ status, body }` — never response headers.

The renderer cannot set headers, choose a URL outside the API, or reach any other host. The renderer CSP keeps `connect-src 'self'`.

## 4. IPC surface

| Channel | Direction | Payload |
| --- | --- | --- |
| `auth:get-session` | invoke | → `{ status, user, storage, reason }` |
| `auth:login`, `auth:cancel-login`, `auth:logout` | invoke | → result without tokens |
| `api:request` | invoke | allowlisted request → `{ status, body, error? }` |
| `auth:session-changed` | event | session state (the IPC event object is not forwarded) |

Phase A protections remain: sandbox, context isolation, no Node integration, trusted-sender check (main frame + app origin) on every handler, argument count check, frozen bridge, navigation and window restrictions, permission handlers denying everything, debugging switches refused in packaged builds.

## 5. Configuration

| Variable | Default | Rule |
| --- | --- | --- |
| `AIW_OIDC_ISSUER` | `http://localhost:8082/realms/ai-workforce` | HTTPS, or HTTP on loopback only |
| `AIW_OIDC_CLIENT_ID` | `ai-workforce-desktop` | public client, no secret |
| `AIW_API_BASE_URL` | `http://localhost:8081` | origin only, HTTPS or loopback HTTP |

An invalid configuration disables authentication (the login reports `unavailable`) instead of falling back to something unsafe.

## 6. Angular side

- `SessionService` mirrors the session state; `authGuard` protects the shell (`/login?returnUrl=` with an in-app-only return URL); `signedOutGuard` on `/login`.
- `BackendClient` → `IdentityApi` (typed DTOs) over `window.desktop.api`; ids are validated before building paths.
- `AccessStore` exposes `myPermissions` of the current organization to show or hide actions; the API remains the authority.
- Screens: login, organization creation (onboarding when the user has none), organization selector (sidebar), members (roles, suspend/reactivate/remove/leave), roles & permissions (custom roles limited to the caller's permissions), invitations (send/list/revoke), invitation acceptance, sign-out.
- Data sources: `api` (default, Electron) — workspace from the Core API, and **agents, projects, communication, supervision, departments stay on demo data** (fixed demo organization, labelled "Demo data" in the top bar); `mock` (`ng serve --configuration mock`, unit tests) — everything in memory, no sign-in.

## 7. Tests

`electron/test/auth-policy.test.ts`, `auth-session.test.ts` (loopback flow with forged state, denial, timeout, cancel, single-flight refresh with rotation, refresh failure, logout revocation, restore, encrypted storage, memory fallback, API proxy bearer/401 retry/allowlist), `ipc-surface.test.ts` (no IPC primitive exposed, no token in contract types, contract copies identical, handler per channel), `keycloak.integration.test.ts` (opt-in, real Keycloak: PKCE login, audience, rotation, reuse refused, logout revocation). Angular: `core/auth/auth.spec.ts`.
