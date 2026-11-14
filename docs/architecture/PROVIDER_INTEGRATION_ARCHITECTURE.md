# Provider integration architecture (Phase C0)

Complements [PROVIDER_ARCHITECTURE.md](PROVIDER_ARCHITECTURE.md) (B3: connections, SecretStore). C0 adds the **runtime side**: asynchronous adapters in the Python orchestrator. No second key store exists.

## 1. Credential flow (implemented in C1 for Anthropic)

> **C1:** the mechanism below is now implemented. The Core API issues the credential through `POST /api/v1/internal/organizations/{org}/executions/{id}/credential` (orchestrator service account only, RUNNING executions only, **once per execution**, agent still ACTIVE, PRIMARY binding unchanged, connection VALID, provider enabled; decrypted by `ProviderCredentialBroker` in the provider package; `Cache-Control: no-store`; redacted `toString`; never stored, audited or returned elsewhere). The orchestrator obtains it with an execution-scoped `CredentialResolver` **inside the model-invocation node only**: it is never put in LangGraph state, checkpoints, logs, errors or the execution record. Decision record: [ADR-0017](../adr/ADR-0017-supervised-execution-and-credential-issuance.md).


```
orchestrator ──(service token)──► Core API ──► SecretStore.reveal (provider package only) ──► plaintext, in memory
      ▲                                                                                           │
      └──────────────── short-lived, per-call credential (never persisted, logged, checkpointed) ◄┘
```

* The B3 SecretStore stays the **only** place where credentials are stored. The orchestrator defines a `CredentialResolver` protocol; in C0 the only implementation fails closed (`UnavailableError`). C1 will implement it by calling a dedicated, audited Core API operation that is scoped to (organization, connection), requires the service account and returns the secret for immediate use only.
* Credentials never appear in: Angular/Electron, logs (redaction + `SecretStr`), LangGraph state (`assert_state_safe`), MCP tool descriptions (metadata screening), exception messages, execution-context payloads.
* An adapter holds a key only while one request runs: it is not an attribute of the adapter or its HTTP client (tested).

## 2. Interface

`ProviderAdapter` (Anthropic, OpenAI, Google): `generate`, `stream` (async iterator of `StreamEvent`: `delta`, `usage`, `done`), `list_models`. Contracts: `GenerationRequest` (model id, messages, max output tokens, temperature), `GenerationResponse`, `Usage`, `ModelInfo`, `ProviderScope(organization_id, connection_id)`.

| Provider | Base URL (configurable, fixed) | Auth header | Generate | Catalog (non-billable) |
| --- | --- | --- | --- | --- |
| Anthropic | `https://api.anthropic.com` | `x-api-key` + `anthropic-version: 2023-06-01` | `POST /v1/messages` | `GET /v1/models` |
| OpenAI | `https://api.openai.com` | `Authorization: Bearer` | `POST /v1/chat/completions` | `GET /v1/models` |
| Gemini | `https://generativelanguage.googleapis.com` | `x-goog-api-key` | `POST /v1beta/models/{model}:generateContent` (`:streamGenerateContent?alt=sse`) | `GET /v1beta/models` |

Official REST APIs are used through HTTPX rather than vendor SDKs: three small, fully mockable adapters with identical retry/timeout/redaction behaviour and no extra dependency tree. (Endpoint shapes follow the providers' public documentation; the catalog calls and authentication schemes were checked against the live endpoints with deliberately invalid keys in B3. The generation request shapes are verified by mocked tests only.)

## 3. Safety properties

* **No hardcoded model names** (a test scans the adapter sources). The model id is data: validated against `^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$` so it cannot alter the request path (no `/`, `..`, `?`, spaces).
* **Fixed endpoints**: base URLs come from settings, must be HTTPS (loopback HTTP only outside production), carry no userinfo; redirects are not followed.
* **Normalized errors**: authentication → `ProviderAuthenticationError` (Gemini's HTTP 400 `API_KEY_INVALID` is recognized without copying the body), 429 → `ProviderRateLimitError(retry_after)`, timeouts, 5xx/network → `ProviderUnavailableError`, 4xx → `ProviderBadRequestError`, malformed payload → `ProviderResponseError`. The provider body is **never** copied into a message or a log (it may echo the key).
* **Retries**: rate limits, timeouts and transient failures only, exponential backoff with jitter, `Retry-After` honoured up to a cap, bounded attempts; authentication and bad-request errors are never retried. Streaming retries only before the first event.
* Response size is capped (8 MiB) for non-streaming calls.

## 4. Anthropic in C1

The Anthropic adapter (Messages API, `x-api-key` + `anthropic-version: 2023-06-01`) runs supervised executions: model id from the agent's PRIMARY binding (never hardcoded), `max_tokens` from `AIO_EXECUTION_MAX_OUTPUT_TOKENS` (default 1024), bounded timeout and retries (429/5xx/timeouts only, `Retry-After` honoured), usage (`input_tokens`/`output_tokens`) recorded by the Core API. OpenAI and Gemini adapters are intact but **not enabled** (`AIO_EXECUTION_ENABLED_PROVIDERS=ANTHROPIC`; the Core API refuses to launch an agent whose primary provider is not Anthropic). No automatic fallback between providers. Error normalization to safe codes is described in [AI_ORCHESTRATION_ARCHITECTURE.md](AI_ORCHESTRATION_ARCHITECTURE.md) §9.2.

## 5. Testing and what is (not) verified

| Layer | How | Proves |
| --- | --- | --- |
| Unit (CI, default) | `respx` mocks of all three providers: success, headers, usage, streaming, 401/400-invalid-key, 429 + `Retry-After`, timeout, 5xx, bad payloads, key never in errors/attributes | Adapter logic and secret hygiene |
| **Live smoke (opt-in)** | `tests/test_live_providers.py`, runs only with `AIO_LIVE_PROVIDER_TESTS=1` **and** `AIO_LIVE_ANTHROPIC_KEY` / `AIO_LIVE_OPENAI_KEY` / `AIO_LIVE_GEMINI_KEY` | Real connectivity of the **catalog** endpoint only — never a generation, so nothing is billed |

**Real provider connectivity has not been verified in C0 or C1**: no real key was available, so the live tests were skipped (3 skipped). Mocked tests prove nothing about the providers' actual behaviour.
