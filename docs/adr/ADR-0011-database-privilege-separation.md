# ADR-0011 — Separate database roles for migrations, runtime and Keycloak

- **Status:** Accepted
- **Date:** 2026-11-12 (Sprint B1)

## Context
In B0 the API and Flyway used the bootstrap superuser. A SQL injection or a compromised API process would then own the whole database server, including the identity provider's data.

## Decision
- `aiw_migrator` owns the schema and is used only by Flyway; `aiw_app` (runtime) has data access only: no DDL, no access to the migration history, no write on the permission catalog, no update or delete of audit events; `keycloak` owns a separate database and cannot connect to the application database.
- Roles are created by an idempotent script (`infra/docker/postgres/init-roles.sh`, `db-init` service), which also creates superuser-only extensions and transfers ownership of existing B0 objects. A Flyway `afterMigrate` callback re-applies the runtime grants.
- Integration tests use the same script and roles.

## Consequences
- Two credentials for the API (`DB_MIGRATION_*`, `DB_APP_*`); in production migrations may run as a separate job with the migrator credentials only.
- New superuser-only needs (extensions) go into the role script, not into migrations.

## Alternatives considered
- **Single owner role:** simpler, rejected (runtime with DDL rights).
- **Separate PostgreSQL server for Keycloak:** stronger isolation, more resources locally; a separate database and role is sufficient for now.
