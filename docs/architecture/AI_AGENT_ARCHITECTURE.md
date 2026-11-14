# AI agent architecture (Sprint B3)

Scope: **configuration and governance** of AI employees. Nothing in B3 executes an agent, calls a model for a task, runs a tool, a shell or an MCP server, or stores/retrieves memory. Execution and orchestration are Phase C.

## 1. Domain

An **agent** is an organization-owned configuration record. It is *not* a Keycloak user, has no membership, and is *not* an `EmployeeProfile`.

| Field | Notes |
| --- | --- |
| `id` (UUID), `organization_id` | tenant key; every FK is composite `(organization_id, id)` |
| `handle` | unique per organization, `^[a-z0-9][a-z0-9-]{1,39}$`, generated from the name when omitted |
| `name`, `title`, `description`, `avatar_tone`, `responsibilities[]` (≤ 20) | identity |
| `system_instructions` | returned only to callers holding `agent.manage` |
| `department_id` | optional, same organization, must be active |
| `status` | `DRAFT → ACTIVE ⇄ PAUSED → ARCHIVED` |
| `config_version` | incremented by every configuration change (identity, bindings, skills, tools, memory) |
| `version` | optimistic locking (`expectedVersion` on PATCH, `409` on conflict) |

### Lifecycle

| From | Action | To | Rule |
| --- | --- | --- | --- |
| `DRAFT`, `PAUSED` | activate | `ACTIVE` | requires a `PRIMARY` binding whose connection is `VALID` |
| `ACTIVE` | pause | `PAUSED` | — |
| any but `ARCHIVED` | archive | `ARCHIVED` | terminal; closes team memberships; refused edits afterwards |

`ACTIVE` means *eligible for future execution*. It does not mean the agent is running.

## 2. Modules and boundaries

```
common ─ access ─ audit ─ identity ─ workforce (ports) ─ department / position / employee / team
                                                  provider ◄── agent (implements ConnectionUsage, AgentDirectory)
```

* `provider` owns connections, the `SecretStore` and the provider adapters; it knows nothing of agents. It asks the agent module through the `ConnectionUsage` port whether an `ACTIVE` agent still uses a connection as primary.
* `agent` owns agents, bindings, skills, tool grants, memory config and team membership. It reads connections through `ProviderDirectory` and never touches `SecretStore`.
* `department` and `team` (B2) learn about agents only through the `AgentDirectory` port in `workforce` (counts, archive guards).
* ArchUnit rules (`ArchitectureTest`) enforce all of the above, including "only the `provider` package may call `SecretStore.reveal`".

## 3. Authorization

New permissions (`resource.action`), added by migration `V4` and backfilled to built-in roles only:

| Permission | OWNER | ADMIN | MEMBER |
| --- | :-: | :-: | :-: |
| `agent.read`, `skill.read` | ✓ | ✓ | ✓ |
| `agent.create`, `agent.manage`, `agent.activate`, `agent.archive`, `agent.tool.manage` | ✓ | ✓ | — |
| `provider.read`, `provider.connect`, `provider.manage`, `provider.test`, `skill.manage` | ✓ | ✓ | — |

Custom roles are never widened by the migration. Rules enforced in the services:

* membership and permission are checked **before** the organization lock is taken; non-members get `404`, missing permission `403`;
* binding a provider needs `agent.manage` **and** `provider.read`; adding an agent to a team needs `team.manage` **and** `agent.read`; tool grants need `agent.tool.manage`;
* system instructions are omitted from responses without `agent.manage`;
* the frontend hides what the user cannot do, but the API is the only authority.

## 4. Configuration

* **Provider bindings** – `(agent, connection, model id, PRIMARY|FALLBACK, priority, options)`. One primary per agent (partial unique index); fallbacks have unique priorities; swapping the primary is done in three flushed steps inside one transaction. Model ids are free-form (validated by pattern) and normally chosen from the catalog discovered through the connection.
* **Skills** – organization catalog (name, category, description, active) assigned to agents as a set (`PUT /agents/{id}/skills`). Deleting a skill removes its assignments.
* **Tools** – a global read-only registry (`tool_definitions`, seeded with six entries including MCP metadata `mcp_server`/`mcp_tool`). An agent has **no** tool by default. A grant carries a level (`READ ≤ WRITE ≤ EXECUTE`, never above the tool's maximum) and an approval flag; `HIGH` risk tools always require approval. `project_id` must be `NULL` (database check) until projects exist in the backend. Granting a tool grants nothing executable in B3.
* **Memory** – `agent_memory_configs`: disabled by default, scope `PROJECT|ORGANIZATION`, access `AGENT_ONLY|TEAM_MEMBERS|ORGANIZATION_MEMBERS`, retention days. Configuration only. A future pgvector store will be keyed by `(organization_id, agent_id, scope)` and must filter on `organization_id` first; no embedding, retrieval or autonomous memory exists today.

## 5. Teams

`team_agent_members(organization_id, team_id, agent_id, joined_at, left_at)` is a dedicated table (never a polymorphic member). A partial unique index forbids two *current* memberships of the same agent in the same team; an agent can belong to several teams. Archived agents and archived teams cannot receive new assignments. Team and department payloads expose `memberCount`/`employeeCount` (humans) and `agentCount` (agents) separately.

## 6. History

Configuration history is the audit log filtered on `target_type = 'agent'` (`GET /agents/{id}/history`, paginated). Events record who and what changed; they never contain system instructions, credentials or provider payloads.

## 7. Frontend

`/agents` shows the real section in `api` mode (AI agents page: Agents, Providers, Skills tabs; agent detail with lifecycle, bindings, skills, tool permissions, memory, teams, history). The Phase A demo remains for the `mock` build and is labelled as demo data. `AgentsStore` keys every collection by the current organization through `orgResource`, so a response arriving after an organization switch is never shown.

## 8. Not in B3

Execution, scheduling, LLM calls for tasks, tool/MCP/shell execution, embeddings and retrieval, per-project tool scope, credential key rotation, agent-to-agent messaging, billing/quotas.
