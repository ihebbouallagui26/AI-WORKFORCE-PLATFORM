# Architecture Decision Records

Short records of significant decisions: context, decision, consequences. An accepted ADR is not edited. A change of direction is a new ADR that supersedes it.

| ADR | Title | Status |
| --- | --- | --- |
| [0001](ADR-0001-modular-monolith.md) | Modular monolith for the Core API | Accepted |
| [0002](ADR-0002-spring-boot-4-java-21.md) | Spring Boot 4.1 on Java 21 | Accepted |
| [0003](ADR-0003-flyway-schema-ownership.md) | Flyway owns the schema, Hibernate validates | Accepted |
| [0004](ADR-0004-postgresql-pgvector.md) | PostgreSQL 18 with pgvector | Accepted |
| [0005](ADR-0005-problem-details.md) | RFC 9457 problem details for every error | Accepted |
| [0006](ADR-0006-configuration-and-secrets.md) | Production-safe base configuration, secrets from the environment | Accepted |
| [0007](ADR-0007-stateless-deny-by-default-security.md) | Stateless, deny-by-default API security before authentication | Accepted |
| [0008](ADR-0008-keycloak-identity-provider.md) | Keycloak as OpenID Connect provider, API as OAuth2 resource server | Accepted |
| [0009](ADR-0009-tenant-isolation-by-membership.md) | Tenant isolation by membership check and organization-scoped repositories | Accepted |
| [0010](ADR-0010-desktop-oidc-in-main-process.md) | Desktop OIDC in the Electron main process, API calls proxied over IPC | Accepted |
| [0011](ADR-0011-database-privilege-separation.md) | Separate database roles for migrations, runtime and Keycloak | Accepted |
| [0012](ADR-0012-workforce-domain-model-and-module-ports.md) | Workforce domain model and module boundaries (shared-kernel ports) | Accepted |
| [0013](ADR-0013-employee-onboarding-after-invitation.md) | Employee onboarding as a separate step after invitation acceptance | Accepted |
| [0014](ADR-0014-agent-domain-and-module-ports.md) | AI agent domain, separate from users and employees, wired through ports | Accepted |
| [0015](ADR-0015-secret-store-and-provider-adapters.md) | SecretStore abstraction and fixed-endpoint provider adapters | Accepted |
| [0016](ADR-0016-orchestrator-service-boundary-and-identity.md) | Python orchestrator behind a service boundary, with its own identity and runtime database | Accepted |
| [0017](ADR-0017-supervised-execution-and-credential-issuance.md) | Supervised executions: asynchronous hand-over, Core API state machine, single-use credential issuance | Accepted |

Template: `# ADR-NNNN — Title` · Status · Date · Context · Decision · Consequences · Alternatives considered.
