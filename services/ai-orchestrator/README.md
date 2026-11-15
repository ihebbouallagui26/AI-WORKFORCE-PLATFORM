# AI orchestrator (Phases C0–C1)

Python service that runs AI agents. **C1 adds the first supervised execution: a person launches a run from the desktop, the orchestrator generates text with Anthropic Claude and reports the result to the Core API. It runs no tool, shell, file or MCP action, has no fallback between providers and starts nothing on its own.** Flow, security and limits: [AI_ORCHESTRATION_ARCHITECTURE.md §9](../../docs/architecture/AI_ORCHESTRATION_ARCHITECTURE.md), [ADR-0017](../../docs/adr/ADR-0017-supervised-execution-and-credential-issuance.md). Architecture: [AI_ORCHESTRATION_ARCHITECTURE.md](../../docs/architecture/AI_ORCHESTRATION_ARCHITECTURE.md), [PROVIDER_INTEGRATION_ARCHITECTURE.md](../../docs/architecture/PROVIDER_INTEGRATION_ARCHITECTURE.md), [MCP_INTEGRATION_ARCHITECTURE.md](../../docs/architecture/MCP_INTEGRATION_ARCHITECTURE.md), [ADR-0016](../../docs/adr/ADR-0016-orchestrator-service-boundary-and-identity.md).

* FastAPI (`/health/live`, `/health/ready`, `/api/v1/runtime/info`), correlation ids, RFC 9457 errors, request limits and timeouts, structured JSON logs with secret redaction.
* Typed async **Core API client** authenticated as a Keycloak **service account** (client credentials).
* **Provider adapters** (Anthropic, OpenAI, Gemini): generate, stream, list models; normalized errors, retries, no secret leakage. Mocked tests only; optional live catalog smoke tests.
* **LangGraph** runtime with PostgreSQL checkpoints (dedicated database and role), tenant-aware thread ids, a deterministic diagnostic graph, human-in-the-loop pause/resume.
* **MCP** client foundation with a closed allowlist (one harmless local diagnostic server) and permission-checked tool registry.

## Requirements

