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
