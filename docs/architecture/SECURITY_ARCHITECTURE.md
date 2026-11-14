# Security Architecture

**Status:** Sprint B0 — 2026-11-12. Everything marked *(B1)* is designed but **not implemented**.

> **Sprint B1 update.** Authentication, authorization, multi-tenancy and desktop authentication are implemented. The authoritative descriptions are now [IDENTITY_ARCHITECTURE.md](IDENTITY_ARCHITECTURE.md), [MULTI_TENANCY_ARCHITECTURE.md](MULTI_TENANCY_ARCHITECTURE.md) and [DESKTOP_AUTHENTICATION_ARCHITECTURE.md](DESKTOP_AUTHENTICATION_ARCHITECTURE.md); sections marked *(B1)* below record the plan made in B0.

## 1. Current state (Sprint B0, implemented and tested)

| Control | Implementation | Test |
| --- | --- | --- |
| Deny by default | `SecurityConfig`: `anyRequest().authenticated()`. Public endpoints: `GET /api/v1/health`, `GET /actuator/health/**`, `GET /actuator/info`, and the API docs in `dev` only. | `SecurityConfigTest` (8 paths → 401) |
| No credentials at all | No authentication mechanism yet. HTTP Basic, form login and logout are disabled. Spring Boot's generated in-memory user is excluded (`UserDetailsServiceAutoConfiguration`). | `doesNotRegisterAnyUserAccount` |
| Stateless | `SessionCreationPolicy.STATELESS`, no request cache, no cookies | `doesNotCreateSessionsOrCookies` |
| CSRF | Disabled, deliberately. The API will authenticate with a bearer token in the `Authorization` header, which browsers never attach automatically, so cross-site requests cannot be authenticated. No cookie-based authentication may be introduced without re-enabling CSRF protection. | — |
| CORS | Exact origins from `APP_CORS_ALLOWED_ORIGINS`. Wildcards, paths and credentials in origins are rejected at startup. `allowCredentials=false`. Applies to `/api/**`. | `AppPropertiesTest`, `SecurityConfigTest` (allowed vs `https://evil.example`), prod container (dev origin → 403) |
| Uniform 401/403 | The entry point and access-denied handler delegate to `GlobalExceptionHandler`. RFC 9457 body, `WWW-Authenticate: Bearer realm="ai-workforce"`. | `SecurityConfigTest`, `GlobalExceptionHandlerTest` |
| No information leakage | No stack traces, exception classes or messages of unexpected errors. Validation errors do not echo rejected values. `server.error.include-*` set to `never`. | `hidesInternalDetailsOfUnexpectedErrors` |
| Security headers | `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`, CSP `default-src 'none'; frame-ancestors 'none'` (outside Swagger UI) | `sendsSecurityHeaders` |
| Method security | `@EnableMethodSecurity` is active, ready for `@PreAuthorize` in B1 | `returnsForbiddenWhenMethodSecurityDeniesAccess` |
| Actuator | Only `health` and `info` exposed. Details and components hidden outside `dev`. `env`, `configprops`, `heapdump`, `metrics` are unreachable. | integration test |
| Secrets | Environment variables only. `.env` is Git-ignored. No default database password. | manual checks (see sprint report) |
| Logs | No `Authorization` header, query string or payload in the access log. The request id format is validated (no log injection). | `RequestIdFilterTest`, manual check |
| Runtime | Container runs as the unprivileged user `app` (uid 10001). Database and API ports are bound to `127.0.0.1` in compose. | `docker exec … id` |

## 2. Authentication *(B1)*

**Target:** an OAuth 2.1 / OpenID Connect identity provider (self-hosted Keycloak for local and hybrid deployments, or a managed IdP in the cloud). The Core API is an **OAuth2 resource server** (`spring-boot-starter-oauth2-resource-server`) that validates JWT access tokens:
- signature via JWKS;
- `iss`, `aud=core-api`, `exp` and `nbf`, with small clock skew;
- an allow-listed algorithm (`RS256`/`ES256`).

The API never issues tokens itself, and never stores user passwords.

