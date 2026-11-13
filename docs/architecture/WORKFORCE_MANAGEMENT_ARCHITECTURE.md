# Workforce Management Architecture

Sprint B2. Employees, departments, positions and teams of an organization. This builds on the tenant and RBAC model of [MULTI_TENANCY_ARCHITECTURE.md](MULTI_TENANCY_ARCHITECTURE.md); the data model is in [WORKFORCE_DATA_MODEL.md](../database/WORKFORCE_DATA_MODEL.md).

## 1. Concepts kept apart

| Concept | Meaning | Owned by | Scope |
| --- | --- | --- | --- |
| **UserAccount** | The platform identity of a person, from `(issuer, subject)` | `identity` | global |
| **Membership** | A user's access to one organization (status + roles) | `access` | per organization |
| **Role** | Authorization (OWNER, ADMIN, MEMBER, custom): a set of permissions | `access` | per organization |
| **EmployeeProfile** | The *professional* record of a human employee | `employee` | per organization |
| **Position** | A job title ("Senior Backend Engineer"). **Never grants permissions** | `position` | per organization |
| **Department** | Organizational unit, hierarchical | `department` | per organization |
| **Team** | Collaborative group; members are employees | `team` | per organization |

Consequences, all covered by tests:

- A person can be in several organizations with a different profile, position and department in each.
- A member is not automatically an employee, and an employee does not need a login: `employee_profiles.membership_id` is optional.
- When linked, the membership must be in the **same organization** (composite foreign key + service check) and one membership has at most one profile.
- Suspending or removing a membership never touches the employee record: the profile just shows the membership status (`SUSPENDED`, `REMOVED`).
- Changing an employee's position, department or manager never changes anyone's permissions. RBAC is computed only from `membership_roles`.
- An employee record holds no credentials and no Keycloak data; the linked account's e-mail is **not** exposed by the employee API (only an opaque membership id and its status). No salary, birth date or address is collected.
- AI agents are **not** employees and have no fake account. Phase B3 will add agents to teams through a separate table (`team_agent_members`) with its own composite foreign key — no polymorphic `member_type/member_id`.

## 2. Modules and dependency order

```
workforce (shared kernel: statuses + ports)        ← depends on nothing
   ▲
department  ◀── position ◀── employee             team ──▶ department
   ▲                              │                 │
   └─────── implemented by ───────┘ EmployeeDirectory port (department, position, team use it)
```

| Module | Content | Depends on |
| --- | --- | --- |
| `workforce` | `EmploymentStatus`, `ArchiveStatus`, ports `EmployeeDirectory` and `EmployeeLifecycleParticipant` | — |
| `department` | entity, tenant-scoped repository, `DepartmentService`, `DepartmentDirectory`, controller | access, audit, workforce |
| `position` | idem, `PositionDirectory` | department, access, audit, workforce |
| `employee` | `EmployeeProfile`, `EmployeeService`, `EmployeeDirectoryAdapter` (implements the port), controller | department, position, access, audit, workforce |
| `team` | `Team`, `TeamMember`, `TeamService`, controller | department, access, audit, workforce |

**Why a shared kernel.** Departments need employees (head, employee counts), employees need departments (assignment): a direct dependency both ways would be a module cycle. `EmployeeDirectory` (declared in `workforce`, implemented by `employee`) breaks it: department, position and team ask *questions about employees* through the port, and `employee` depends on them one way. The reverse hook, `EmployeeLifecycleParticipant`, lets `department` (heads a department?) and `team` (leads a team? close memberships) take part in an employee's deactivation without `employee` knowing them (Spring injects all participants).

`ArchitectureTest` enforces the order (no cycle, the kernel depends on no module, `access`/`identity`/`organization`/`invitation` know nothing of the workforce, every `*Service` depends on `TenantAccessService`), plus the B1 rules: controllers never touch repositories or entities, repositories are package-private and `@TenantScoped`, no unscoped `findById`/`findAll`.

