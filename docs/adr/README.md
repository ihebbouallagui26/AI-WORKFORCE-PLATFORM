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

Template: `# ADR-NNNN — Title` · Status · Date · Context · Decision · Consequences · Alternatives considered.
