# ADR-0008 — Keycloak as OpenID Connect provider, API as OAuth2 resource server

- **Status:** Accepted
- **Date:** 2026-11-12 (Sprint B1)

## Context
The platform needs user accounts, registration, e-mail verification, password policy, brute-force protection and standard tokens for a desktop client and a REST API. Implementing these in the Core API would duplicate a security-critical product.

## Decision
- Keycloak **26.8.2** (pinned image, never `latest`) runs in Docker Compose, with its own database and DB role on the shared PostgreSQL 18 server. Realm `ai-workforce` is versioned as an import file (no users, no secrets).
- The desktop is a **public** client (`ai-workforce-desktop`) using Authorization Code + PKCE `S256` with a loopback redirect; implicit and password grants are disabled.
- Access tokens carry the audience `ai-workforce-api` (audience mapper). The Core API is a stateless resource server validating signature (JWKS, RS256), issuer, audience, `exp`/`nbf` and `typ = Bearer` (ID tokens rejected).
- Users are identified by `(issuer, subject)`; token claims grant no authority — permissions are evaluated in the database per organization (ADR-0009).

## Consequences
- The API never handles passwords. Swapping or adding a provider only touches configuration and the issuer column.
- The local stack needs Keycloak running to sign in; the API starts without it (JWKS fetched lazily).
- Keycloak runs `start-dev` locally; a production deployment needs `start` with TLS and a hostname (see `infra/keycloak/README.md`).

## Alternatives considered
- **Spring Authorization Server in the Core API:** more code to own, no account UI, couples identity to the API release cycle.
- **SaaS IdP (Auth0, Entra ID):** viable later through configuration; rejected for local-first development.
