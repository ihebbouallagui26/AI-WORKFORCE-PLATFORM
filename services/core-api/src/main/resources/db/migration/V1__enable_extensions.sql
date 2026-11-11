-- V1 — Database foundation (Sprint B0).
--
-- No business tables yet: each domain adds its own migrations when it is implemented
-- (see docs/database/DATABASE_CONVENTIONS.md).
--
-- pgvector stores embeddings for AI memory and document search. The extension ships with
-- the pgvector/pgvector image used in infra/docker and in Testcontainers. Creating it requires
-- the database owner (development) or a DBA (managed production databases), see the README.
CREATE EXTENSION IF NOT EXISTS vector;
