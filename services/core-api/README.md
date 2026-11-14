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
| `APP_CORS_ALLOWED_ORIGINS` | **yes** outside `dev` | `http://localhost:4200` in `dev` only | Comma-separated exact origins. Wildcards are rejected at startup. |

Secrets are never committed: `.env` files are ignored by Git, and only `*.env.example` files with placeholders are versioned.

## Database and migrations

- The schema is owned by **Flyway**: `src/main/resources/db/migration/V<n>__<description>.sql`. Migrations run automatically at startup, before Hibernate validates the schema (`ddl-auto=validate`).
- `V1__enable_extensions.sql` enables `pgvector`; `V2__identity_and_organizations.sql` adds identity, organizations, RBAC, invitations and audit; `V3__workforce_management.sql` adds employee profiles, departments, positions, teams and ten permissions (granted to the built-in roles of existing organizations). `afterMigrate.sql` re-applies the runtime role's grants.
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
| everything else | — | `401` without a valid token |

- **Swagger UI (dev):** `http://localhost:8080/swagger-ui.html`. The OpenAPI JSON is at `/v3/api-docs`.
- **Published contract:** [`contracts/openapi/core-api.v1.json`](../../contracts/openapi/core-api.v1.json). It is regenerated by the test suite into `target/openapi/core-api.v1.json`.
- **Errors:** `application/problem+json` (RFC 9457) with `type` (`urn:aiworkforce:problem:*`), `title`, `status`, `detail`, `instance`, `timestamp` and `requestId`. Validation errors add `errors[{field, message}]`.
- **Correlation:** send `X-Request-Id` (8–64 characters among `[A-Za-z0-9-]`), or one is generated. It is echoed in the response, in errors, and in every log line.

## Tests

```bash
./mvnw clean verify     # 137+ tests: unit, web/security, architecture, integration (PostgreSQL, Mailpit, Keycloak)
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