Python 3.12 or 3.13 and [uv](https://docs.astral.sh/uv/) (`pip install uv`). Docker for the PostgreSQL tests. Dependencies are pinned in `pyproject.toml` and locked in `uv.lock`.

## Run

```bash
# 1. Stack (from the repository root): environment, PostgreSQL roles/databases, Keycloak, service account
bash infra/docker/scripts/ensure-env.sh
docker compose -f infra/docker/compose.yaml --env-file infra/docker/.env up -d --wait
bash infra/keycloak/scripts/provision-orchestrator-client.sh     # idempotent, prints no secret

# 2. The service as a container (internal, published on 127.0.0.1:8091 only)
docker compose -f infra/docker/compose.yaml --env-file infra/docker/.env --profile orchestrator up -d --build
curl http://127.0.0.1:8091/health/ready
```

Run it from sources (Linux/macOS/WSL): `cd services/ai-orchestrator && uv sync && AIO_DATABASE_URL=… AIO_CLIENT_SECRET=… uv run python -m app`. `python -m app` also works on native Windows (it selects the selector event loop that psycopg needs).

Port **8091** is the default (8090 is taken by unrelated software on some machines).

## Configuration (`AIO_*`)

| Variable | Default | Purpose |
| --- | --- | --- |
| `AIO_ENVIRONMENT` | `dev` | `dev`, `test`, `prod`. `prod` requires HTTPS endpoints, a client secret and a database URL. |
| `AIO_HOST` / `AIO_PORT` | `127.0.0.1` / `8091` | Bind address (the container sets `0.0.0.0`, published on loopback only). |
| `AIO_CORE_API_BASE_URL` | `http://localhost:8081` | Core API. |
| `AIO_TOKEN_URL`, `AIO_CLIENT_ID`, `AIO_CLIENT_SECRET` | Keycloak local, `ai-orchestrator`, none | Service account (client credentials). The secret is never defaulted, logged or committed. |
| `AIO_OIDC_ISSUER`, `AIO_OIDC_JWKS_URL`, `AIO_OIDC_AUDIENCE` | Keycloak local, `ai-orchestrator` | Verification of callers' tokens. |
| `AIO_EXECUTION_ENABLED_PROVIDERS`, `AIO_EXECUTION_MAX_OUTPUT_TOKENS`, `AIO_MAX_CONCURRENT_EXECUTIONS` | `ANTHROPIC`, `1024`, `4` | Providers allowed to run an execution (OpenAI/Gemini adapters exist but are not enabled), output limit, concurrent runs (a full runtime answers 503). |
| `AIO_ALLOWED_CALLER_CLIENT_IDS` | `ai-workforce-core-api` | Comma-separated service clients allowed to call this service. |
| `AIO_STATE_ENCRYPTION_KEY` | none | Base64 of **32 random bytes** (AES-256-GCM). Seals the conversation of a run paused for a human approval before it is checkpointed. **Required in `prod`** (startup fails if missing or malformed); outside prod, without it tools are simply not offered. Never logged, never in the image, never in health/runtime endpoints. Must be identical on every replica and stable across restarts; no rotation yet. See `docs/architecture/STATE_ENCRYPTION.md`. |
| `AIO_DATABASE_URL` | none | **Dedicated** runtime database (LangGraph checkpoints). Restricted role, never the Core API database. |
| `AIO_REQUEST_TIMEOUT_SECONDS`, `AIO_MAX_REQUEST_BYTES`, `AIO_RUN_TIMEOUT_SECONDS`, `AIO_SHUTDOWN_GRACE_SECONDS` | 30, 1 MiB, 60, 10 | Limits. |
| `AIO_ANTHROPIC_BASE_URL`, `AIO_OPENAI_BASE_URL`, `AIO_GOOGLE_BASE_URL` | official endpoints | Fixed, HTTPS only (loopback HTTP outside prod, for tests). |

## Tests

```bash
uv run pytest                      # unit + PostgreSQL (Testcontainers) + MCP stdio tests
uv run pytest -m "not docker"      # without Docker
uv run ruff check .
```

Live provider smoke tests (**off by default**, never in CI; the catalog tests only list models and are not billable; `test_live_anthropic_generation` is **billable** and needs a second opt-in, `AIO_LIVE_ANTHROPIC_GENERATION=1`, plus `AIO_LIVE_ANTHROPIC_MODEL`): `AIO_LIVE_PROVIDER_TESTS=1 AIO_LIVE_ANTHROPIC_KEY=… AIO_LIVE_OPENAI_KEY=… AIO_LIVE_GEMINI_KEY=… uv run pytest tests/test_live_providers.py`. Everything else is mocked: **mocked tests say nothing about real provider connectivity.**

## Security notes

* No credential is stored here: provider keys stay in the Core API SecretStore. State, logs and tool metadata are screened for secrets.
* Only service tokens of allow-listed clients are accepted; desktop and user tokens are rejected.
* The container runs as uid 10001 with a read-only filesystem and no capabilities.

## Workflow coordinator turns (Sprint C3.2)

When the Core API starts an execution that is a workflow **coordinator turn**, its `start` answer carries `workflowRole: COORDINATOR` and
the two platform actions `workflow.delegate` / `workflow.finish` with their JSON schemas. The orchestrator then offers the model **only**
those two actions (shown as `workflow_delegate` / `workflow_finish`: provider tool names cannot contain a dot) and **no MCP tool**. It does not
interpret them: it checks the name and the argument shape, forwards each to the Core API (`POST …/workflow-actions`, idempotent per provider
tool call id, never retried here), and relays the Core API decision to the model. Only the first action of a turn is forwarded; the turn
ends as soon as one is accepted; a rejection is relayed so the model may choose again, bounded by the round limit and by the Core API.
There is no long-lived supervisor and no workflow state in checkpoints. Tests (`tests/test_workflow_actions.py`) use a scripted provider
adapter and a stubbed Core API; real Anthropic tool-use for this flow is **NOT VERIFIED**.

## Workflow reliability (Sprint C3.3)

The orchestrator never decides a retry. It reports every failure with a precise safe code (`PROVIDER_UNAVAILABLE`, `PROVIDER_RATE_LIMIT`, `PROVIDER_TIMEOUT`,
`PROVIDER_AUTHENTICATION`, `TOOL_FAILED`, `DEADLINE_EXCEEDED`, `COORDINATION_UNAVAILABLE`, …); the Core API classifies it, decides on a retry and creates a NEW execution.
When an execution belongs to a workflow, the `start` answer carries `deadlineSeconds` (the earlier of the step and workflow deadlines): the run is bounded by it and fails with
`DEADLINE_EXCEEDED` instead of extending it; a tool call cut short has an UNKNOWN outcome, recorded by the Core API, and is never replayed here. Failure messages come from a
fixed table: no task, credential or provider text. Tests: `tests/test_workflow_reliability.py` (scripted adapters, stubbed Core API; real Anthropic behaviour NOT VERIFIED).

## Parallel multi-agent execution (Sprint C3.4)

Each execution already runs in its own asyncio task with its own `RunContext` and graph, so independent worker turns run concurrently with nothing shared (capacity: `AIO_MAX_CONCURRENT_EXECUTIONS`, default 4;
the Core API decides which and how many to start). New: the `start` answer carries `maxDelegationsPerTurn`; a coordinator turn may then call `workflow_delegate` several times (each forwarded with its own
provider tool-call id, bounded, never together with `workflow_finish`) and the turn ends once the Core API accepted something. A failed execution reports the provider usage it already consumed. Tests:
`tests/test_parallel_execution.py` (a barrier that only opens when all executions are in flight at once proves the overlap; real Anthropic behaviour NOT VERIFIED).

## Security hardening (Sprint C3.6)

* **Checkpoint sealing, format 2:** HKDF-derived key, AAD bound to organization, execution, thread and the paused tool invocation; `AIO_STATE_ACCEPT_LEGACY_FORMAT` (default `true`) keeps format-1 blobs readable until old runs finish. Never replace `AIO_STATE_ENCRYPTION_KEY` while sealed state exists.
* **Bounded MCP transport:** `AIO_MCP_MAX_MESSAGE_BYTES`, `AIO_MCP_MAX_CALL_BYTES`, `AIO_MCP_STDERR_CAPTURE_BYTES`, `AIO_MCP_STDERR_FLOOD_BYTES`, `AIO_MCP_STDOUT_FLOOD_BYTES`, `AIO_MCP_STDOUT_FLOOD_MESSAGES` (validated ranges; defaults in `app/core/config.py`). Unknown outcomes are reported to the Core API and never replayed. The transport depends on private helpers of `mcp==2.3.0` (exact pin; `tests/test_mcp_sdk_compat.py`).
* **Transport:** in production `AIO_CORE_API_BASE_URL` and the token/JWKS URLs must be credential-free `https://`; the clients verify certificate chain, expiry and host name, never follow redirects and have no plaintext fallback. To trust an internal CA set `SSL_CERT_FILE` (mounted file, not baked in the image). Tests with real TLS handshakes: `tests/test_internal_tls.py`. No mutual TLS.
* **Logs:** `redact` / `redact_text` mask secret shapes; tool arguments, results and keys are not logged.
* Linux run of the suite: mount the repository read-only in a `python:3.13-slim` container, `uv sync --frozen --no-install-project` into `/tmp/venv`, and run `pytest` (Testcontainers needs the Docker socket and `TESTCONTAINERS_HOST_OVERRIDE`).
