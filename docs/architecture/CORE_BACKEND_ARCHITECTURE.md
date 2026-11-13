# Core Backend Architecture

**Status:** Sprint B0 (foundation) — 2026-11-12
**Code:** `services/core-api` · **Decisions:** `docs/adr/`

## 1. Context

```
┌──────────────────────────┐   HTTPS /api/v1 (JSON)   ┌─────────────────────────────┐
│ Desktop (Angular+Electron)│ ───────────────────────▶ │ Core API (Spring Boot)       │
│ apps/desktop              │ ◀─────────────────────── │ services/core-api            │
└──────────────────────────┘   WebSocket (planned)    │  modular monolith            │
                                                       └──────┬───────────────┬──────┘
                                                              │ JDBC          │ HTTP / events (planned)
                                                       ┌──────▼──────┐  ┌─────▼──────────────────┐
                                                       │ PostgreSQL  │  │ AI Orchestrator         │
                                                       │ + pgvector  │  │ Python/FastAPI/LangGraph│
                                                       └─────────────┘  └────────────────────────┘
```

The Core API is the **system of record and the security boundary**:
- it owns organizations, people, agents, projects, tasks, approvals and conversations;
- it decides who may do what;
- it is the only component that writes business data.

The AI orchestrator executes agent work **on behalf of** the Core API, never around it.

## 2. Modular monolith

One deployable Spring Boot application, organized in **business modules** that are separate top-level packages ([ADR-0001](../adr/ADR-0001-modular-monolith.md)). This keeps operations simple (one deployment, one transaction boundary, one database) while making the module boundaries explicit, so that a module can be extracted later if a real scaling or ownership need appears.

### Package structure

```
com.aiworkforce.core
├── CoreApiApplication
├── config/            framework configuration (security, CORS, OpenAPI, clock, properties)
├── common/            cross-cutting technical code, no business knowledge
│   ├── exception/     RFC 9457 error handling
│   └── logging/       request correlation
├── health/            public health endpoint                       ← implemented (B0)
│
├── auth/              authentication, tokens, current principal   ← B1
├── organization/      organizations, settings                     ← B1
├── membership/        user ↔ organization membership, tenant context ← B1
├── role/ permission/  roles, permissions, authorization rules     ← B1
├── employee/ department/  human workforce, structure
├── agent/ provider/   AI employees (identity) and provider bindings
├── project/ task/     projects, tasks, Kanban state
├── approval/          human approval workflow for sensitive operations
├── conversation/      conversations, messages, files
├── report/            reports and documents
└── audit/             immutable audit trail
```

### Inside a module

```
agent/
├── api/              AgentController, request/response DTOs, DtoMapper
├── application/      AgentService (use cases, transactions, authorization checks)
├── domain/           Agent entity, value objects, domain rules
└── persistence/      AgentRepository (Spring Data), queries
```

| Layer | Responsibility | Must not |
| --- | --- | --- |
| `api` | HTTP transport: routing, validation of DTOs, status codes, OpenAPI annotations | contain business rules, touch repositories, return entities |
| `application` | Use cases, transactions (`@Transactional`), tenant scoping, authorization | depend on controllers or HTTP types |
| `domain` | Entities and invariants | depend on Spring MVC or other modules' internals |
| `persistence` | Spring Data repositories, organization-scoped queries | be called from controllers |

Rules (enforced by `ArchitectureTest` with ArchUnit):
- no dependency cycles between modules;
- `common` depends on no module;
- controllers never use repositories nor reference `@Entity` classes;
- services never depend on controllers;
- constructor injection only.

Modules talk to each other **through their application services** (or through domain events later), never through another module's repositories or entities.

Mapping between entities and DTOs is explicit: hand-written mappers or records. No mapping framework is added before it is needed.

## 3. Data

- **PostgreSQL 18 + pgvector** ([ADR-0004](../adr/ADR-0004-postgresql-pgvector.md)), one database `ai_workforce`.
- **Flyway owns the schema**; Hibernate only validates it ([ADR-0003](../adr/ADR-0003-flyway-schema-ownership.md)). Conventions: [`DATABASE_CONVENTIONS.md`](../database/DATABASE_CONVENTIONS.md).
- **UTC everywhere:** JVM clock (`Clock` bean), JDBC session (`SET TIME ZONE 'UTC'`), Hibernate (`jdbc.time_zone=UTC`), JSON (`Instant` serialized as ISO 8601 with `Z`).
- `open-in-view` is disabled: lazy loading never happens during JSON rendering, and data access stays inside services.
- **Embeddings:** `vector` columns in PostgreSQL for agent memory and document search. Each embedding table is organization-scoped like any other business table.

## 4. Multi-tenancy strategy

> Implemented in Sprint B1: see [MULTI_TENANCY_ARCHITECTURE.md](MULTI_TENANCY_ARCHITECTURE.md). Modules now present: `identity`, `auth`, `audit`, `access`, `organization`, `invitation`.

