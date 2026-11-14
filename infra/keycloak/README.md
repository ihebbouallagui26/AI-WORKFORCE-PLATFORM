# Keycloak (identity provider)

Keycloak **26.8.2** (`quay.io/keycloak/keycloak:26.8.2`) provides sign-in, registration and tokens for the platform. Architecture: [`docs/architecture/IDENTITY_ARCHITECTURE.md`](../../docs/architecture/IDENTITY_ARCHITECTURE.md) · Decision: [ADR-0008](../../docs/adr/ADR-0008-keycloak-identity-provider.md).

## Start the local stack

```bash
# From the repository root. Creates missing keys in infra/docker/.env with random passwords;
# existing values are never changed.
bash infra/docker/scripts/ensure-env.sh

docker compose -f infra/docker/compose.yaml --env-file infra/docker/.env up -d --wait
```

| Service | URL | Notes |
| --- | --- | --- |
| Keycloak | http://localhost:8082 | admin console: `/admin` (user `KEYCLOAK_ADMIN_USERNAME`, password `KEYCLOAK_ADMIN_PASSWORD` from `infra/docker/.env`) |
| Realm discovery | http://localhost:8082/realms/ai-workforce/.well-known/openid-configuration | issuer `http://localhost:8082/realms/ai-workforce` |
| Mailpit | http://localhost:8025 | receives verification and invitation e-mails (SMTP `localhost:1025`) |

`db-init` creates the Keycloak database and role before Keycloak starts. Keycloak's data lives in the `keycloak` database of the shared PostgreSQL volume; the volume is never deleted by these scripts.

## Realm import

`realm/ai-workforce-realm.json` is imported at start-up (`--import-realm`) **only if the realm does not exist yet**. After changing the file on an existing installation, either apply the change in the admin console or delete the realm there and restart Keycloak. The file contains **no users and no secrets**.

| Setting | Value |
| --- | --- |
| Client `ai-workforce-desktop` | public, standard flow only, PKCE `S256`, redirect `http://127.0.0.1/callback` (any port), post-logout `http://127.0.0.1/logged-out`, no web origins, no implicit, no direct access grants, no service account |
| Client `ai-workforce-api` | bearer-only; audience added to the desktop client's **access** tokens |
| Tokens | access 5 min, SSO idle 30 min / max 10 h, refresh token rotation (no reuse) |
| Accounts | registration on, e-mail as username, e-mail verification, brute-force detection, password policy (12+ chars, not username/e-mail) |
| SMTP | `mailpit:1025` |

## Orchestrator service account (Phase C0)

The AI orchestrator authenticates to the Core API as the confidential client `ai-orchestrator` (service account, client credentials only: no browser flow, no password grant, no implicit; access token 5 min; its tokens carry the `ai-workforce-api` audience). It is **not** in the realm file (that file must contain no secret and is imported only once): install it, on a fresh or existing realm, with

```bash
bash infra/docker/scripts/ensure-env.sh                              # generates ORCHESTRATOR_CLIENT_SECRET
bash infra/keycloak/scripts/provision-orchestrator-client.sh         # idempotent; the secret is never printed
```

A second client, `ai-workforce-core-api` (secret `CORE_API_SERVICE_CLIENT_SECRET`, token audience `ai-orchestrator`), is the identity of the Core API when it asks the orchestrator to run an execution (Phase C1); the same script provisions both, one per direction. The Core API accepts the orchestrator client's tokens only on `/api/v1/internal/**` (`app.orchestrator.client-id` / `ORCHESTRATOR_CLIENT_ID`) and refuses them elsewhere. In production create the client through your normal change process and store the secret in a secret manager.

## Create a user

Either register from the sign-in page (then open the verification e-mail in Mailpit), or in the admin console: *Users → Add user*, set e-mail, tick *Email verified*, then *Credentials → Set password* (not temporary).

## Production notes

- Run `start` (not `start-dev`) behind TLS with a real `KC_HOSTNAME`, a strong bootstrap admin password rotated after first login, and a real SMTP server.
- Add the production loopback redirect only (`http://127.0.0.1/callback`); never add wildcard redirect URIs.
- Point the API at the public issuer (`OIDC_ISSUER`) and an internal JWKS URL (`OIDC_JWK_SET_URI`) if needed.
