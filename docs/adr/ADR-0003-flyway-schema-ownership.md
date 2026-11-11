# ADR-0003 — Flyway owns the schema, Hibernate validates

- **Status:** Accepted
- **Date:** 2026-11-12 (Sprint B0)

## Context
The database will hold multi-tenant business data. Schema changes must be reviewable, repeatable across environments and safe on existing production data.

## Decision
- All DDL lives in versioned Flyway migrations (`db/migration/V<n>__*.sql`), applied automatically at application startup before JPA initialises.
- `spring.jpa.hibernate.ddl-auto=validate` in every profile. Hibernate checks that entities match the schema and fails startup otherwise.
- `flyway clean` is disabled, and applied migrations are immutable (fix forward).
- Migrations run inside the application, not from a container entrypoint script.

## Consequences
- The schema history is auditable (`flyway_schema_history`) and identical in development, tests (Testcontainers) and production.
- Developers write SQL migrations for every entity change. This is the price of explicit control.
- Running migrations at startup assumes a single migrating instance at a time. Flyway takes a database lock, which is sufficient for rolling deployments of one service. A dedicated migration job can replace it later without changing the migrations.

## Alternatives considered
- **`ddl-auto=update`/`create`:** non-deterministic, unsafe on production data. Rejected.
- **Liquibase:** equivalent capability. Flyway was chosen for plain SQL migrations and simpler review.
