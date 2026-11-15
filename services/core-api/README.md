# AI Workforce Platform — Core API

Core backend of the AI Workforce Platform: a **Spring Boot modular monolith** on PostgreSQL.

- **Sprint B0** — foundation: configuration profiles, PostgreSQL + Flyway, deny-by-default security, RFC 9457 errors, health, OpenAPI, request correlation, tests.
- **Sprint B2** — workforce management: employees (professional profiles, optionally linked to a platform membership), hierarchical departments, positions (job titles, never permissions) and teams with member history, on top of the B1 tenant model.
- **Sprint B1** — authentication (OAuth2 resource server, Keycloak access tokens), user provisioning by `(issuer, subject)`, organizations, memberships, RBAC (built-in and custom roles, anti-escalation), strict multi-tenant isolation, invitations by e-mail, audit trail, separate database roles.

Workforce: [`WORKFORCE_MANAGEMENT_ARCHITECTURE.md`](../../docs/architecture/WORKFORCE_MANAGEMENT_ARCHITECTURE.md) · Data model: [`WORKFORCE_DATA_MODEL.md`](../../docs/database/WORKFORCE_DATA_MODEL.md) · Identity: [`IDENTITY_ARCHITECTURE.md`](../../docs/architecture/IDENTITY_ARCHITECTURE.md) · Multi-tenancy: [`MULTI_TENANCY_ARCHITECTURE.md`](../../docs/architecture/MULTI_TENANCY_ARCHITECTURE.md) · Schema: [`IDENTITY_AND_ORGANIZATION_SCHEMA.md`](../../docs/database/IDENTITY_AND_ORGANIZATION_SCHEMA.md) · Architecture: [`docs/architecture/CORE_BACKEND_ARCHITECTURE.md`](../../docs/architecture/CORE_BACKEND_ARCHITECTURE.md) · Security: [`docs/architecture/SECURITY_ARCHITECTURE.md`](../../docs/architecture/SECURITY_ARCHITECTURE.md) · Decisions: [`docs/adr`](../../docs/adr)

## Technology stack

| Component | Version | Notes |
| --- | --- | --- |
| Java | 21 (LTS) | Eclipse Temurin |
| Spring Boot | 4.1.2 | Manages every version below unless noted |
| Spring Framework / Security | 7.0.10 / 7.1.2 | |
| Hibernate ORM | 7.4.13 | `ddl-auto=validate` only |
| Flyway | 12.4.0 | `flyway-database-postgresql` module |
| PostgreSQL JDBC | 42.7.14 | |
| Jackson | 3.1.7 | Spring Boot 4 uses Jackson 3 (`tools.jackson`) |
| springdoc-openapi | 3.2.0 | Not in the Boot BOM; built against Boot 4.1.2 |
| JUnit / Testcontainers / ArchUnit | 6.0.3 / 2.0.5 / 1.5.1 | |
| Maven | 3.9.11 | Pinned by the Maven Wrapper |
| PostgreSQL | 18 + pgvector 0.8.7 | `pgvector/pgvector:0.8.7-pg18` |

Version choices are recorded in [ADR-0002](../../docs/adr/ADR-0002-spring-boot-4-java-21.md).

## Prerequisites

- JDK 21 (`java -version`)
- Docker Desktop or Docker Engine (database, integration tests, container image)
- Maven is **not** required: use `./mvnw` (Linux/macOS/Git Bash) or `mvnw.cmd` (Windows)

## Local setup

```bash
# 1. Environment (from the repository root). Adds missing keys with random passwords;
#    never changes existing values.
bash infra/docker/scripts/ensure-env.sh

# 2. Start PostgreSQL (+ role setup), Keycloak and Mailpit
docker compose -f infra/docker/compose.yaml --env-file infra/docker/.env up -d --wait

# 3. Run the API with the dev profile (from services/core-api)
cd services/core-api
./mvnw spring-boot:run
```

The `dev` profile reads `infra/docker/.env` (and an optional `services/core-api/.env`), so the same credentials serve Docker Compose and the API. Real environment variables always take precedence.

> **Port 8080 already in use?** Some machines run another service on 8080 (for example Oracle XML DB / TNS Listener). Set `SERVER_PORT=8081` in `infra/docker/.env`; the desktop app's `apiBaseUrl` must then use the same port.

### Environment variables

