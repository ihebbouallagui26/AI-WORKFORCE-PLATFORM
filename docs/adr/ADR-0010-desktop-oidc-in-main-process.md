# ADR-0010 — Desktop OIDC in the Electron main process, API calls proxied over IPC

- **Status:** Accepted
- **Date:** 2026-11-12 (Sprint B1)

## Context
The renderer runs web content; any token it can read can be exfiltrated by an XSS or a compromised dependency. Native apps must use the system browser with PKCE and a loopback or claimed redirect (RFC 8252).

## Decision
- The main process runs the whole OIDC flow with **openid-client 6.8.9**: system browser, PKCE `S256`, random state/nonce, temporary `127.0.0.1` loopback server with strict callback validation.
- Access token in main-process memory; refresh token encrypted with Electron `safeStorage` (session-only when the OS cannot protect it); rotation; revocation on logout.
- The renderer only receives the session state and calls the API through `api:request`, validated against an allowlist; the main process attaches the bearer token. The renderer CSP keeps `connect-src 'self'`.

## Consequences
- No token ever reaches the renderer, `localStorage` or the DOM.
- Each new API operation used by the UI must be added to the allowlist (`electron/src/auth/auth-policy.ts`) — deliberate friction.
- The CommonJS main process loads the ESM library through `require(esm)` (Electron 45 / Node 24, TypeScript `module: node20`).

## Alternatives considered
- **Tokens in the renderer with an HTTP interceptor:** simplest, rejected for exposure.
- **Custom URL scheme redirect:** needs OS registration and can be claimed by other apps; loopback is simpler and recommended.
- **Embedded login window:** violates RFC 8252 (credentials typed into the app).
