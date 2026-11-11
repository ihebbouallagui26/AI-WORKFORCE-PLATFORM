# Sprint B0 — Core Backend Foundation: Implementation Report

**Date:** 2026-11-12 · **Branch:** `feat/core-api-foundation` (from `main` = `364a848`, Phase A merged and tagged `v0.1.0-desktop`) · **Not committed.**

Legend: ✅ implemented and tested · 🟡 implemented, not fully tested · 📋 planned (next sprint) · ⛔ blocked

## 1. Repository audit (start of sprint)

| Item | Finding |
| --- | --- |
| Git | Branch `feat/core-api-foundation`, clean working tree. Phase A commit `cf88773` is an ancestor of `main`. |
| `services/core-api` | Empty directory |
| PostgreSQL infrastructure | None: no compose file, container, volume or `.env`. Port 5432 free. |
| `contracts/openapi`, `infra/docker`, `docs/*` | Empty skeleton directories |
| Root `.gitignore` | Missing, so `.env` files would not have been ignored |
| Toolchain | Temurin JDK 21.0.12, Maven 3.9.11, Docker 29.0.1 (daemon started during the audit) |
| Local constraint | Port **8080** is occupied by Oracle TNS Listener / XML DB on the development machine |

## 2. Implemented architecture

- **Modular monolith:** Spring Boot application in `services/core-api`, package `com.aiworkforce.core` ([ADR-0001](adr/ADR-0001-modular-monolith.md)).
- **Packages:**
  - `config/` (security, CORS, OpenAPI, clock, typed properties);
  - `common/exception/` (RFC 9457);
  - `common/logging/` (request correlation);
  - `health/` (first `/api/v1` endpoint).
- **Layer and module rules** are documented in [CORE_BACKEND_ARCHITECTURE.md](architecture/CORE_BACKEND_ARCHITECTURE.md) and enforced by ArchUnit.

## 3. Files created or modified

