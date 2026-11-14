# ADR-0013 — Employee onboarding as a separate step after invitation acceptance

- **Status:** Accepted
- **Date:** 2026-11-14 (Sprint B2)

## Context
B1 delivered organization invitations (256-bit single-use token stored hashed, e-mail delivery, verified e-mail match, anti-escalation on the offered role). B2 must let a manager onboard a new human collaborator: invite, accept, then create or link the employee profile with department, position, manager and team. Two designs were possible: carry onboarding metadata (department, position, manager) inside the invitation, or treat the profile as a separate authorized step.

## Decision
Keep the **B1 invitation untouched** and make profile creation a **separate step**:
1. `invitation.create` invites; the invitee accepts (B1 flow) and becomes a member.
2. `employee.create` creates the profile and links it (`membershipId`), or `employee.manage` links an existing profile later (`PUT /employees/{id}/membership`); the membership must belong to the same organization, must not be removed and must not already have a profile.
3. Teams are a further step (`team.manage`).

No second token system exists, and no new column is added to `organization_invitations`.

## Consequences
- Invitation and HR permissions stay independent (least privilege); the invitation model and its security review are unchanged.
- It also covers people who never get a login, and profiles created before the person joins (linked afterwards).
- Onboarding is two or three UI actions instead of one: the employee form offers the members of the organization, and the Organization page links to the invitations screen.
- If a one-step "invite with onboarding" is wanted later, it can be added as an optional, backward-compatible `onboarding` payload applied at acceptance, re-validated against the inviter's rights at that time (as the role already is).

## Alternatives considered
- **Metadata in the invitation (department, position, manager):** one step for the user, but the invitation would carry HR data, acceptance would have to re-validate every reference (archived department, inactive manager) and create an employee on behalf of the inviter, mixing two permission sets in one operation.
- **Auto-create a profile on acceptance:** rejected — a member is not automatically an employee.
