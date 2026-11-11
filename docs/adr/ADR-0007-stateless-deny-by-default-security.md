# ADR-0007 — Stateless, deny-by-default API security before authentication

- **Status:** Accepted
- **Date:** 2026-11-12 (Sprint B0)

## Context
Authentication is scheduled for Sprint B1, but the security model must be correct from the first endpoint. A temporary open configuration ("permit all until login exists") tends to survive into production.

## Decision
- A single `SecurityFilterChain`:
  - stateless sessions, no request cache;
  - HTTP Basic, form login and logout disabled;
  - CSRF disabled because authentication will be a bearer token in the `Authorization` header (never a cookie);
  - explicit public endpoints (`GET /api/v1/health`, health/info actuator, and API docs only when enabled);
  - **`anyRequest().authenticated()`**.
- No authentication mechanism and no user store exist. Spring Boot's generated in-memory user is excluded. Every protected endpoint therefore answers 401.
- Method security (`@EnableMethodSecurity`) is enabled now, so B1 can add `@PreAuthorize` without touching the configuration.
- CORS uses exact configured origins, without credentials.
- B1 target: OAuth2 resource server validating JWTs from an OpenID Connect provider (see `docs/architecture/SECURITY_ARCHITECTURE.md`). It will be recorded in its own ADR.

## Consequences
- No fake login, hard-coded user or placeholder token exists anywhere.
- Business endpoints added before B1 are unreachable. This is intentional: it keeps business work behind authentication.
- Introducing cookie-based authentication later would require re-enabling CSRF protection. This is documented as a rule.

## Alternatives considered
- **Permit all business endpoints until B1:** rejected, unsafe default.
- **Temporary API key or basic user:** rejected, it would be a fake authentication path to remove later.