| Variable | Required | Default | Purpose |
| --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | no | none (production-safe base) | `dev`, `test` or `prod`. `spring-boot:run` uses `dev`. |
| `SERVER_PORT` | no | `8080` | HTTP port |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | no | `localhost` / `5432` / `ai_workforce` | PostgreSQL location |
| `DB_USERNAME` / `DB_PASSWORD` | compose only | none | Bootstrap superuser, used only by `db-init` to create roles and extensions — never by the API |
| `DB_MIGRATION_USERNAME` / `DB_MIGRATION_PASSWORD` | **yes** | none | Flyway role (owns the schema, DDL) |
| `DB_APP_USERNAME` / `DB_APP_PASSWORD` | **yes** | none | Runtime role (data access only, no DDL) |
| `OIDC_ISSUER` | **yes** | none | Exact issuer of accepted tokens, e.g. `http://localhost:8082/realms/ai-workforce` |
| `OIDC_JWK_SET_URI` | **yes** | none | JWKS URL used to verify signatures |
| `OIDC_AUDIENCE` | **yes** | none | Required audience (`ai-workforce-api`) |
| `MAIL_HOST` / `MAIL_PORT` | for invitations | none / `25` | SMTP server (Mailpit locally: `localhost` / `1025`). Without it, invitations answer 503 |
| `INVITATION_MAIL_FROM` | no | `no-reply@aiworkforce.local` | Sender of invitation e-mails |
| `SECRETS_MASTER_KEY` | **yes** | none (generated into `infra/docker/.env` by `ensure-env.sh`) | Base64 of 32 random bytes: AES-256-GCM master key of the provider `SecretStore`. Never commit or log it; losing it makes stored credentials unreadable. |
| `ORCHESTRATOR_URL`, `ORCHESTRATOR_TOKEN_URL`, `CORE_API_SERVICE_CLIENT_ID`, `CORE_API_SERVICE_CLIENT_SECRET` | no | blank (dispatching disabled) | Core API → orchestrator calls with the Core API's own Keycloak service account (`ai-workforce-core-api`). Blank = dispatching disabled (executions fail safely with `ORCHESTRATOR_UNAVAILABLE`); a partial configuration refuses to start. **Outside the `dev` and `test` profiles both URLs must be HTTPS** (startup fails otherwise). |
| `EXECUTIONS_TIMEOUT`, `EXECUTIONS_MAX_CONCURRENT` | no | `PT5M`, `3` | Open executions older than the timeout are failed (`TIMEOUT`); open executions allowed per organization. |
| `ORCHESTRATOR_CLIENT_ID` | no | `ai-orchestrator` | Keycloak client id (`azp`) of the AI orchestrator service account: accepted on `/api/v1/internal/**` only, refused on every user endpoint. |
| `APP_CORS_ALLOWED_ORIGINS` | **yes** outside `dev` | `http://localhost:4200` in `dev` only | Comma-separated exact origins. Wildcards are rejected at startup. |

Secrets are never committed: `.env` files are ignored by Git, and only `*.env.example` files with placeholders are versioned.

## Database and migrations

- The schema is owned by **Flyway**: `src/main/resources/db/migration/V<n>__<description>.sql`. Migrations run automatically at startup, before Hibernate validates the schema (`ddl-auto=validate`).
- `V1__enable_extensions.sql` enables `pgvector`; `V2__identity_and_organizations.sql` adds identity, organizations, RBAC, invitations and audit; `V3__workforce_management.sql` adds employee profiles, departments, positions, teams and ten permissions (granted to the built-in roles of existing organizations). `afterMigrate.sql` re-applies the runtime role's grants.
- `V6__tool_execution_and_approvals.sql` (Sprint C2) is additive: tool policy columns on `tool_definitions` (all existing tools stay `DENIED`), the reference tools of the local `diagnostic` MCP server, the `WAITING_APPROVAL` execution status, the `tool_invocations` and `tool_approvals` tables, and the permissions `approval.read` / `approval.decide` (granted to `OWNER` and `ADMIN`). V1–V5 are untouched.
- `V7__multi_agent_workflows.sql` (Sprint C3.1) is additive: `workflow_definitions`, `workflow_participants`, `workflow_runs` (with an immutable `snapshot` of the definition), `workflow_steps` (one step = one `agent_executions` row; `agent_executions` itself is unchanged), `workflow_messages` (append-only: a trigger refuses UPDATE and DELETE), and the permissions `workflow.read`, `workflow.manage`, `workflow.execute` (granted to `OWNER` and `ADMIN`). V1–V6 are untouched.
- Flyway runs as `DB_MIGRATION_USERNAME`; the application pool uses `DB_APP_USERNAME` (see [ADR-0011](../../docs/adr/ADR-0011-database-privilege-separation.md)).
- Applied migrations are immutable. A fix is a new migration, and `flyway clean` is disabled.
- Conventions: [`docs/database/DATABASE_CONVENTIONS.md`](../../docs/database/DATABASE_CONVENTIONS.md).