| Area | Files |
| --- | --- |
| Root | `.gitignore` (new: `.env` ignored, `*.env.example` allowed), `README.md` (status, quick start, documentation links) |
| Infrastructure | `infra/docker/compose.yaml`, `infra/docker/.env.example` |
| Core API build | `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `Dockerfile`, `.dockerignore`, `.gitattributes`, `.gitignore`, `.env.example`, `README.md` |
| Main code (11 classes) | `CoreApiApplication`, `config/{AppProperties, CorsConfig, SecurityConfig, OpenApiConfig, TimeConfig}`, `common/exception/{GlobalExceptionHandler, ProblemType, ResourceNotFoundException}`, `common/logging/RequestIdFilter`, `health/{HealthController, ApplicationHealthService, HealthResponse}` |
| Resources | `application.yml`, `application-dev.yml`, `application-test.yml`, `application-prod.yml`, `db/migration/V1__enable_extensions.sql` |
| Tests (10 classes) | `architecture/ArchitectureTest`, `common/exception/GlobalExceptionHandlerTest`, `common/logging/RequestIdFilterTest`, `config/{AppPropertiesTest, SecurityConfigTest}`, `health/ApplicationHealthServiceTest`, `integration/{DatabaseAndApplicationIntegrationTest, ApiDocumentationIntegrationTest}`, `support/{TestcontainersConfiguration, TestSupportController}` |
| Contracts | `contracts/openapi/core-api.v1.json` (generated), `contracts/openapi/README.md` |
| Documentation | `docs/architecture/CORE_BACKEND_ARCHITECTURE.md`, `docs/architecture/SECURITY_ARCHITECTURE.md`, `docs/database/DATABASE_CONVENTIONS.md`, `docs/adr/README.md` + ADR-0001…0007, this report |

`apps/desktop` was not modified (verified: no tracked changes under `apps/`).

## 4–6. Versions and dependencies

| | Version | Source |
| --- | --- | --- |
| Java | 21 (Temurin 21.0.12 locally, image `eclipse-temurin:21.0.12.1_1`) | — |
| Spring Boot | **4.1.2** (latest GA; 4.2.0 is RC) | parent POM |
| Spring Framework / Security | 7.0.10 / 7.1.2 | Boot BOM |
| Hibernate ORM | 7.4.13.Final | Boot BOM |
| Flyway | 12.4.0 (+ `flyway-database-postgresql`) | Boot BOM |
| PostgreSQL JDBC | 42.7.14 | Boot BOM |
| Jackson | 3.1.7 | Boot BOM |
| springdoc-openapi | 3.2.0 (built against Boot 4.1.2) | explicit |
| JUnit / Mockito / Testcontainers | 6.0.3 / 5.23.0 / 2.0.5 | Boot BOM |
| ArchUnit | 1.5.1 | explicit, test |
| Maven | 3.9.11 via Wrapper 3.3.4 | `.mvn/wrapper` |

**Declared dependencies:**
- **Compile:** starters `webmvc`, `validation`, `security`, `actuator`, `data-jpa`, `flyway`; `flyway-database-postgresql`; `springdoc-openapi-starter-webmvc-ui`.
- **Runtime:** `postgresql`.
- **Test:** starters `test`, `webmvc-test`, `security-test`; `spring-boot-testcontainers`; `testcontainers-postgresql`; `testcontainers-junit-jupiter`; `archunit-junit5`.

Rationale: [ADR-0002](adr/ADR-0002-spring-boot-4-java-21.md). Redis was not introduced (no use case yet).

## 7–8. PostgreSQL and Flyway ✅

- **Image and engine:** `pgvector/pgvector:0.8.7-pg18`; the engine reports PostgreSQL 18.6.
- **Database:** `ai_workforce`, credentials taken from `infra/docker/.env`.
- **Container settings:** named volume `ai-workforce-postgres-data` at `/var/lib/postgresql`, health check `pg_isready`, `restart: unless-stopped`, port bound to `127.0.0.1`.
- **`V1__enable_extensions.sql`:** `CREATE EXTENSION IF NOT EXISTS vector`. No business tables.
- **Verified on the compose database:**
  - `Migrating schema "public" to version "1 - enable extensions"`, then `up to date` on the next start;
  - the vector distance operator works (`<->` returns 1.0);
  - Hibernate runs in `validate` mode.
- **Fixed during the sprint:** sessions inherited the host JVM time zone (`Africa/Tunis`), detected by an integration test. Every connection now runs `SET TIME ZONE 'UTC'`.

## 9. Security configuration ✅

- **Deny by default:** public endpoints are only `GET /api/v1/health`, `GET /actuator/health/**`, `GET /actuator/info`, and the API docs when enabled (`dev`).
- **Session and authentication:** stateless; CSRF disabled (bearer-token design); Basic, form login and logout disabled.
- **No user account exists:** the generated in-memory user is excluded, and this is verified by a test.
- **CORS:** exact origins from configuration. Wildcards and malformed origins fail at startup. Credentials are disabled.
- **401/403:** produced in RFC 9457 format with `WWW-Authenticate: Bearer realm="ai-workforce"`.
- **Headers:** `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`, restrictive CSP.
- **Method security:** `@EnableMethodSecurity` is active.

Tenant isolation is **📋 documented, not implemented**: no authenticated principal exists yet ([SECURITY_ARCHITECTURE.md](architecture/SECURITY_ARCHITECTURE.md) §3).

## 10. API endpoints

| Endpoint | Status |
| --- | --- |
| `GET /api/v1/health` → `{status, service, version, timestamp}` (200 / 503) | ✅ |
| `GET /actuator/health`, `/liveness`, `/readiness`, `GET /actuator/info` | ✅ |
| `/v3/api-docs`, `/swagger-ui.html` (dev only) | ✅ |
| Business endpoints | 📋 B1+ (any `/api/**` path answers 401 today) |

## 11. Tests executed (actual results)

Command: `./mvnw -B clean test`, with Docker running.

| Test class | Kind | Result |
| --- | --- | --- |
| `AppPropertiesTest` | unit: CORS origin rules | 12/12 ✅ |
| `RequestIdFilterTest` | unit: correlation id, log-injection guard, MDC cleanup | 9/9 ✅ |
| `ApplicationHealthServiceTest` | unit: UP → 200, DOWN → 503, version | 3/3 ✅ |
| `GlobalExceptionHandlerTest` | web: validation, malformed JSON, 404/405/415/500, method security 403 | 9/9 ✅ |
| `SecurityConfigTest` | web: public vs protected (8 paths), headers, CORS allowed/denied, no cookies | 14/14 ✅ |
| `ArchitectureTest` | ArchUnit: cycles, `common` independence, layers, injection | 6/6 ✅ |
| `DatabaseAndApplicationIntegrationTest` | Testcontainers PG18: connection, UTC, Flyway, pgvector, `validate`, no user store, health, closed actuator endpoints | 8/8 ✅ |
| `ApiDocumentationIntegrationTest` | Testcontainers: OpenAPI 3.1 (`/api/**` only), Swagger UI | 2/2 ✅ |
| **Total** | | **63 run, 0 failures, 0 errors, 0 skipped** |

Other checks:
- `./mvnw -B clean package` → BUILD SUCCESS (63/63, executable jar).
- `./mvnw dependency:analyze` → only the expected starter pattern (see ADR-0002); no superfluous library.
- Build output contains no warnings, after attaching the Mockito agent explicitly (JDK 21 dynamic-agent warning removed).
- `git diff --check` → clean.

Defects found by the tests and fixed during the sprint:
1. Hikari `connection-timeout` did not accept a duration string.
2. Actuator's `HealthDescriptor` is sealed in Spring Boot 4 and cannot be mocked; the service now takes an injectable status source.
3. The JDBC session time zone was not UTC.
4. Surefire `argLine` needed a declared empty property and quoting (local repository path with spaces).

## 12. Docker execution results ✅

| Check | Result |
| --- | --- |
| `docker compose … config` | valid |
| `up -d postgres` | healthy in about 5 s |
| `--profile app up -d --build` | image `ai-workforce/core-api:local` (528 MB) built; API started in 6.7 s, migrations up to date |
| Runtime user | `uid=10001(app) gid=10001(app)` |
| `GET /api/v1/health` (container) | `200 {"status":"UP",…}` |
| Business endpoint (container) | `401` |
| `prod` profile container | JSON (ECS) logs; `/actuator/health` → `{"status":"UP"}` without components; Swagger and API docs 401; dev origin CORS preflight 403 |
| `prod` without `APP_CORS_ALLOWED_ORIGINS` | refuses to start: `app.cors.allowedOrigins … must not be empty` |
| `./mvnw spring-boot:run` (dev, compose DB) | started on 8081 (8080 occupied locally); Swagger UI 200; DB password, `Authorization` header and query strings **absent from logs** (checked with grep) |

## 13. Known limitations

- No authentication, users, organizations or business endpoints (by design: B1+).
- Tenant isolation is documented but not implemented or tested.
- The OpenAPI contract contains only `/api/v1/health`. It is copied manually from `target/openapi` (no CI drift check yet).
- Local port conflict: 8080 is used by Oracle on this machine. `SERVER_PORT=8081` is set in the local, Git-ignored `infra/docker/.env`. The desktop `apiBaseUrl` (8080) must match when wired in B1.
- No CI pipeline yet. Commands were run locally.
- Image size of 528 MB (Ubuntu JRE base). A smaller base (Alpine or distroless) can be evaluated.

## 14. Remaining risks

| Risk | Mitigation |
| --- | --- |
| The application connects as the database owner (created by the container) | B1: separate a migration role (DDL) from a least-privilege runtime role (DML only) |
| Flyway at startup with several replicas | Flyway's lock covers it. Move to a migration job if deployments get complex. |
| Electron renderer origin (`app://workforce`) not yet verified against CORS | Verify with the packaged app in B1, or route API calls through the main process |
| Spring Boot 4 / Jackson 3 ecosystem is young | Pinned GA versions, broad test coverage, ADR-0002 documents the choice |
| Health endpoint exposes the application version | Intentional (client compatibility). Remove it if a security review requires. |

## 15. Commands to start the backend

```bash
cp infra/docker/.env.example infra/docker/.env        # set DB_PASSWORD (and SERVER_PORT if 8080 is taken)
docker compose -f infra/docker/compose.yaml --env-file infra/docker/.env up -d postgres
cd services/core-api
./mvnw spring-boot:run                                # dev profile
./mvnw clean test                                     # all tests (Docker required for integration tests)
# Everything in Docker:
docker compose -f infra/docker/compose.yaml --env-file infra/docker/.env --profile app up -d --build
```

## 16. Recommended next sprint — B1: Authentication, Authorization & Multi-Tenancy

1. ADR-0008: choose the identity provider (Keycloak locally, OIDC in the cloud). Configure the OAuth2 resource server (JWT: `iss`, `aud`, `exp`, JWKS).
2. Flyway V2: `users`, `organizations`, `organization_memberships`, `roles` / permissions, following the database conventions (`organization_id` scoping, composite foreign keys).
3. `TenantContextResolver` (membership check per request), permission evaluator, `@PreAuthorize` on services, and an ArchUnit rule against unscoped tenant repository methods.
4. First business endpoints: `GET /api/v1/me`, `GET /api/v1/organizations`, `GET /api/v1/organizations/{id}`, with **cross-organization 404 tests** (horizontal privilege escalation).
5. Separate migration and runtime database roles.
6. Desktop:
   - add `HttpWorkspaceApi` behind `dataSource: 'api'`;
   - Authorization Code + PKCE through the system browser;
   - an Angular auth interceptor;
   - extend the CSP `connect-src` with the API origin.
7. CI: `./mvnw verify` with Testcontainers, OpenAPI drift check, `npm test` for the desktop.
