# ADR-0001 — Modular monolith for the Core API

- **Status:** Accepted
- **Date:** 2026-11-12 (Sprint B0)

## Context
The platform has many business domains (organizations, people, agents, projects, tasks, approvals, conversations, reports, audit). They are strongly related: an approval references an agent, a project and an organization in the same transaction. The team is small, and the product is at its first backend milestone.

## Decision
Build the Core API as **one Spring Boot application** organized in top-level business packages under `com.aiworkforce.core`, each with `api / application / domain / persistence` layers.

Modules interact through application services only. The rules (no cycles, `common` independent, controllers without repositories or entities) are enforced by ArchUnit tests.

The AI orchestrator stays a separate service: it uses a different runtime (Python/LangGraph) and scales differently.

## Consequences
- One deployment, one database, and local ACID transactions across domains. Simple local development and debugging.
- Boundaries are explicit and tested, so a module can be extracted later if load or team ownership justify it.
- All modules share one release cycle and one JVM: a heavy module affects the others. To be monitored.

## Alternatives considered
- **Microservice per domain:** distributed transactions, network failures, and multiplied operational cost for no current benefit.
- **Unstructured monolith (layer-first packages):** fast at first, but boundaries erode and later extraction becomes impractical.