```bash
# Inspect the database
docker compose -f infra/docker/compose.yaml --env-file infra/docker/.env exec postgres \
  psql -U "$DB_USERNAME" -d ai_workforce -c "select version, description, success from flyway_schema_history"
```

## API

| Endpoint | Access | Description |
| --- | --- | --- |
| `GET /api/v1/health` | public | Application status (`UP` → 200, otherwise 503), version, UTC timestamp |
| `GET /actuator/health` (`/liveness`, `/readiness`) | public | Operational probes. Details are shown only in `dev`. |
| `GET /actuator/info` | public | Build information |
| `/v3/api-docs`, `/swagger-ui.html` | public in `dev` only | OpenAPI document and Swagger UI |
| `GET/PATCH /api/v1/me` | bearer token | Caller profile |
| `/api/v1/organizations[/{organizationId}]` | bearer token + membership | List/create/get/update organizations, audit events |
| `/api/v1/organizations/{id}/members`, `/roles`, `/invitations` | bearer token + membership + permission | Members, roles and invitations |
| `GET /api/v1/permissions`, `POST /api/v1/invitations/accept` | bearer token | Permission catalog, invitation acceptance |
| `/api/v1/organizations/{id}/employees` (+ `/{employeeId}`, `/department`, `/position`, `/manager`, `/membership`, `/status`, `/teams`) | bearer token + membership + `employee.*` | Employee profiles: search, filters, assignments, lifecycle |
| `/api/v1/organizations/{id}/departments` (+ `/tree`, `/{id}`, `/parent`, `/head`, `/archive`, `/restore`) | bearer token + membership + `department.*` | Department hierarchy |
| `/api/v1/organizations/{id}/positions` (+ `/{id}`, `/department`, `/archive`, `/restore`) | bearer token + membership + `position.*` | Position catalog |
| `/api/v1/organizations/{id}/teams` (+ `/{id}`, `/department`, `/leader`, `/members`, `/archive`, `/restore`) | bearer token + membership + `team.*` | Teams and their members |
| `/api/v1/organizations/{id}/agents` (+ `/{agentId}`, `/department`, `/activate`, `/pause`, `/archive`, `/history`) | bearer token + membership + `agent.*` | AI agents: lifecycle, search, configuration history |
| `/api/v1/organizations/{id}/agents/{agentId}/providers` (+ `/{bindingId}`), `/skills`, `/tools`, `/memory-config` | bearer token + membership + `agent.manage` / `agent.tool.manage` | Provider bindings (primary/fallback), skills, tool grants, memory settings |
| `/api/v1/organizations/{id}/providers/connections` (+ `/{id}`, `/credential`, `/test`, `/models`), `GET /api/v1/providers/types` | bearer token + membership + `provider.*` | Provider connections. The credential is write-only and never returned. |
| `/api/v1/organizations/{id}/skills` (+ `/{id}`), `GET …/tools` | bearer token + membership + `skill.*` | Skill catalog and tool registry |
| `/api/v1/organizations/{id}/teams/{teamId}/agents` (+ `/{agentId}`) | bearer token + membership + `team.manage` + `agent.read` | AI agents in teams (separate from human members) |
| `POST/GET /api/v1/organizations/{id}/agents/{agentId}/executions`, `GET /api/v1/organizations/{id}/executions[/{executionId}]` | bearer token + membership + `agent.execute` / `agent.execution.read` | Supervised agent executions: launch (202, asynchronous), history (paginated, newest first), result |
| `GET …/executions/{executionId}/tool-calls`, `GET …/tool-invocations` (filters `agentId`, `status`) | bearer token + membership + `agent.execution.read` | Tool calls of one execution (in order) and the organization's tool-call history (newest first). Pages. Arguments are the REDACTED rendering + SHA-256 hash, never raw. |
| `GET …/agents/{agentId}/available-tools` | bearer token + membership + `agent.read` | Tools granted to the agent and what the platform would do with a call (automatic, approval, denied) |
| `GET …/approvals` (filters `status`, `executionId`; page), `GET …/approvals/{approvalId}` | bearer token + membership + `approval.read` | Human approval requests: the exact call (tool, MCP server, risk, redacted arguments, hash, expiry) |
| `POST …/approvals/{approvalId}/approve` `{argumentsHash}`, `POST …/approvals/{approvalId}/reject` `{reason?}` | bearer token + membership + `approval.decide` | Decide ONE request. `approve` needs the SHA-256 of the arguments that were reviewed (64 hex characters); `409` if the request is no longer pending, expired, its execution no longer waits, the arguments changed, or the agent no longer holds the grant. |
| `POST/GET /api/v1/organizations/{id}/workflows`, `GET/PATCH …/workflows/{workflowId}`, `PUT …/workflows/{workflowId}/participants`, `PUT …/workflows/{workflowId}/steps`, `POST …/workflows/{workflowId}/archive` | bearer token + membership + `workflow.manage` (write) / `workflow.read` | Multi-agent workflow definitions (Sprint C3.1): explicit participants, up to 8 ordered static steps, limits. Every change is a new version. |
| `POST …/workflows/{workflowId}/runs` `{objective, requestId?}` (202), `GET …/workflow-runs`, `GET …/workflow-runs/{runId}`, `GET …/workflow-runs/{runId}/steps`, `GET …/workflow-runs/{runId}/messages`, `POST …/workflow-runs/{runId}/cancel` | bearer token + membership + `workflow.execute` (start, cancel; **and** `agent.execute` to start) / `workflow.read` | Runs of a workflow: each step is one agent execution; steps and the append-only message journal are pages. |
| `POST /api/v1/internal/organizations/{id}/executions/{executionId}/{start,credential,complete,fail}` | **service account only** (`ai-orchestrator`) | Drives an execution through its state machine; `credential` is single use, RUNNING only, `no-store` |
| `POST /api/v1/internal/organizations/{id}/executions/{executionId}/tool-calls`, `…/tool-calls/{invocationId}/begin`, `…/tool-calls/{invocationId}/complete`, `…/resume` | **service account only** (`ai-orchestrator`) | The orchestrator asks for a tool call (the Core API decides: automatic / approval / denied), starts an authorized call (revalidated now; returns the arguments once, `no-store`), reports its bounded result, and takes a human decision to continue a paused run (once per pause). |
| `GET /api/v1/internal/organizations/{id}/agents/{agentId}/execution-context` | **service account only** (`ai-orchestrator`) | Read-only, secret-free configuration of an ACTIVE agent for the AI orchestrator |
| everything else | — | `401` without a valid token |

