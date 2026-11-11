# ADR-0004 — PostgreSQL 18 with pgvector

- **Status:** Accepted
- **Date:** 2026-11-12 (Sprint B0)

## Context
The platform needs relational data with strong integrity (organizations, memberships, approvals) **and** vector similarity search for agent memory and document retrieval. No database existed in the repository.

## Decision
- **PostgreSQL 18** via the image `pgvector/pgvector:0.8.7-pg18` (pinned tag), in `infra/docker/compose.yaml` and in Testcontainers, so tests run on exactly the production engine.
- The `vector` extension is enabled by Flyway V1 (`CREATE EXTENSION IF NOT EXISTS vector`).
- Data volume `ai-workforce-postgres-data` mounted at `/var/lib/postgresql` (layout of PostgreSQL 18 images).
- Port bound to `127.0.0.1`, health check with `pg_isready`, restart policy `unless-stopped`.

## Consequences
- One database for relational and vector data: transactional consistency between a document and its embeddings, and tenant scoping with the same `organization_id` rules.
- Creating the extension requires the database owner. Managed databases must allow pgvector, or a DBA pre-creates it.
- If vector workloads outgrow PostgreSQL, a dedicated vector store can be introduced behind the same module API.

## Alternatives considered
- **Separate vector database (Qdrant, Weaviate…):** extra infrastructure and cross-store consistency problems, not justified yet.
- **PostgreSQL 17:** supported, but 18 is the current major release with the longest support window.
