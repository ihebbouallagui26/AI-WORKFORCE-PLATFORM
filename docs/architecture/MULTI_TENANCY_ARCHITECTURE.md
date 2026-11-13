# Multi-Tenancy Architecture

Sprint B1. An **organization** is the tenant. Every organization-owned row carries `organization_id`; access is granted per organization through an ACTIVE **membership** and its **roles**.

## 1. Request pipeline

Every organization-scoped request goes through the same steps, in this order:

1. **Authenticate** — bearer access token validated (signature, issuer, audience, time, type).
2. **Resolve the user** — from `(iss, sub)`, never from the e-mail.
3. **Verify the membership** — `TenantAccessService.requireMember(organizationId, user)`: an ACTIVE membership of this user in the organization named in the URL must exist. Otherwise **404** (`organization not found`): the existence of other organizations is not revealed, and suspended/removed members get the same answer.
4. **Verify the permission** — effective permissions are loaded from PostgreSQL for this request (union of the membership's role permissions). Missing permission: **403**.
5. **Org-scoped data access** — repositories only expose methods that take the organization id.

The organization id in the URL only *selects* an organization; it is never trusted on its own. Nothing is cached between requests: a suspension or role change applies to the very next request.

## 2. Modules

| Module | Responsibility | Depends on |
| --- | --- | --- |
| `identity` | Users, external identities, `/me` | — |
| `auth` | JWT decoder and validators | — |
| `audit` | Append-only audit events (same transaction as the change) | — |
| `access` | Memberships, roles, permissions, tenant check, anti-escalation | identity, audit |
| `organization` | Organization lifecycle, audit listing | access, identity, audit |
| `invitation` | Invitations and acceptance | access, organization, identity, audit |

Repositories are package-private (other modules call services). ArchUnit enforces: no module cycles, controllers never use repositories or entities, repositories are not public, every method declared in a `@TenantScoped` repository contains `Organization` in its name (it takes the organization id), and nobody calls the unscoped inherited methods (`findById`, `findAll`, `count`, `deleteById`, ...) on a tenant repository.

## 3. Schema-level isolation

- Composite unique keys `(organization_id, id)` on roles and memberships, and **composite foreign keys** from `membership_roles`, `role_permissions` and `organization_invitations`: a row can never reference a role or membership of another organization, even with a forged `organization_id` (tested in `DatabaseSecurityIntegrationTest`).
- `UNIQUE (organization_id, user_id)` on memberships: one membership per user and organization; a user may belong to many organizations.

## 4. RBAC

Permission catalog (table `permissions`, seeded by V2, mirrored by the `Permission` enum; a test keeps them identical):

`organization.read`, `organization.update`, `member.read`, `member.manage`, `member.roles.assign`, `role.read`, `role.manage`, `invitation.read`, `invitation.create`, `invitation.revoke`, `audit.read`, `ownership.manage`.

| Built-in role | Permissions |
| --- | --- |
| OWNER | all |
| ADMIN | all except `ownership.manage` |
| MEMBER | `organization.read`, `member.read`, `role.read` |

Custom roles are organization-scoped sets of permissions (max 50 per organization). Built-in roles cannot be modified or deleted; a custom role still assigned cannot be deleted.

### Anti-escalation rules (`RoleGrantPolicy`)

- Granting or revoking the **OWNER** role requires `ownership.manage` — an ADMIN can never create, demote, suspend or remove an owner.
- Granting or revoking any other role requires holding **all** of its permissions — nobody delegates what they do not have.
- `ownership.manage` can never be put in a custom role; a custom role may only contain permissions its creator holds.
- Acting on a member (suspend, reactivate, remove, change roles) requires being able to grant every role that member holds.
- Members cannot suspend themselves; they can always leave (except the last owner).

### Last owner invariant

An organization always keeps at least one ACTIVE member with the OWNER role. Every membership change first takes a row lock on the organization (`SELECT ... FOR UPDATE`), so two concurrent requests cannot each remove "the other" owner. Removing, suspending or downgrading the last owner answers **409**.

## 5. Organization lifecycle

`POST /api/v1/organizations` creates, **in one transaction**, the organization, its three built-in roles, the creator's ACTIVE membership with OWNER, and the audit event. Other endpoints: list my organizations (ACTIVE memberships only), get (with `myRoles`, `myPermissions`, `myMembershipId`), update, audit events.

## 6. Invitations

| Property | Implementation |
| --- | --- |
| Entropy | 256 random bits (`SecureRandom`), base64url, 43 characters |
| Storage | SHA-256 only (`token_hash`, unique); the token is never stored, logged, audited or returned by the API |
| Delivery | E-mail via SMTP (Mailpit locally). If sending fails the invitation is rolled back and the API answers 503 |
| Expiry | `app.invitations.ttl` (7 days, max 30); an expired pending invitation no longer blocks a new one |
| Single use | Row locked on acceptance (`PESSIMISTIC_WRITE`), status `ACCEPTED`; reuse → 409 |
| Revocation | `invitation.revoke`; revoked → 409 on acceptance |
| E-mail binding | The acceptor must hold the token **and** have a **verified** e-mail equal to the invited address (403 otherwise) |
| Role checks | At creation, the inviter must be able to grant the role (no OWNER from an ADMIN). At acceptance, the inviter must **still** be an active member allowed to invite and to grant it (otherwise 409) |
| Duplicates | One pending invitation per e-mail and organization (partial unique index); no invitation for a current member |

## 7. Audit

`audit_events` records organization creation/update, member suspension/reactivation/removal/leave, role changes, custom role CRUD, invitation creation/revocation/acceptance — in the same transaction as the change. The runtime DB role cannot update or delete audit rows. Details never contain secrets.

## 8. Tests

`MultiTenancyIntegrationTest` (two organizations; A↔B denial on every endpoint, cross-organization ids through A's paths, permissions per role, ADMIN cannot grant OWNER nor act on owners, no delegation of unheld permissions, built-in immutability, last-owner protection, suspended/removed members lose access immediately, re-invitation), `InvitationIntegrationTest` (real SMTP, hashed token, single use, verified e-mail binding, expiry, revocation, inviter revalidation, cross-organization revocation), `DatabaseSecurityIntegrationTest`, `ArchitectureTest`.