- **Swagger UI (dev):** `http://localhost:8080/swagger-ui.html`. The OpenAPI JSON is at `/v3/api-docs`.
- **Published contract:** [`contracts/openapi/core-api.v1.json`](../../contracts/openapi/core-api.v1.json). It is regenerated by the test suite into `target/openapi/core-api.v1.json`.
- **Errors:** `application/problem+json` (RFC 9457) with `type` (`urn:aiworkforce:problem:*`), `title`, `status`, `detail`, `instance`, `timestamp` and `requestId`. Validation errors add `errors[{field, message}]`.
- **Correlation:** send `X-Request-Id` (8–64 characters among `[A-Za-z0-9-]`), or one is generated. It is echoed in the response, in errors, and in every log line.

## Multi-agent workflows (Sprint C3.1)

A deterministic, sequential pipeline: the Core API is the only owner of the workflow state; each step is a normal supervised
agent execution (same credential broker, tools, approvals, reaper) run by the step agent with ITS OWN grants. There is no
coordinator, no delegation, no parallelism and no agent-to-agent message in this increment. Design:
[`docs/architecture/MULTI_AGENT_WORKFLOWS_C3_DESIGN.md`](../../docs/architecture/MULTI_AGENT_WORKFLOWS_C3_DESIGN.md).