Shared database, shared schema, **`organization_id` on every organization-owned row**.

| Concern | Strategy |
| --- | --- |
| Tenant resolution | The tenant comes from the route (`/api/v1/organizations/{organizationId}/...`), and is **accepted only after verifying the authenticated user's membership server-side**. |
| Enforcement point | Application services, through a `TenantContext` resolved per request after authorization. Repository methods always take the organization id (`findByOrganizationIdAndId`). |
| Not found vs forbidden | A resource of another organization returns **404**, so its existence is not revealed. |
| Database guarantees | `organization_id not null`, tenant-scoped unique constraints and composite foreign keys. Row-Level Security is planned as defence in depth. |
| Background work | Jobs and orchestrator callbacks carry an explicit organization id and an acting principal, and are re-authorized by the Core API. |
| Tests | Every B1+ module adds tests proving that user A cannot read or modify organization B's data (horizontal privilege escalation). |

**Status:** documented only. No tenant data exists in Sprint B0, so isolation is **not** implemented or verified yet. See [`SECURITY_ARCHITECTURE.md`](SECURITY_ARCHITECTURE.md) §3.

## 5. API conventions

- **Versioning:** every business endpoint lives under `/api/v1/`. Breaking changes require `/api/v2/`.
- **Resources:** plural nouns, organization-scoped paths (`/api/v1/organizations/{organizationId}/agents/{agentId}`).
- **Methods and status codes:**

  | Method | Result |
  | --- | --- |
  | `GET` | 200 |
  | `POST` | 201 with a `Location` header |
  | `PUT` / `PATCH` | 200 |
  | `DELETE` | 204 |
  | Validation error | 400 |
  | Unauthenticated / forbidden | 401 / 403 |
  | Missing resource | 404 |
  | Conflict (e.g. an approval already decided) | 409 |

- **DTOs:** Java records, separate request and response types, Bean Validation on requests. Entities are never serialized.
- **JSON:** camelCase, `Instant` timestamps in ISO 8601 UTC, identifiers as strings.
- **Pagination** (for list endpoints): `?page=0&size=20&sort=createdAt,desc`, with a maximum `size` of 100. The response is:

  ```json
  { "content": [...], "page": 0, "size": 20, "totalElements": 135, "totalPages": 7 }
  ```

  Unbounded lists are not allowed.
- **Errors:** RFC 9457 `application/problem+json` ([ADR-0005](../adr/ADR-0005-problem-details.md)). Clients branch on `type`, never on `detail`.
- **Idempotency:** decision endpoints (for example approvals) are final and return 409 when repeated.
- **Documentation:** springdoc generates OpenAPI 3.1. The published contract is `contracts/openapi/core-api.v1.json`.

## 6. Cross-cutting concerns

| Concern | Implementation |
| --- | --- |
| Correlation | `RequestIdFilter`: `X-Request-Id` validated or generated, put in the MDC and echoed in responses and errors |
| Logging | SLF4J / Logback. Every line carries `[requestId]`. The access log records method, path, status and duration only. `prod` emits JSON (ECS). |
| Health | `/api/v1/health` (public, minimal) and Actuator `/actuator/health` with liveness/readiness groups. Details are shown in `dev` only. |
| Configuration | `application.yml` is production-safe; profiles `dev`, `test`, `prod`; secrets come from the environment ([ADR-0006](../adr/ADR-0006-configuration-and-secrets.md)) |
| Threads | Virtual threads enabled for request handling (Java 21) |
| Observability (planned) | Micrometer metrics and OpenTelemetry tracing when a collector exists. The MDC request id is the bridge until then. |

## 7. AI orchestrator integration (planned)

- **Direction:** the Core API creates an *execution* record and dispatches it to the orchestrator (HTTP first; a Redis-backed queue only when throughput or retries require it). The orchestrator reports progress back through authenticated, idempotent callbacks.
- **Identity:** a service-to-service credential (client credentials or mTLS), separate from user tokens. Every call carries the organization id, the agent id and the execution id. The Core API re-checks them.
- **Supervision:** the orchestrator cannot perform a sensitive operation directly. It requests an **approval** from the Core API, and continues only after a human decision recorded by the Core API.
- **Data access:** the orchestrator reads context and memory through Core API endpoints (or a read-scoped view), never by writing to business tables.
- **Provider secrets:** provider credentials stay server-side (orchestrator or secret manager) and are never sent to the desktop client.

## 8. Frontend integration

The desktop already isolates data access: `Component → Signal store → abstract API → implementation`.

Sprint B1+ adds `Http*Api` implementations of the existing abstract classes. They are registered by `provideDataLayer` when `dataSource: 'api'`, and the mocks stay for tests and demos. The base URL comes from `API_CONFIG`. Authentication is attached by a single HTTP interceptor. The desktop CSP `connect-src` must then include the API origin (see the Phase A audit, recommendation 6).