- **Desktop login:** Authorization Code + PKCE through the **system browser** (RFC 8252, native apps), with a loopback or custom-scheme redirect handled by the Electron main process. No embedded login window.
- **Token storage (desktop):** access token in main-process memory, refresh token encrypted with Electron `safeStorage` (OS keychain). Never `localStorage`. The renderer obtains short-lived tokens through a new, validated IPC capability. The Angular HTTP interceptor attaches `Authorization: Bearer …`.
- **Lifetimes:** access token about 5–10 minutes. Refresh tokens are rotated, with reuse detection at the IdP.
- **Principal mapping:** `sub` maps to an internal `users` row created on first login (just-in-time). Roles inside an organization come from the Core API database, **not** from token claims, so they can change without re-issuing tokens.
- **Alternative considered:** API-issued JWT with local passwords. Rejected for the enterprise target: SSO, MFA and password policies are IdP features. To be recorded in a new ADR (ADR-0008) when B1 starts.

## 3. Authorization and tenant isolation *(B1)*

Principles, mandatory for every module:

1. The **authenticated principal** (`sub`) is the only source of identity.
2. **Organization membership is verified server-side** on every request that targets an organization.
3. A client-supplied organization id (path, header or body) is **never trusted by itself**. It selects an organization, and membership decides whether that selection is allowed.
4. Every organization-owned resource is loaded **scoped to the authorized organization** (`findByOrganizationIdAndId`). A resource of another organization answers **404**.
5. Repository methods without an organization parameter are forbidden for tenant data. This will be enforced by an ArchUnit rule plus code review.
6. Cross-organization references are prevented in the schema (composite foreign keys including `organization_id`).
7. Background jobs and orchestrator callbacks carry an **explicit organization id and acting principal**, and are re-authorized. There is no ambient tenant on worker threads.
8. Tests prove **horizontal privilege escalation prevention**: user A, member of org A only, gets 404 on every org-B resource for every verb.

**Authorization model:** roles per membership (`OWNER`, `ADMIN`, `MANAGER`, `MEMBER`, `VIEWER`) mapped to fine-grained permissions (`agent:create`, `approval:decide`…). The checks run in application services with `@PreAuthorize` and a permission evaluator that receives the tenant context. Sensitive operations requested by AI agents always require a human decision (supervised autonomy).

**Request flow (B1):**

```
Bearer JWT → JwtAuthenticationFilter (signature, iss, aud, exp)
           → principal (sub → user)
           → TenantContextResolver: {organizationId} from path + membership lookup → 404 if not a member
           → @PreAuthorize(permission) in the service
           → organization-scoped repository query
```

## 4. Secret management

| Environment | Mechanism |
| --- | --- |
| Local development | `infra/docker/.env` (Git-ignored), generated per developer |
| CI | CI secret store, injected as environment variables |
| Cloud production | Secret manager (cloud KMS-backed) or Kubernetes secrets, injected as environment variables or mounted files. Rotation without rebuild. |
| AI provider keys | Server-side only (orchestrator or secret manager), encrypted at rest, never returned by any API, never sent to the desktop |

The application never logs configuration values. A dedicated database role with least privilege (no superuser, no DDL) is planned for runtime, with a separate migration role (see risks in the sprint report).

## 5. API security checklist (for every new endpoint)

- Under `/api/v1/`, authenticated by default (no new `permitAll` without review)
- Request DTO with Bean Validation, explicit size limits on strings and collections
- Tenant-scoped loading and permission check in the service
- Response DTO only, no entity, no internal identifiers beyond what the client needs
- Pagination with a maximum page size
- Tests: happy path, validation, 401, 403, cross-organization 404

## 6. Electron-to-backend communication *(B1)*

- **Transport:** HTTPS in every non-local deployment. HTTP on `localhost` is acceptable in development only.
- **Origins:** the Electron renderer runs on the privileged standard scheme `app://workforce`. Its `fetch` requests are expected to carry `Origin: app://workforce`, which must then be listed in `APP_CORS_ALLOWED_ORIGINS` (the `app` scheme is already accepted by `AppProperties`). **To verify in B1** against the packaged app before configuring production. The Angular dev server origin is `http://localhost:4200` and is **not** the same origin.
- **Desktop CSP:** the Phase A policy `connect-src 'self'` must be extended with the exact API origin (and its WebSocket origin). No wildcard.
- **Alternative:** route API calls through the Electron main process over IPC. This removes CORS entirely and keeps tokens out of the renderer, at the cost of a larger IPC surface. To be decided in B1. The token-handling rules in §2 apply in both cases.
- **Certificates:** no `--ignore-certificate-errors` or disabled TLS validation, including in development builds.