**Definition.** Name, limits (`maxSteps` ≤ 8, `maxRetriesPerStep` 0–2, `tokenBudget`, `deadlineSeconds`), participants and ordered
steps (`name`, `agentId`, `instruction`, `inputsFrom` = earlier ranks whose bounded result summaries the step receives). Participants
are agents explicitly chosen by a person with `workflow.manage` and `agent.read`; they must be `ACTIVE` with a valid, enabled provider.
A team never makes an agent a participant. Every participant must be used by at least one step before the workflow can start.
Each change increments `version`; a run copies the definition into its own snapshot, so editing or archiving a definition never
changes a run in progress.

**Run states** (`WorkflowRunStatus`): `PENDING → RUNNING ⇄ WAITING_APPROVAL → SUCCEEDED | FAILED | TIMEOUT`;
`CANCEL_REQUESTED` (from any open state) `→ CANCELLED`. **Step states**: `RUNNING ⇄ WAITING_APPROVAL → SUCCEEDED | FAILED | CANCELLED`.
A rank has at most one live or successful step (unique index); an automatic retry is a new execution (`attempt` 2, 3) and the failed
one is kept.

**Starting** needs `workflow.execute` **and** `agent.execute`, and the requester is authorized again before every step. At most
`app.workflows.max-open-runs-per-organization` runs are open per organization (3); the open-execution limit of agent executions
(3) stays independent: when it is reached the next step simply waits and is retried by the scheduler. `requestId` (optional) makes a
repeated launch return the same run.

**Scheduler.** `WorkflowMaintenance` (period `app.workflows.maintenance-ms`, 3000) lets `WorkflowScheduler` advance each open run in its
own transaction, with the run row locked: a finished child becomes the next step, FAIL_FAST stops the run at the first failed step,
the total deadline ends the run (`TIMEOUT`) and its active child, the token budget (checked between steps) fails the run
(`TOKEN_BUDGET_EXCEEDED`). All state is in PostgreSQL, so a restart of the Core API or of the orchestrator loses nothing and a repeated
or concurrent pass never creates a second step or execution. Automatic retry only for `PROVIDER_TIMEOUT`, `PROVIDER_UNAVAILABLE`,
`PROVIDER_RATE_LIMIT` and **never** if a tool call of the failed execution ever started (`OUTCOME_UNKNOWN` is never retried).

**Context.** A worker receives only: the objective, its assignment, the secret-masked summaries (≤ 1500 characters each) of the
steps selected in `inputsFrom`, framed as untrusted data, and the remaining budget. No conversation, no other step's assignment,
no credential. The journal (`OBJECTIVE`, `ASSIGNMENT`, `RESULT`, `STATUS`) is bounded and masked.

**Cancellation** is cooperative: the run becomes `CANCEL_REQUESTED` (nothing new starts) and is settled by the scheduler. A child that
has not started, or waits for a person, is ended at once (its approval is closed by the C2 mechanism). A child with a tool call IN
FLIGHT is given `app.workflows.cancel-grace` (30 s) to reach a known outcome; after that it is ended and the call is reported
`OUTCOME_UNKNOWN`. Effects already produced are never presented as undone. The orchestrator learns of it from the `409` of its next
call to the Core API.

| Property (`app.workflows.*`) | Default | Meaning |
| --- | --- | --- |
| `max-open-runs-per-organization` | `3` | Open runs per organization (1–50). |
| `maintenance-ms` | `3000` | Period of the scheduler pass. |
| `cancel-grace` | `PT30S` | Wait for a RUNNING tool call after a cancellation request. |
| `max-result-chars` | `20000` | Length kept for a run's final result. |

## Tool execution and human approval (Sprint C2)

The Core API owns every decision and every record; the orchestrator never writes Core tables. Architecture and threat model:
[`docs/architecture/MCP_AND_TOOL_SECURITY.md`](../../docs/architecture/MCP_AND_TOOL_SECURITY.md),
[`docs/architecture/APPROVAL_RECOVERY_ARCHITECTURE.md`](../../docs/architecture/APPROVAL_RECOVERY_ARCHITECTURE.md).

**Execution states** (`ExecutionStatus`): `PENDING → RUNNING → WAITING_APPROVAL → RUNNING → SUCCEEDED | FAILED`
(`WAITING_APPROVAL` may also go to `FAILED`). There is no `CANCELLED` or `TIMEOUT` state: a timeout is `FAILED` with `errorCode = TIMEOUT`.
`WAITING_APPROVAL` counts as open (it uses the per-organization concurrency budget) until it is decided, expires, or is abandoned.

