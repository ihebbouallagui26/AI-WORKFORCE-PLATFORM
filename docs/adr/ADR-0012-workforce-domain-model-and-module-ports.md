# ADR-0012 — Workforce domain model and module boundaries (shared-kernel ports)

- **Status:** Accepted
- **Date:** 2026-11-14 (Sprint B2)

## Context
Sprint B2 adds employees, departments, positions and teams. Several concepts look alike and are easily merged by mistake: the platform account, the organization membership, the authorization role and the job title. Merging them creates security bugs (a job title that grants rights, a suspended login that deletes HR data) and forces every future feature to untangle them. The four new areas also reference each other (department head → employee, employee → department), which in a modular monolith turns into package cycles.

## Decision
- **Separate aggregates.** `UserAccount` (identity), `Membership` + `Role` (access), `EmployeeProfile` (professional record), `Position` (job title), `Department` and `Team`. An `EmployeeProfile` has an **optional** `membership_id`; a position or department never influences permissions; suspending a membership does not change the profile. AI agents are not employees and have no fake account.
- **Tenant integrity in the schema.** Every relation is a composite foreign key `(organization_id, id)`; services additionally resolve every id inside the request's organization (unknown there → 404).
- **Acyclic modules through ports.** A small shared-kernel package `workforce` holds the statuses and two ports: `EmployeeDirectory` (questions about employees, implemented by `employee`) and `EmployeeLifecycleParticipant` (hook called on deactivation, implemented by `department` and `team`). Dependency order: `department` → `position` → `employee`, `team` → `department`; the kernel and the access modules depend on nothing above them. `ArchitectureTest` enforces it.
- **Lifecycle instead of deletion.** Employees are never deleted (status + end date); departments, positions and teams are archived; deletion is limited to rows nobody references.
- **Team members in their own table**, with history, referencing employees only. Agents will get a sibling table in B3 (no polymorphic member reference).
- **Structural invariants under the organization lock** (hierarchy and reporting cycles, current-member rules), checked after membership and permission.

## Consequences
- Each concept can evolve independently; HR data survives access changes; authorization stays purely RBAC.
- Some rules are refusals rather than cascades (an employee who heads a department or leads a team cannot be deactivated before reassignment): explicit, auditable, slightly more clicks.
- The port indirection adds two small interfaces, and the package layout is "flat per module" (kept from B1) rather than layered sub-packages, so repositories stay package-private.
- Pickers load a bounded list (100) until a type-ahead is built.

## Alternatives considered
- **One `Person` entity with login, job title and role:** simplest, rejected — couples HR data to access and to Keycloak.
- **Position as a kind of role:** rejected — a job title must never grant permissions.
- **Direct dependencies between the modules (or a single `workforce` module):** rejected — package cycles, or one large module hiding the boundaries; ports keep the four areas separate yet acyclic.
- **Polymorphic team members now:** rejected — no foreign key can protect `(member_type, member_id)`.
- **Cascading deactivation (auto-clearing heads and leaders):** rejected — silent data changes with security-relevant roles.
