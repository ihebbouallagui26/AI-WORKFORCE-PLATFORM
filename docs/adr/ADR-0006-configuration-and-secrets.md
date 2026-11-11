# ADR-0006 — Production-safe base configuration, secrets from the environment

- **Status:** Accepted
- **Date:** 2026-11-12 (Sprint B0)

## Context
The API runs from Maven during development, in Docker for local integration, and later in the cloud. A forgotten profile must never expose development features (Swagger, health details, permissive CORS) in production.

## Decision
- `application.yml` is the **production-safe base**: API docs off, health details hidden, no CORS default, no credential defaults.
- Profiles add behaviour:
  - `dev`: docs, health details, local `.env` import, `localhost:4200` CORS default;
  - `test`: CORS for tests;
  - `prod`: JSON logs.
- No profile is activated implicitly, except `spring-boot:run`, which uses `dev`.
- Secrets and environment-specific values come from environment variables (`DB_*`, `APP_CORS_ALLOWED_ORIGINS`, `SERVER_PORT`). In development they live in Git-ignored `.env` files; only `*.env.example` placeholders are committed.
- Missing or unsafe values fail startup: no database password, empty or wildcard CORS origins.

## Consequences
- Misconfiguration is detected at startup instead of silently weakening security.
- Developers create `infra/docker/.env` once. Compose and the API share the same variables.

## Alternatives considered
- **Defaulting to the `dev` profile:** convenient, but unsafe if a container is started without configuration.
- **Spring Cloud Config / Vault integration now:** premature. The environment-variable contract is compatible with both later.
