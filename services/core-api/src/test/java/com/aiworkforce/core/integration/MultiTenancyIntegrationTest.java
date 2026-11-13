package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tenant isolation and RBAC across two organizations: every request is authenticated, resolved
 * to a user, checked for an ACTIVE membership and a permission, then scoped to the organization.
 */
class MultiTenancyIntegrationTest extends OrganizationFlows {

    @Test
    void creatingAnOrganizationMakesTheCreatorItsOwnerAtomically() throws Exception {
        TestUser alice = newUser("alice");
        UUID org = createOrganization(alice, "Acme");

        getAs(alice, "/api/v1/organizations/{id}", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Acme"))
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.myRoles[0].code").value("OWNER"))
                .andExpect(jsonPath("$.myPermissions", hasItem("ownership.manage")));
        getAs(alice, "/api/v1/organizations/{id}/roles", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.builtIn == true)].code").value(
                        containsInAnyOrder("OWNER", "ADMIN", "MEMBER")));
        getAs(alice, "/api/v1/organizations/{id}/members", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value(alice.email()))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"));
        getAs(alice, "/api/v1/organizations/{id}/audit-events", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].action", hasItem("organization.created")));
    }

    @Test
    void aUserCanBelongToSeveralOrganizationsAndOnlyListsItsOwn() throws Exception {
        TestUser alice = newUser("alice");
        TestUser bob = newUser("bob");
        UUID a1 = createOrganization(alice, "Alpha");
        UUID a2 = createOrganization(alice, "Beta");
        UUID b1 = createOrganization(bob, "Bobco");

        JsonNode list = body(getAs(alice, "/api/v1/organizations").andExpect(status().isOk()));
        List<String> ids = list.get("content").valueStream().map(o -> o.get("id").asString()).toList();
        assertThat(ids).containsExactlyInAnyOrder(a1.toString(), a2.toString()).doesNotContain(b1.toString());
    }

    @Test
    void aMemberOfOrganizationACannotReachOrganizationB() throws Exception {
        TestUser alice = newUser("alice");
        TestUser bob = newUser("bob");
        UUID orgA = createOrganization(alice, "Org A");
        UUID orgB = createOrganization(bob, "Org B");
        UUID bobMembership = membershipId(bob, orgB);
        UUID roleOfB = roleId(bob, orgB, "MEMBER");

        // Selecting another organization in the URL is not enough: 404, its existence is not revealed.
        getAs(alice, "/api/v1/organizations/{id}", orgB).andExpect(status().isNotFound());
        patchAs(alice, "/api/v1/organizations/{id}", Map.of("name", "Pwned"), orgB).andExpect(status().isNotFound());
        getAs(alice, "/api/v1/organizations/{id}/members", orgB).andExpect(status().isNotFound());
        getAs(alice, "/api/v1/organizations/{id}/roles", orgB).andExpect(status().isNotFound());
        getAs(alice, "/api/v1/organizations/{id}/invitations", orgB).andExpect(status().isNotFound());
        getAs(alice, "/api/v1/organizations/{id}/audit-events", orgB).andExpect(status().isNotFound());
        postAs(alice, "/api/v1/organizations/{id}/invitations",
                Map.of("email", "x@example.test", "roleId", roleOfB), orgB).andExpect(status().isNotFound());
        // Unknown organizations look exactly the same.
        getAs(alice, "/api/v1/organizations/{id}", UUID.randomUUID()).andExpect(status().isNotFound());

        // Resources of B addressed through A (where alice is OWNER) are not found either.
        getAs(alice, "/api/v1/organizations/{a}/members/{m}", orgA, bobMembership).andExpect(status().isNotFound());
        postAs(alice, "/api/v1/organizations/{a}/members/{m}/suspend", "{}", orgA, bobMembership)
                .andExpect(status().isNotFound());
        getAs(alice, "/api/v1/organizations/{a}/roles/{r}", orgA, roleOfB).andExpect(status().isNotFound());
        UUID aliceMembership = membershipId(alice, orgA);
        putAs(alice, "/api/v1/organizations/{a}/members/{m}/roles", Map.of("roleIds", List.of(roleOfB)), orgA,
                aliceMembership).andExpect(status().isNotFound());
        postAs(alice, "/api/v1/organizations/{id}/invitations", Map.of("email", "y@example.test", "roleId", roleOfB),
                orgA).andExpect(status().isNotFound());

        // B is untouched.
        getAs(bob, "/api/v1/organizations/{id}", orgB).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Org B"));
        getAs(bob, "/api/v1/organizations/{id}/members", orgB).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void membersOnlyGetWhatTheirRolesAllow() throws Exception {
        TestUser owner = newUser("owner");
        TestUser member = newUser("member");
        UUID org = createOrganization(owner, "Perms");
        join(owner, org, member, "MEMBER");

        getAs(member, "/api/v1/organizations/{id}", org).andExpect(status().isOk())
                .andExpect(jsonPath("$.myPermissions", not(hasItem("member.manage"))));
        getAs(member, "/api/v1/organizations/{id}/members", org).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        patchAs(member, "/api/v1/organizations/{id}", Map.of("name", "Mine"), org).andExpect(status().isForbidden());
        getAs(member, "/api/v1/organizations/{id}/invitations", org).andExpect(status().isForbidden());
        getAs(member, "/api/v1/organizations/{id}/audit-events", org).andExpect(status().isForbidden());
        postAs(member, "/api/v1/organizations/{id}/invitations",
                Map.of("email", "z@example.test", "roleId", roleId(owner, org, "MEMBER")), org)
                .andExpect(status().isForbidden());
        postAs(member, "/api/v1/organizations/{id}/roles", Map.of("name", "Mine", "permissions", List.of()), org)
                .andExpect(status().isForbidden());
        postAs(member, "/api/v1/organizations/{a}/members/{m}/suspend", "{}", org, membershipId(owner, org))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminCannotEscalateToOwnerNorActOnOwners() throws Exception {
        TestUser owner = newUser("owner");
        TestUser admin = newUser("admin");
        TestUser member = newUser("member");
        UUID org = createOrganization(owner, "Escalation");
        UUID adminMembership = join(owner, org, admin, "ADMIN");
        UUID memberMembership = join(owner, org, member, "MEMBER");
        UUID ownerRole = roleId(owner, org, "OWNER");
        UUID adminRole = roleId(owner, org, "ADMIN");
        UUID ownerMembership = membershipId(owner, org);

        // ADMIN cannot grant OWNER, to others or to itself, nor invite owners.
        putAs(admin, "/api/v1/organizations/{o}/members/{m}/roles", Map.of("roleIds", List.of(ownerRole)), org,
                memberMembership).andExpect(status().isForbidden());
        putAs(admin, "/api/v1/organizations/{o}/members/{m}/roles", Map.of("roleIds", List.of(ownerRole, adminRole)),
                org, adminMembership).andExpect(status().isForbidden());
        postAs(admin, "/api/v1/organizations/{id}/invitations", Map.of("email", "o@example.test", "roleId", ownerRole),
                org).andExpect(status().isForbidden());
        // ADMIN cannot suspend, remove or re-role an OWNER.
        postAs(admin, "/api/v1/organizations/{o}/members/{m}/suspend", "{}", org, ownerMembership)
                .andExpect(status().isForbidden());
        deleteAs(admin, "/api/v1/organizations/{o}/members/{m}", org, ownerMembership).andExpect(status().isForbidden());
        putAs(admin, "/api/v1/organizations/{o}/members/{m}/roles", Map.of("roleIds", List.of(adminRole)), org,
                ownerMembership).andExpect(status().isForbidden());
        // No custom role can carry ownership.manage.
        postAs(admin, "/api/v1/organizations/{id}/roles",
                Map.of("name", "Shadow owner", "permissions", List.of("ownership.manage")), org)
                .andExpect(status().isForbidden());
        postAs(owner, "/api/v1/organizations/{id}/roles",
                Map.of("name", "Shadow owner", "permissions", List.of("ownership.manage")), org)
                .andExpect(status().isForbidden());

        // What ADMIN may do: manage MEMBERs and grant ADMIN.
        putAs(admin, "/api/v1/organizations/{o}/members/{m}/roles", Map.of("roleIds", List.of(adminRole)), org,
                memberMembership).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].code").value("ADMIN"));
        // OWNER can grant OWNER.
        putAs(owner, "/api/v1/organizations/{o}/members/{m}/roles", Map.of("roleIds", List.of(ownerRole)), org,
                adminMembership).andExpect(status().isOk());
        getAs(owner, "/api/v1/organizations/{id}/audit-events", org)
                .andExpect(jsonPath("$.content[*].action", hasItem("member.roles.changed")));
    }

    @Test
    void customRolesCannotDelegatePermissionsTheCreatorDoesNotHold() throws Exception {
        TestUser owner = newUser("owner");
        TestUser manager = newUser("manager");
        TestUser member = newUser("member");
        UUID org = createOrganization(owner, "Delegation");
        UUID managerMembership = join(owner, org, manager, "MEMBER");
        UUID memberMembership = join(owner, org, member, "MEMBER");

        JsonNode limited = body(postAs(owner, "/api/v1/organizations/{id}/roles", Map.of("name", "Role manager",
                "permissions", List.of("role.read", "role.manage", "member.read", "member.roles.assign")), org)
                .andExpect(status().isCreated()));
        UUID limitedRole = UUID.fromString(limited.get("id").asString());
        UUID memberRole = roleId(owner, org, "MEMBER");
        putAs(owner, "/api/v1/organizations/{o}/members/{m}/roles", Map.of("roleIds", List.of(limitedRole, memberRole)),
                org, managerMembership).andExpect(status().isOk());

        // Cannot create a role with permissions it does not hold, nor grant ADMIN.
        postAs(manager, "/api/v1/organizations/{id}/roles",
                Map.of("name", "Inviter", "permissions", List.of("invitation.create")), org)
                .andExpect(status().isForbidden());
        putAs(manager, "/api/v1/organizations/{o}/members/{m}/roles",
                Map.of("roleIds", List.of(roleId(owner, org, "ADMIN"))), org, memberMembership)
                .andExpect(status().isForbidden());
        // Can create and grant a role within its own permissions.
        JsonNode reader = body(postAs(manager, "/api/v1/organizations/{id}/roles",
                Map.of("name", "Reader", "permissions", List.of("member.read")), org).andExpect(status().isCreated()));
        putAs(manager, "/api/v1/organizations/{o}/members/{m}/roles",
                Map.of("roleIds", List.of(reader.get("id").asString(), memberRole.toString())), org, memberMembership)
                .andExpect(status().isOk());
        // Unknown permission codes are rejected.
        postAs(owner, "/api/v1/organizations/{id}/roles", Map.of("name", "Bad", "permissions", List.of("root.all")), org)
                .andExpect(status().isBadRequest());
    }

    @Test
    void builtInRolesAreImmutableAndAssignedRolesCannotBeDeleted() throws Exception {
        TestUser owner = newUser("owner");
        TestUser member = newUser("member");
        UUID org = createOrganization(owner, "Roles");
        UUID memberRole = roleId(owner, org, "MEMBER");

        patchAs(owner, "/api/v1/organizations/{o}/roles/{r}", Map.of("name", "Renamed"), org, memberRole)
                .andExpect(status().isConflict());
        deleteAs(owner, "/api/v1/organizations/{o}/roles/{r}", org, roleId(owner, org, "OWNER"))
                .andExpect(status().isConflict());

        JsonNode custom = body(postAs(owner, "/api/v1/organizations/{id}/roles",
                Map.of("name", "Auditor", "permissions", List.of("audit.read")), org).andExpect(status().isCreated()));
        UUID customRole = UUID.fromString(custom.get("id").asString());
        postAs(owner, "/api/v1/organizations/{id}/roles", Map.of("name", "auditor", "permissions", List.of()), org)
                .andExpect(status().isConflict());
        UUID memberMembership = join(owner, org, member, "MEMBER");
        putAs(owner, "/api/v1/organizations/{o}/members/{m}/roles", Map.of("roleIds", List.of(customRole)), org,
                memberMembership).andExpect(status().isOk());
        getAs(member, "/api/v1/organizations/{id}/audit-events", org).andExpect(status().isOk());

        deleteAs(owner, "/api/v1/organizations/{o}/roles/{r}", org, customRole).andExpect(status().isConflict());
        putAs(owner, "/api/v1/organizations/{o}/members/{m}/roles", Map.of("roleIds", List.of(memberRole)), org,
                memberMembership).andExpect(status().isOk());
        getAs(member, "/api/v1/organizations/{id}/audit-events", org).andExpect(status().isForbidden());
        deleteAs(owner, "/api/v1/organizations/{o}/roles/{r}", org, customRole).andExpect(status().isNoContent());
    }

    @Test
    void theLastActiveOwnerCannotBeRemovedSuspendedOrDowngraded() throws Exception {
        TestUser owner = newUser("owner");
        TestUser second = newUser("second");
        UUID org = createOrganization(owner, "Ownership");
        UUID ownerMembership = membershipId(owner, org);
        UUID memberRole = roleId(owner, org, "MEMBER");

        deleteAs(owner, "/api/v1/organizations/{o}/members/{m}", org, ownerMembership).andExpect(status().isConflict());
        putAs(owner, "/api/v1/organizations/{o}/members/{m}/roles", Map.of("roleIds", List.of(memberRole)), org,
                ownerMembership).andExpect(status().isConflict());
        postAs(owner, "/api/v1/organizations/{o}/members/{m}/suspend", "{}", org, ownerMembership)
                .andExpect(status().isConflict());
        putAs(owner, "/api/v1/organizations/{o}/members/{m}/roles", Map.of("roleIds", List.of()), org,
                ownerMembership).andExpect(status().isBadRequest());

        // With a second owner, the first one can step down and leave.
        UUID secondMembership = join(owner, org, second, "OWNER");
        putAs(owner, "/api/v1/organizations/{o}/members/{m}/roles", Map.of("roleIds", List.of(memberRole)), org,
                ownerMembership).andExpect(status().isOk());
        deleteAs(second, "/api/v1/organizations/{o}/members/{m}", org, secondMembership)
                .andExpect(status().isConflict());
        deleteAs(owner, "/api/v1/organizations/{o}/members/{m}", org, ownerMembership)
                .andExpect(status().isNoContent());
        getAs(owner, "/api/v1/organizations/{id}", org).andExpect(status().isNotFound());
    }

    @Test
    void suspendedAndRemovedMembersLoseAccessImmediately() throws Exception {
        TestUser owner = newUser("owner");
        TestUser member = newUser("member");
        UUID org = createOrganization(owner, "Lifecycle");
        UUID membership = join(owner, org, member, "ADMIN");

        postAs(owner, "/api/v1/organizations/{o}/members/{m}/suspend", "{}", org, membership)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDED"));
        getAs(member, "/api/v1/organizations/{id}", org).andExpect(status().isNotFound());
        getAs(member, "/api/v1/organizations/{id}/members", org).andExpect(status().isNotFound());
        getAs(member, "/api/v1/organizations").andExpect(jsonPath("$.totalElements").value(0));
        getAs(owner, "/api/v1/organizations/{id}/members", org).andExpect(jsonPath("$.totalElements").value(1));
        getAs(owner, "/api/v1/organizations/{id}/members?includeSuspended=true", org)
                .andExpect(jsonPath("$.totalElements").value(2));

        postAs(owner, "/api/v1/organizations/{o}/members/{m}/reactivate", "{}", org, membership)
                .andExpect(status().isOk());
        getAs(member, "/api/v1/organizations/{id}", org).andExpect(status().isOk());

        deleteAs(owner, "/api/v1/organizations/{o}/members/{m}", org, membership).andExpect(status().isNoContent());
        getAs(member, "/api/v1/organizations/{id}", org).andExpect(status().isNotFound());
        getAs(owner, "/api/v1/organizations/{o}/members/{m}", org, membership).andExpect(status().isNotFound());

        // A removed member can be invited again and gets only the new role.
        join(owner, org, member, "MEMBER");
        getAs(member, "/api/v1/organizations/{id}", org).andExpect(status().isOk())
                .andExpect(jsonPath("$.myRoles.length()").value(1))
                .andExpect(jsonPath("$.myRoles[0].code").value("MEMBER"));
    }

    @Test
    void membersCanUpdateTheOrganizationOnlyWithPermissionAndValidValues() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Settings");
        patchAs(owner, "/api/v1/organizations/{id}", Map.of("name", "Settings 2", "timezone", "Europe/Paris"), org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Settings 2"))
                .andExpect(jsonPath("$.timezone").value("Europe/Paris"));
        patchAs(owner, "/api/v1/organizations/{id}", Map.of("timezone", "Mars/Olympus"), org)
                .andExpect(status().isBadRequest());
        patchAs(owner, "/api/v1/organizations/{id}", Map.of("name", "x"), org)
                .andExpect(status().isBadRequest());
        postAs(owner, "/api/v1/organizations", Map.of("name", " "), org).andExpect(status().isBadRequest());
    }
}
