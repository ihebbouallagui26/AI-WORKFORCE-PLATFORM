# ADR-0014 — AI agent domain, separate from users and employees, wired through ports

- **Status:** Accepted
- **Date:** 2026-11-14 (Sprint B3)

## Context
B3 introduces AI employees. They appear in teams and departments next to people, but they have no identity in Keycloak, no membership and no employment record. The earlier modules (department, team) must count and guard them without depending on a module that itself depends on them.

## Decision
* An **agent is its own aggregate** (`agents` table, `agent` module). It is never a Keycloak user, a membership or an `EmployeeProfile`.
* Team membership of agents is a **separate table** `team_agent_members` with composite foreign keys `(organization_id, team_id)` and `(organization_id, agent_id)`. No polymorphic "member" table.
* `department` and `team` learn about agents through the `AgentDirectory` port defined in `workforce`; `provider` learns whether a connection is in use through `ConnectionUsage`; the `agent` module implements both. Module direction is enforced by ArchUnit.
* `ACTIVE` means *eligible for future execution*, not running. Activation requires a primary binding on a `VALID` connection.
* Archiving an agent is terminal and closes its team memberships; archived agents are never assigned to new teams.
* Tools are least-privilege: a global read-only registry and per-agent grants, empty by default, capped by the tool's maximum level, `HIGH` risk always with approval. Per-project scope is reserved (`project_id` must be `NULL`).
* New permissions follow `resource.action`; the migration only touches built-in roles.

## Consequences
* Humans and agents are counted separately everywhere; no code path can mistake one for the other.
* Adding execution in Phase C needs no change of the domain: the runtime reads configuration through the same services.
* Per-project tool scope and memory need a later migration once projects exist.

## Alternatives considered
* One polymorphic `team_members(member_type, member_id)` table: rejected, no real foreign keys, weaker tenant guarantees.
* Representing agents as service users in Keycloak: rejected, mixes identity with configuration and invites privilege escalation.
