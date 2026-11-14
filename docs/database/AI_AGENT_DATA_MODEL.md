# AI agent data model (migration V4)

`V4__ai_agent_management.sql` is additive: V1–V3 are untouched. It was tested from an empty database and from a database already at V3 (B2) with data. All tables carry `organization_id` and reference their parents through **composite foreign keys** `(organization_id, id)`, so a row can never point at another organization's parent. Updatable tables have a `version` column for optimistic locking.

| Table | Purpose | Notable constraints |
| --- | --- | --- |
| `secret_entries` | AES-256-GCM ciphertext, nonce, key version | no plaintext column; unique `(organization_id, id)` |
| `provider_connections` | organization connections | `credential_ref` nullable; `(status = 'REVOKED') = (credential_ref IS NULL)`; display name length ≥ 2 |
| `agents` | agent identity and lifecycle | unique `(organization_id, handle)`; handle regex; `responsibilities text[]` ≤ 20; `config_version ≥ 1`; optional composite FK to `departments` |
| `agent_provider_bindings` | agent ↔ connection ↔ model | one `PRIMARY` per agent (partial unique); unique priority per agent; role/priority check; unique `(agent, connection, model)`; model id pattern |
| `skills` | organization skill catalog | unique name per organization; category |
| `agent_skills` | agent ↔ skill | primary key `(agent_id, skill_id)`; composite FKs |
| `tool_definitions` | global tool registry with MCP metadata | seeded: `project.files.read`, `code.analyze`, `docs.search`, `reports.create`, `tests.run`, `git.access` (HIGH); read-only for the runtime role |
| `agent_tool_grants` | least-privilege grants | level ≤ tool maximum (service); `project_id` **must be NULL** (check) until projects exist |
| `agent_memory_configs` | memory settings, one row per agent | disabled by default; scope/access enums; retention ≥ 1 |
| `team_agent_members` | agents in teams | composite FKs `(organization_id, team_id)` and `(organization_id, agent_id)`; partial unique index on current membership (`left_at IS NULL`) |

Also in V4: twelve permissions (`agent.read|create|manage|activate|archive`, `agent.tool.manage`, `provider.read|connect|manage|test`, `skill.read|manage`) and the backfill of built-in roles (OWNER/ADMIN all twelve, MEMBER `agent.read` and `skill.read`). `afterMigrate.sql` re-applies the runtime role's privileges and revokes `INSERT/UPDATE/DELETE` on `tool_definitions` (guarded, so it is safe on any version).

## Future pgvector memory

The `vector` extension is already installed (ADR-0004). A later migration will add a memory table keyed `(organization_id, agent_id, scope, project_id)` with a composite FK to `agents`; every similarity query must filter on `organization_id` before ranking, and embeddings must never be shared across organizations. B3 stores only the configuration (`agent_memory_configs`).

## Audit

Sensitive changes are written to `audit_events` with `target_type` `agent` (lifecycle, configuration, `agent.team.joined|left`), `provider_connection` or `skill`. Payloads contain identifiers and names only — no credentials, no system instructions.