**Package layout.** Modules keep the flat layout established in B1 (entity, repository, service, controller in one package, repositories package-private) rather than `api/application/domain/persistence` sub-packages: package-private repositories are what makes "other modules go through services" a compiler-enforced rule. Controllers stay thin, DTOs are records nested in the controller, read models (`*View`) are separate from entities, mapping is explicit.

## 3. Request pipeline (unchanged from B1)

Authenticated user → internal `UserAccount` → ACTIVE membership of the organization in the URL (else **404**) → permission loaded from PostgreSQL (else **403**) → organization-scoped query. Every id found in a request body (department, position, manager, membership, team leader…) is looked up **inside the same organization**: another organization's id simply does not exist → **404**. Nothing is cached between requests.

Services check membership and permission **before** taking the organization lock, so a user without rights cannot make others wait.

## 4. Permissions

Ten permissions were added to the catalog (migration V3, `Permission` enum, kept identical by a test), following the existing `resource.action` naming:

| Permission | Allows | OWNER | ADMIN | MEMBER |
| --- | --- | :-: | :-: | :-: |
| `employee.read` | view employees | ✓ | ✓ | ✓ |
| `employee.create` | create employee profiles | ✓ | ✓ | |
| `employee.manage` | edit profiles, assignments (department, position, manager, membership link), ACTIVE ⇄ ON_LEAVE | ✓ | ✓ | |
| `employee.deactivate` | deactivate / reactivate (status to or from INACTIVE) | ✓ | ✓ | |
| `department.read` / `department.manage` | view / create, edit, move, archive, delete, set head | ✓ | ✓ | read |
| `position.read` / `position.manage` | view / manage the catalog | ✓ | ✓ | read |
| `team.read` / `team.manage` | view / manage teams and their members | ✓ | ✓ | read |

Existing organizations receive them through V3 (OWNER/ADMIN: all ten, MEMBER: the four reads); new organizations from `BuiltInRole`. Custom roles are never widened. The anti-escalation rules of B1 apply unchanged: a custom role can only contain permissions its author holds. The Angular UI hides actions according to `myPermissions`, but only as a convenience: every endpoint enforces its permission.

## 5. Business rules

**Departments** — hierarchical (max 10 levels, 500 per organization), code unique and upper-cased. Parent and head are resolved in the same organization. A department cannot be its own parent, and moving it under one of its descendants is refused (**cycle detection**, serialised by the organization lock, tested under concurrency). The head must be a *current* employee. Archiving requires no current employees and no active sub-departments; an archived department accepts neither employees, children nor a new head, and can only be restored under an active parent. Deletion is limited to departments nobody belongs to (foreign keys protect positions and teams that still reference it): otherwise archive.

**Positions** — catalog with unique code and title (case-insensitive), optional department association. Archiving requires that nobody currently holds it; archived positions cannot be assigned.

**Employees** — never deleted: lifecycle `ACTIVE ⇄ ON_LEAVE ⇄ INACTIVE`.
- Manager must be a current employee, not the employee, and must not create a **reporting cycle** (the chain is walked under the organization lock).
- *Inactive employee rules*: cannot be manager, department head, team leader or join a team. Deactivation is **refused** while the employee heads an active department, leads an active team, or manages current employees; on success the end date is set, team memberships are closed (history kept). Reactivation needs an active department and position (if set), clears the end date, and drops the manager if that manager left meanwhile.
- Employee number unique per organization; professional e-mail normalized to lower case.
- `expectedVersion` on updates turns optimistic locking into a 409 for stale edits (also on departments, positions and teams).

**Teams** — may mix departments. Members come from `team_members` with history (`joined_at`, `left_at`): a member who leaves keeps the row; rejoining adds a new one. A current employee is in a team at most once (partial unique index, tested under concurrency). The leader must be a current member; the leader cannot leave until replaced; a leader given at creation joins as first member. Archived teams are frozen (no new members or leader).

## 6. Onboarding of a new collaborator (B1 invitations reused)

Chosen approach: **a separate, authorized step after the invitation is accepted**, rather than extending the invitation with onboarding metadata.