**Tool call states** (`InvocationStatus`): `DENIED` (final at creation); `AWAITING_APPROVAL → APPROVED | REJECTED | EXPIRED | FAILED`;
`APPROVED → RUNNING → SUCCEEDED | FAILED`. A call starts at most once.
**Approval states** (`ApprovalStatus`): `PENDING → APPROVED | REJECTED | EXPIRED | CANCELLED`; only `PENDING` changes, once.
`CANCELLED` closes an approval whose execution ended before a decision.

**Policy** (`tool_definitions.execution_policy`): `READ_ONLY` runs automatically for an explicitly granted agent;
`APPROVAL_REQUIRED` pauses the run; `DENIED` (default) never runs. Unknown, disabled, ungranted or under-levelled tools are always denied.
Grants, policy version and agent status are revalidated when the call starts, after any approval.

**Properties** (`app.executions.*`, defaults in `ExecutionProperties`; set them like any Spring property, e.g. `APP_EXECUTIONS_APPROVAL_TIMEOUT=PT10M`;
only `EXECUTIONS_TIMEOUT`, `EXECUTIONS_MAX_CONCURRENT`, `EXECUTIONS_DISPATCH_MAX_THREADS`, `EXECUTIONS_DISPATCH_QUEUE` have dedicated variables):

| Property | Default | Meaning |
| --- | --- | --- |
| `approval-timeout` | `PT15M` | A person has this long to decide; then the approval is `EXPIRED` and the run fails (`APPROVAL_EXPIRED`). |
| `resume-give-up` | `PT15M` | A decision the orchestrator never takes up for this long fails the run (`RESUME_FAILED`). |
| `max-tool-calls-per-execution` | `10` | Tool calls one execution may request (denied ones included). |
| `max-tool-argument-bytes` | `8192` | Canonical argument size of one call. |
| `max-tool-output-chars` | `8000` | Tool results are truncated to this before being stored. |
| `approval-maintenance-ms` | `10000` | Period of the approval maintenance pass. |
| `reaper-interval-ms` | `30000` | Period of the stale-execution reaper (only `PENDING`/`RUNNING` are reaped). |

**Approval maintenance** (`ApprovalMaintenance`, PostgreSQL only, no broker; safe with several instances): each pass (1) expires pending
approvals past their deadline, (2) closes approvals of already-ended executions (`CANCELLED` or acknowledged) and closes calls still
`RUNNING` after their execution ended as `FAILED / OUTCOME_UNKNOWN`, (3) re-sends the resume notification of decided approvals the
orchestrator has not acknowledged (at most one worker per 10-second window; attempts are recorded in `tool_approvals`).
The decision itself is committed first and never depends on the notification.

**Idempotency.** Delivery is *at least once*; processing is idempotent: `resume` succeeds once per pause (a duplicate gets `409` and does
nothing), `begin` succeeds once per call, a decided approval cannot be decided again. This is **not** exactly-once delivery.

**Troubleshooting.** An execution stuck in `WAITING_APPROVAL` with a decided approval: check the orchestrator is reachable
(`ORCHESTRATOR_URL`), the `ai-workforce-core-api` service account secret, and `tool_approvals.resume_attempts` /
`resume_acknowledged_at`; after `resume-give-up` the run fails with `RESUME_FAILED`. `STATE_UNAVAILABLE` on resume means the orchestrator
could not open the sealed state (wrong or missing `AIO_STATE_ENCRYPTION_KEY`, see `docs/architecture/STATE_ENCRYPTION.md`). After a
restart of the Core API nothing needs to be done: all state is in PostgreSQL.

## Tests

```bash
./mvnw clean verify     # 255 tests: unit, web/security, architecture, integration (PostgreSQL, Mailpit, Keycloak)
./mvnw clean package    # tests + executable jar in target/
./mvnw dependency:analyze
```

The integration tests start `pgvector/pgvector:0.8.7-pg18` (with the production role script), `axllent/mailpit:v1.33.1` and `quay.io/keycloak/keycloak:26.8.2` (with the versioned realm) with Testcontainers. Access tokens for most tests are signed by an in-test key published as a JWKS and validated by the production decoder; `KeycloakIntegrationTest` runs a real PKCE login. Without Docker they are **skipped**, not failed, and Maven reports them as skipped.

