# ADR-0009 — Tenant isolation by membership check and organization-scoped repositories

- **Status:** Accepted
- **Date:** 2026-11-12 (Sprint B1)

## Context
Multi-tenancy is the highest-risk area: a single missing filter leaks another organization's data. Options were PostgreSQL row-level security, a Hibernate tenant filter, or explicit application checks.

## Decision
- Shared schema, `organization_id` on every tenant row, **composite foreign keys** `(organization_id, id)` between tenant tables.
- Every organization-scoped service method starts with `TenantAccessService.require(organizationId, user, permission)`: ACTIVE membership (else 404) then permission from the database (else 403). Nothing is cached across requests.
- Repositories of tenant data are annotated `@TenantScoped`, package-private, and only declare methods taking the organization id; ArchUnit rejects unscoped inherited calls (`findById`, `findAll`, ...).
- RBAC: built-in OWNER/ADMIN/MEMBER plus custom roles; anti-escalation rules (OWNER only grantable with `ownership.manage`; never delegate unheld permissions); last-owner invariant under an organization row lock.

## Consequences
- Isolation is visible in code review and enforced by tests (two-organization integration tests, schema tests, ArchUnit).
- Every new tenant module must follow the same pattern (see `docs/architecture/MULTI_TENANCY_ARCHITECTURE.md`).
- Row-level security can be added later as defence in depth (requires a per-transaction tenant setting).

## Alternatives considered
- **PostgreSQL RLS only:** strong, but invisible in application code and awkward with connection pooling; kept as a future layer.
- **Hibernate filter / multi-tenant identifier:** easy to bypass with native queries, implicit when enabled.
- **Database per tenant:** operationally heavy for this stage.
