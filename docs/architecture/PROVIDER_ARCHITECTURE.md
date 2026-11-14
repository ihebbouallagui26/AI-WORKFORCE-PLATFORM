# Provider architecture and secret handling (Sprint B3)

## 1. Connections

A `ProviderConnection` belongs to one organization: provider type (`ANTHROPIC`, `OPENAI`, `GEMINI`), display name, optional account label, status, an opaque `credential_ref`, `last_tested_at`, timestamps and a version.

| Status | Meaning |
| --- | --- |
| `PENDING` | credential stored, **not validated**. Saving never implies validity. |
| `VALID` | the last validation succeeded |
| `INVALID` | the provider rejected the credential |
| `REVOKED` | terminal: credential destroyed, `credential_ref` is `NULL` (database check keeps both in sync) |

Replacing a credential resets the status to `PENDING`. A validation that cannot reach the provider (`UNAVAILABLE`: network, timeout, 5xx, rate limit) leaves the status unchanged. A connection cannot be revoked while an `ACTIVE` agent uses it as primary.

## 2. SecretStore

```java
public interface SecretStore {
    UUID store(UUID organizationId, String secret);
    String reveal(UUID organizationId, UUID entryId);
    void delete(UUID organizationId, UUID entryId);
}
```

Development/self-hosted adapter `AesGcmSecretStore`:

* AES-256-GCM, fresh 96-bit random nonce per entry, 128-bit tag;
* **AAD = organization id + entry id**, so a ciphertext copied to another row or organization fails authentication;
* the master key comes **only** from `SECRETS_MASTER_KEY` (`app.secrets.master-key`, base64 of 32 bytes). There is no default and no hardcoded key; startup fails when it is missing or malformed. `infra/docker/scripts/ensure-env.sh` generates one for local development with `openssl rand -base64 32` without touching existing values;
* table `secret_entries(id, organization_id, ciphertext, nonce, key_version, created_at)` stores ciphertext only; `key_version = 1` is recorded for a future rotation (not implemented);
* the master key and any plaintext are excluded from `toString`, logs and audit; credential-bearing request records redact themselves;
* `reveal` is callable only from the `provider` package (ArchUnit). Nothing reveals a secret to a controller, a response or the frontend, not even masked.

Production should plug a KMS/Vault adapter behind the same interface.

> **C1:** a second, server-side path now exists besides these write-only operations: the orchestrator's service account can obtain a credential for one RUNNING execution, once, through the Core API (see [PROVIDER_INTEGRATION_ARCHITECTURE.md](PROVIDER_INTEGRATION_ARCHITECTURE.md) §1). The desktop still never receives a credential.

## 3. Credential flow

1. The user types the key once in a write-only password field (`POST …/providers/connections` or `PUT …/{id}/credential`). The renderer sends it to the Electron main process (`api:request`), which attaches the bearer token and forwards it. The renderer clears the field right away and never stores it in a store or `localStorage`; the API never returns it. This brief renderer-memory/IPC transit is a documented limitation (see Desktop README).
2. The Core API validates the shape (`^[!-~]+$`, 8–512 characters), encrypts it, stores the entry and the reference.
3. Validation (`POST …/{id}/test`) and model listing (`GET …/{id}/models`) are the only calls that reveal the credential, in memory, to a provider adapter.

## 4. Provider adapters

`ProviderAdapter` hides provider specifics: `validate(credential)` and `listModels(credential)`.

| Provider | Base URL (configurable) | Authentication | Probe |
| --- | --- | --- | --- |
| Anthropic | `https://api.anthropic.com` | `x-api-key`, `anthropic-version: 2023-06-01` | `GET /v1/models?limit=` |
| OpenAI | `https://api.openai.com` | `Authorization: Bearer` | `GET /v1/models` |
| Gemini | `https://generativelanguage.googleapis.com` | `x-goog-api-key` | `GET /v1beta/models?pageSize=` (an invalid key answers HTTP 400 `API_KEY_INVALID`) |

Properties (verified against the official endpoints with deliberately invalid keys; no real key was used):

* **read-only listing calls, never a billable completion**;
* **no user-supplied URL**: base URLs come from configuration (`app.providers.*.base-url`), must be HTTPS (or loopback HTTP for tests), and the path is fixed — the SSRF surface is nil;
* redirects are not followed, responses are capped at 1 MB, connect/read timeouts apply (`app.providers.timeout`, default 10 s);
* provider error bodies are discarded: they may echo key fragments, so the user only sees fixed messages ("The provider rejected the credential", "The provider is unreachable", …);
* calls run **outside** database transactions (`TransactionTemplate` only around reads/writes) so a slow provider never holds a connection.

## 5. Models

No model name is hardcoded as "latest". The catalog is discovered per connection; bindings accept any id matching the id pattern, so new models work without a release. Provider-specific options (temperature, max output tokens, top-p) are validated and stored in the binding.

## 6. Local and CI testing

`FakeProviders` starts three local HTTP servers imitating the three authentication schemes. Credential prefixes drive the outcome (`valid-`, `bad-`, `rate-`, `down-`). The fake records requests (headers asserted) and echoes the credential in its error bodies to prove it never leaks. **No test needs or uses a real API key.** Real provider connectivity is therefore not exercised by the automated suite.