## Docker

```bash
# Build and run API + database (from the repository root)
docker compose -f infra/docker/compose.yaml --env-file infra/docker/.env --profile app up -d --build

# Stop (data is kept in the ai-workforce-postgres-data volume)
docker compose -f infra/docker/compose.yaml --env-file infra/docker/.env --profile app down
```

The image is a multi-stage build:
- a JDK stage packages the jar;
- a JRE runtime stage runs it with the Spring Boot layered layout, as the unprivileged user `app` (uid 10001).

Migrations run inside the application at startup, never from an entrypoint script. Containers start without a profile (production-safe) unless `SPRING_PROFILES_ACTIVE` is set; compose uses `dev` by default.

## Package layout

```
com.aiworkforce.core
├── CoreApiApplication
├── config/        AppProperties, SecurityConfig, CorsConfig, OpenApiConfig, TimeConfig
├── common/        exception/, logging/, id/ (UUID v7), web/ (pagination), persistence/ (@TenantScoped)
├── health/        HealthController, ApplicationHealthService, HealthResponse
├── auth/          JWT decoder and access-token validators
├── identity/      users, external identities (iss, sub), JIT provisioning, /me
├── audit/         append-only audit events
├── access/        memberships, roles, permissions, tenant check, anti-escalation, member/role endpoints
├── organization/  organizations, audit listing
├── invitation/    invitations (hashed tokens, e-mail delivery, acceptance)
├── workforce/     shared kernel of the workforce modules: statuses and ports (EmployeeDirectory, EmployeeLifecycleParticipant)
├── department/    hierarchical departments (cycle-free, archive instead of delete)
├── position/      job titles (never permissions)
├── employee/      employee profiles (optional membership link, reporting lines, lifecycle)
└── team/          teams and member history
```

Future business modules (`agent`, `project`…) are sibling packages, following the rules in the architecture document and enforced by `ArchitectureTest`.

## AI coordinator and dynamic delegation (Sprint C3.2)

A workflow may be `mode: COORDINATED`: instead of static steps, a chosen **coordinator agent** (a real B3 agent, also an explicit
participant, with its own provider credential) decides what happens next. The Core API stays the only authority: *the Core API decides what
is allowed, the coordinator decides what should happen next, workers execute only what they are explicitly authorized to perform.*
`PIPELINE` (C3.1) is unchanged. Design: [`docs/architecture/MULTI_AGENT_WORKFLOWS_C3_DESIGN.md`](../../docs/architecture/MULTI_AGENT_WORKFLOWS_C3_DESIGN.md) §11.

- `V8__workflow_coordinator.sql` is additive: `mode` and `coordinator_agent_id` on definitions, `mode` on runs, `COORDINATOR` step role,
  and `workflow_actions` (idempotent per coordinator execution and provider `tool_call_id`, one accepted action per turn, no repeated
  `(worker, task)`). V1–V7 are untouched.
- Create with `mode: COORDINATED`; `PUT …/participants` takes `coordinatorAgentId` (must be among `agentIds`); a coordinated workflow has no
  static steps and needs at least one worker besides the coordinator to start. The run snapshot freezes mode, coordinator and
  participants, and names workers by opaque refs (`worker_1`, …).
- Each coordinator turn and each worker step is a child execution (C1). The coordinator has only two **platform-native actions**,
  `workflow.delegate` and `workflow.finish` (not MCP tools; a coordinator turn can reach no MCP tool). The orchestrator forwards them to
  `POST /api/v1/internal/organizations/{org}/executions/{executionId}/workflow-actions` (service account only); the Core API validates
  (participant, state, budgets, step capacity, requester authorization, duplicates, one action per turn), records them (accepted or
  rejected, with a code) **before** anything starts, and the scheduler creates the worker. `GET …/workflow-runs/{runId}/actions` lists them.
- Limits: ≤ 8 worker steps, depth 1, sequential; coordinator turns ≤ `maxSteps + 1`; cumulative token and time budgets include the
  coordinator; a turn without an action gets one corrective turn, then `COORDINATOR_NO_ACTION`. Approvals remain C2 approvals on the workers' tools.
- Anthropic real connectivity is **NOT VERIFIED**; tests use a simulated provider.

## Workflow reliability (Sprint C3.3)

