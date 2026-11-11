# ADR-0002 — Spring Boot 4.1 on Java 21

- **Status:** Accepted
- **Date:** 2026-11-12 (Sprint B0)

## Context
Java 21 (LTS) is the target runtime and is installed on the development machine (Temurin 21.0.12). Versions on Maven Central at decision time:
- Spring Boot **4.1.2** (GA) and 4.2.0-RC2 (release candidate);
- the 4.0.x line (4.0.9) is still maintained.

## Decision
- **Spring Boot 4.1.2**, the latest GA release, as Maven parent. Its BOM manages every compatible version: Spring Framework 7.0.10, Spring Security 7.1.2, Hibernate 7.4.13, Flyway 12.4.0, Jackson 3.1.7, JUnit 6.0.3, Testcontainers 2.0.5, PostgreSQL JDBC 42.7.14.
- **springdoc-openapi 3.2.0**, the only version declared explicitly. It is not in the Boot BOM, and its parent POM is Spring Boot 4.1.2.
- **ArchUnit 1.5.1** (test only), declared explicitly.
- **Maven 3.9.11** pinned through the Maven Wrapper 3.3.4.
- **Java 21** source and target level.

## Consequences
- Spring Boot 4 specifics:
  - modular starters (`spring-boot-starter-webmvc`, `-flyway`, `-webmvc-test`…);
  - Jackson 3 (`tools.jackson.*` packages);
  - relocated auto-configuration packages (e.g. `org.springframework.boot.security.autoconfigure.*`);
  - Testcontainers 2 artifact names (`testcontainers-postgresql`).
  Every class name used was verified in the resolved jars.
- No release candidate in the foundation. Upgrading to 4.2 GA is a routine follow-up once it is released.
- Dependency analysis reports the starters as "unused declared" and their transitive libraries as "used undeclared". This is the expected pattern for starter-based projects, not a defect.

## Alternatives considered
- **Spring Boot 3.5 (Jackson 2, Framework 6):** mature, but would require a major migration soon after the foundation.
- **Spring Boot 4.2.0-RC2:** not production-ready.