1. A user with `invitation.create` invites the person (B1: hashed single-use token sent by e-mail, verified e-mail match, anti-escalation on the role).
2. The invitee signs in through Keycloak and accepts: a **membership** is created.
3. A user with `employee.create` creates the **employee profile** and links it to the membership (`membershipId` at creation, or later `PUT /employees/{id}/membership`), with department, position, manager.
4. Team membership is a further step (`POST /teams/{id}/members`).

Why: it needs no change to the invitation tables or token logic (no second token system, nothing new to leak), keeps invitation permissions separate from HR permissions (an inviter does not need `employee.create`, an HR assistant does not need `invitation.create`), works for people who never get a login, and supports linking a profile created *before* the person joined. The cost is one extra step in the UI: the employee form offers the organization's members (when the user has `member.read`), and the Organization page has an "Invite a collaborator" shortcut. Documented in [ADR-0013](../adr/ADR-0013-employee-onboarding-after-invitation.md).

## 7. API

All under `/api/v1/organizations/{organizationId}`; lists are paginated (`page`, `size` ≤ 100) with filters; errors are RFC 9457; contract in `contracts/openapi/core-api.v1.json`.

| Area | Endpoints |
| --- | --- |
| Employees | `GET/POST /employees` (q, status, departmentId, positionId, managerId) · `GET/PATCH /employees/{id}` · `PUT /employees/{id}/department`, `/position`, `/manager`, `/membership`, `/status` · `GET /employees/{id}/teams` |
| Departments | `GET/POST /departments` · `GET /departments/tree` · `GET/PATCH/DELETE /departments/{id}` · `PUT /{id}/parent`, `/{id}/head` · `POST /{id}/archive`, `/{id}/restore` |
| Positions | `GET/POST /positions` · `GET/PATCH/DELETE /positions/{id}` · `PUT /{id}/department` · `POST /{id}/archive`, `/{id}/restore` |
| Teams | `GET/POST /teams` · `GET/PATCH /teams/{id}` · `PUT /{id}/department`, `/{id}/leader` · `POST /{id}/archive`, `/{id}/restore` · `GET/POST /{id}/members` · `DELETE /{id}/members/{employeeId}` |

Nullable relations are changed with dedicated `PUT` sub-resources (a `null` body value clears the relation), which keeps `PATCH` for scalar fields and makes "omitted" and "clear" unambiguous in typed clients. Referenced ids unknown in the organization answer 404, business conflicts 409, missing permission 403, non-member 404.

Every change writes an audit event in the same transaction (`employee.*`, `department.*`, `position.*`, `team.*`), without personal data beyond names and ids.

## 8. Desktop

- Electron main process: the new operations are added **one by one** to the API allowlist (`auth-policy.ts`) with strict query filters (`q` bounded and free of control characters, ids as UUIDs, enums). Nothing else is reachable; the renderer still never sees a token.
- Angular: `WorkforceApi` (typed client, ids validated), `WorkforceStore` (organization-keyed collections) and `orgResource` — every result is tagged with the organization it was requested for and is **only exposed while that organization is current**, so a slow response arriving after an organization switch can never display another organization's data (tested). Tabs: Structure (tree + detail), Employees (search, filters, pagination, assignments, status, teams), Positions (catalog + holders), Teams (members, leader, department).
- In `api` mode the `/organization` route shows this real page; the Phase A demo page stays for the `mock` build and the unit tests. Agents, projects, communication and activity remain **demo data** and are labelled as such (the Organization page says AI agents arrive in B3).

## 9. Limits and decisions to revisit

- Pickers (manager, head, leader, membership) list the first 100 current employees / members; a type-ahead picker is a later improvement.
- Position history is not recorded (the employee shows its current position); deletion rules therefore consider current references. Team membership has history.
- Deactivation is refused rather than cascading: managers, heads and leaders must be reassigned first.
- Department codes and names are not translated; a department's employee count excludes inactive employees.
- B3 will extend teams with agent members and decide how agents appear in organization charts.