Failure handling for both modes; the Core API owns every decision (design: `docs/architecture/MULTI_AGENT_WORKFLOWS_C3_DESIGN.md` §12).

- `V9__workflow_reliability.sql` (additive; V1–V8 untouched): `failure_policy`, `recovery_mode`, `step_timeout_seconds` on definitions; `failure_policy`,
  `manual_retries` and the non-terminal status `WAITING_RECOVERY` on runs; on steps (= attempts) `launch_reason`, `failure_category`, `retry_safe`,
  `next_attempt_at`, `deadline_at`, `retry_requested_by`.
- Definition settings (create/update): `failurePolicy` = `FAIL_FAST` (default) | `CONTINUE` | `RETRY` (needs `maxRetriesPerStep` ≥ 1); `recoveryMode` = `NONE` |
  `MANUAL` (not with `CONTINUE`); `stepTimeoutSeconds` (10 to the workflow deadline, 0 removes it).
- Every failed attempt is classified (`TRANSIENT_PROVIDER_FAILURE`, `PERMANENT_PROVIDER_FAILURE`, `TOOL_FAILURE`, `OUTCOME_UNKNOWN`, `AUTHORIZATION_FAILURE`,
  `WORKFLOW_LIMIT_EXCEEDED`, `UNCLASSIFIED`). Automatic retries: transient provider failure, no tool ever started, within `maxRetriesPerStep`, never after a manual
  retry, never past the deadline; delay `base × 2^(n-1)` capped, with jitter, stored in PostgreSQL (`app.workflows.retry-base-delay` 5 s, `retry-max-delay` 60 s).
  Each attempt is a new execution; previous attempts stay visible. `OUTCOME_UNKNOWN` is never replayed.
- `POST …/workflow-runs/{runId}/steps/{rank}/retry` (`workflow.execute` + `agent.execute`): manual retry of the failed step that holds a run in `WAITING_RECOVERY`
  (3 per run); everything is revalidated; a repeated request is a `409`.
- `GET …/workflow-runs/{runId}/summary` (`workflow.read`): durations, attempts, retries, failures by category, input/output tokens (coordinator vs workers), remaining
  budget, next scheduled retry. Tokens are not money.
- Internal `start` answers carry `deadlineSeconds` for executions that belong to a workflow.
- Limits: no reconciliation of unknown outcomes, no exactly-once guarantee for external effects, tool failures are never replayed.

## Controlled parallel execution (Sprint C3.4)

Independent workers may run at the same time, in both modes; the default stays sequential (`maxParallelWorkers` = 1). Design: `docs/architecture/MULTI_AGENT_WORKFLOWS_C3_DESIGN.md` §13.

- `V10__workflow_parallel_execution.sql` (additive): `QUEUED` steps (accepted work persisted before dispatch, `execution_id` nullable only while queued or cancelled), `group_no`, token
  reservations, `max_parallel_workers` (1..3) on definitions and runs, several accepted delegations per coordinator turn (one finish).
- Definitions: `maxParallelWorkers` (1–3, never combined with manual recovery); pipeline steps take an optional `stage` (all or none; same stage = parallel; contiguous; stage size ≤ the bound;
  dependencies only on earlier stages; default input = every step of the previous stage). Coordinated workflows: `workflow.delegate` up to the bound per turn (`maxDelegationsPerTurn` in the internal `start` answer).
- Scheduling: a group is persisted as `QUEUED` steps, dispatched within `min(maxParallelWorkers, app.workflows.max-parallel-workers)`, the C1 organization capacity (3 open executions) and the token budget
  (reservation `app.workflows.worker-token-reservation`, replaced by actual usage); fan-in only when every rank of the group is resolved. Policies of C3.3 apply per group (FAIL_FAST stops siblings; CONTINUE keeps
  failures apart and never goes past a limit; RETRY replaces only the failed worker).
- Observability: `GET …/workflow-runs/{runId}/summary` now has `groups` (status, workers, queued, running, waiting for approval, succeeded, failed), `peakConcurrency`, `reservedTokens`, per-attempt `queueWaitMs`;
  steps expose `groupNo` and `QUEUED`.
- Failed executions now report the provider usage already consumed (`inputTokens`/`outputTokens` on the internal `fail`): it counts toward the workflow budget.
- Not guaranteed: exactly-once external effects; no manual recovery in parallel workflows; no per-provider concurrency limit.
