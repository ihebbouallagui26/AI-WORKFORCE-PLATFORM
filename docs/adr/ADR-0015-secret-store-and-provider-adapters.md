# ADR-0015 — SecretStore abstraction and fixed-endpoint provider adapters

- **Status:** Accepted
- **Date:** 2026-11-14 (Sprint B3)

## Context
Organizations connect their own model-provider accounts. Credentials are high-value secrets that must not reach the desktop UI, must not sit in clear in PostgreSQL, and must be validated without cost or SSRF exposure.

## Decision
* Credentials live behind a **`SecretStore`** port. The development adapter encrypts with **AES-256-GCM**, a random 96-bit nonce per entry and **AAD = organization id + entry id**. The master key is read only from `SECRETS_MASTER_KEY` (base64, 32 bytes); there is no default, startup fails without it, it is never logged. PostgreSQL stores ciphertext, nonce and a key version.
* Credentials are **write-only** through the API: never returned, not even masked, never in audit events or logs. Request records override `toString`. Only the `provider` package may call `reveal` (ArchUnit).
* **Saving does not validate.** A connection starts `PENDING`; `POST …/test` validates through the provider's official read-only models endpoint (no billable completion).
* Adapters call **fixed base URLs from configuration** (HTTPS, loopback HTTP only for tests) with fixed paths; users cannot supply endpoints. Redirects are not followed, responses are capped, timeouts apply, provider error bodies are discarded (they can echo key fragments) and replaced by fixed messages. Provider calls never run inside a database transaction.
* **No hardcoded model names**: catalogs are discovered per connection and bindings take configurable ids.
* CI uses local fake provider servers; no real key is ever needed.

## Consequences
* A stolen database dump alone is useless without the master key; a copied ciphertext does not decrypt in another row or organization.
* Key rotation is not implemented (`key_version` is stored for it). Production should add a KMS/Vault adapter.
* The key is typed once in the renderer before being proxied by the main process; it transits renderer memory and IPC but is never stored or displayed. A native input handled entirely by the main process would remove this transit and is a candidate hardening step.
* Real provider connectivity depends on the user's accounts and is not exercised by automated tests.

## Alternatives considered
* pgcrypto column encryption: rejected, the key would be sent to the database and appear in query logs.
* Letting the desktop call providers directly: rejected, credentials would leave the server side.
* Free-form endpoint URLs per connection: rejected, SSRF.
