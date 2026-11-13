# Identity Architecture

Sprint B1. How users are authenticated, identified and provisioned. Tenant isolation and RBAC are in [MULTI_TENANCY_ARCHITECTURE.md](MULTI_TENANCY_ARCHITECTURE.md); the desktop side is in [DESKTOP_AUTHENTICATION_ARCHITECTURE.md](DESKTOP_AUTHENTICATION_ARCHITECTURE.md).

## 1. Components

```
 Desktop (Electron main)  ──Authorization Code + PKCE──▶  Keycloak 26.8.2 (realm ai-workforce)
          │                                                      │ JWKS (RS256)
          │ Bearer access token (aud = ai-workforce-api)         ▼
          └───────────────────────────────────────────▶  Core API (OAuth2 resource server)
                                                                 │
                                                                 ▼
                                                   PostgreSQL: user_accounts, external_identities
```

| Element | Value |
| --- | --- |
| Identity provider | Keycloak `quay.io/keycloak/keycloak:26.8.2`, own database `keycloak` in the shared PostgreSQL server, own DB role |
| Realm | `ai-workforce` (versioned import: `infra/keycloak/realm/ai-workforce-realm.json`) |
| Desktop client | `ai-workforce-desktop`: public, standard flow only, PKCE `S256` required, redirect `http://127.0.0.1/callback` (any loopback port, RFC 8252), no implicit, no direct grants, no service account, `fullScopeAllowed=false` |
| API audience | `ai-workforce-api` (bearer-only client; added to access tokens by an audience mapper, never to ID tokens) |
| Tokens | Access token 5 min; refresh tokens rotated (`revokeRefreshToken=true`, `refreshTokenMaxReuse=0`); SSO idle 30 min, max 10 h |
| Accounts | Self-registration with e-mail as username, e-mail verification required, brute-force detection, password policy `length(12) and notUsername and notEmail` |

## 2. Token validation (Core API)

`auth/AccessTokenDecoderConfig` builds a `NimbusJwtDecoder` (remote JWKS, keys cached, RS256 only). `auth/AccessTokenValidators` adds:

| Check | Rule |
| --- | --- |
| Signature | JWKS of the configured realm (`OIDC_JWK_SET_URI`) |
| `iss` | equals `OIDC_ISSUER` exactly |
| `aud` | contains `OIDC_AUDIENCE` (`ai-workforce-api`) |
| `exp`, `nbf` | `JwtTimestampValidator`, 30 s clock skew |
| `typ` | `Bearer` — **ID tokens (`typ: ID`) are rejected**, as are tokens without `typ` |
| `sub` | present and non-blank |

A rejected token gets `401` with `WWW-Authenticate: Bearer realm="ai-workforce", error="invalid_token"` and a RFC 9457 body. The token grants **no authority**: `JwtAuthenticationConverter` maps no claim to authorities; permissions come from the database (see multi-tenancy).

`OIDC_ISSUER` and `OIDC_JWK_SET_URI` are separate on purpose: in Docker the API fetches keys from `http://keycloak:8080/...` while tokens carry the public issuer `http://localhost:8082/realms/ai-workforce`.

## 3. Identity model

- A user is identified by **`(issuer, subject)`** (`external_identities`, unique). The e-mail is profile data, never a key: two identities with the same e-mail are two users (tested).
- `identity/UserProvisioningService.resolve(jwt)` runs once per request (via `AuthenticatedUserArgumentResolver`):
  1. look up `(iss, sub)`; 2. create `user_accounts` + `external_identities` just in time on first sight (retry once on a concurrent insert); 3. sync e-mail / `email_verified` / display name from claims; 4. refuse `DISABLED` accounts (403).
- Controllers receive an `AuthenticatedUser(userId, email, emailVerified, displayName)` parameter; services never read the security context themselves.
- `email_verified` matters only for invitation acceptance, which additionally requires the invitation token.

## 4. Endpoints

| Endpoint | Purpose |
| --- | --- |
| `GET /api/v1/me` | Profile of the caller (provisioned if needed) |
| `PATCH /api/v1/me` | Change display name |

Everything else is organization-scoped and documented in the OpenAPI contract (`contracts/openapi/core-api.v1.json`).

## 5. Tests

- `AccessTokenIntegrationTest`: valid token, foreign key, wrong issuer, wrong audience, expired, not yet valid, ID token, missing `typ`, malformed, `alg=none`; JIT provisioning and `(iss, sub)` identity.
- `KeycloakIntegrationTest` (real Keycloak container with the versioned realm): discovery, PKCE required (none/plain refused), loopback redirect rules, password and implicit grants disabled, complete PKCE login, API accepts the access token and rejects the ID token, code unusable without the verifier, refresh rotation (old token refused), revocation.
