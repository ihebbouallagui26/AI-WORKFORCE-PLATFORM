package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Employee profiles: lifecycle, assignments, reporting lines and the link to platform membership. */
class EmployeeIntegrationTest extends WorkforceFlows {

    @Test
    void anEmployeeCanExistWithoutAnyPlatformLogin() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "No login");
        UUID dept = department(owner, org, "Engineering", "ENG", null);
        UUID pos = position(owner, org, "Frontend Engineer", "FE", dept);

        UUID employee = employee(owner, org, body("displayName", "  Ines Dupont ", "employeeNumber", "E-001",
                "professionalEmail", "Ines.Dupont@Company.Example", "departmentId", dept, "positionId", pos,
                "description", "Joined from the Lyon office"));
        getAs(owner, path(org, "/employees/" + employee)).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Ines Dupont"))
                .andExpect(jsonPath("$.professionalEmail").value("ines.dupont@company.example"))
                .andExpect(jsonPath("$.employeeNumber").value("E-001"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.membershipId").doesNotExist())
                .andExpect(jsonPath("$.department.name").value("Engineering"))
                .andExpect(jsonPath("$.position.title").value("Frontend Engineer"))
                .andExpect(jsonPath("$.startDate").isNotEmpty())
                .andExpect(jsonPath("$.endDate").doesNotExist())
                .andExpect(jsonPath("$.directReportCount").value(0))
                .andExpect(jsonPath("$.version").value(0));
        // The only organization member is the owner: Ines is not a member.
        getAs(owner, path(org, "/members")).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void employeeValidationAndUniqueness() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Employee validation");
        employee(owner, org, body("displayName", "First", "employeeNumber", "N-1"));

        postAs(owner, path(org, "/employees"), body("displayName", "Second", "employeeNumber", "N-1"))
                .andExpect(status().isConflict());
        postAs(owner, path(org, "/employees"), body("displayName", " ")).andExpect(status().isBadRequest());
        postAs(owner, path(org, "/employees"), body("displayName", "Mail", "professionalEmail", "not-an-email"))
                .andExpect(status().isBadRequest());
        postAs(owner, path(org, "/employees"), body("displayName", "Long", "description", "x".repeat(1001)))
                .andExpect(status().isBadRequest());
        postAs(owner, path(org, "/employees"), body("displayName", "Orphan", "departmentId", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        postAs(owner, path(org, "/employees"), body("displayName", "Orphan", "managerId", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        // Nothing was created by the failed requests.
        getAs(owner, path(org, "/employees")).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void employeesAreSearchableFilterableAndPaginated() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Search");
        UUID eng = department(owner, org, "Engineering", "ENG", null);
        UUID design = department(owner, org, "Design", "DES", null);
        UUID dev = position(owner, org, "Developer", "DEV", eng);
        UUID boss = employee(owner, org, body("displayName", "Alice Boss", "departmentId", eng));
        UUID bob = employee(owner, org, body("displayName", "Bob Builder", "departmentId", eng, "positionId", dev,
                "managerId", boss, "employeeNumber", "B-77"));
        UUID carol = employee(owner, org, body("displayName", "Carol Designer", "departmentId", design,
                "professionalEmail", "carol@acme.example"));
        employee(owner, org, "Dave Dormant");
        setStatus(owner, org, carol, "ON_LEAVE");
        UUID dave = id(read(getAs(owner, path(org, "/employees?q=dave")).andExpect(jsonPath("$.totalElements").value(1)))
                .get("content").get(0));
        setStatus(owner, org, dave, "INACTIVE");

        getAs(owner, path(org, "/employees")).andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].displayName").value("Alice Boss"));
        getAs(owner, path(org, "/employees?size=2&page=1")).andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
        getAs(owner, path(org, "/employees?q=BUILD")).andExpect(jsonPath("$.totalElements").value(1));
        getAs(owner, path(org, "/employees?q=acme")).andExpect(jsonPath("$.content[0].id").value(carol.toString()));
        getAs(owner, path(org, "/employees?q=b-77")).andExpect(jsonPath("$.content[0].id").value(bob.toString()));
        getAs(owner, path(org, "/employees?status=INACTIVE")).andExpect(jsonPath("$.totalElements").value(1));
        getAs(owner, path(org, "/employees?status=ON_LEAVE")).andExpect(jsonPath("$.totalElements").value(1));
        getAs(owner, path(org, "/employees?departmentId=" + eng)).andExpect(jsonPath("$.totalElements").value(2));
        getAs(owner, path(org, "/employees?positionId=" + dev)).andExpect(jsonPath("$.totalElements").value(1));
        getAs(owner, path(org, "/employees?managerId=" + boss)).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].manager.displayName").value("Alice Boss"));
        getAs(owner, path(org, "/employees/" + boss)).andExpect(jsonPath("$.directReportCount").value(1));
        getAs(owner, path(org, "/employees?size=101")).andExpect(status().isBadRequest());
    }

    @Test
    void scalarUpdatesAndOptimisticLocking() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Updates");
        UUID employee = employee(owner, org, body("displayName", "Old Name", "employeeNumber", "E-1",
                "professionalEmail", "old@acme.example"));

        patchAs(owner, path(org, "/employees/" + employee),
                body("displayName", "New Name", "employeeNumber", "", "professionalEmail", "", "startDate", "2024-02-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("New Name"))
                .andExpect(jsonPath("$.employeeNumber").doesNotExist())
                .andExpect(jsonPath("$.professionalEmail").doesNotExist())
                .andExpect(jsonPath("$.startDate").value("2024-02-01"));

        long version = read(getAs(owner, path(org, "/employees/" + employee))).get("version").asLong();
        patchAs(owner, path(org, "/employees/" + employee), body("description", "ok", "expectedVersion", version))
                .andExpect(status().isOk());
        patchAs(owner, path(org, "/employees/" + employee), body("description", "stale", "expectedVersion", version))
                .andExpect(status().isConflict());
        patchAs(owner, path(org, "/employees/" + employee), body("displayName", " ")).andExpect(status().isBadRequest());

        // Employee numbers stay unique within the organization.
        employee(owner, org, body("displayName", "Other", "employeeNumber", "E-2"));
        patchAs(owner, path(org, "/employees/" + employee), body("employeeNumber", "E-2"))
                .andExpect(status().isConflict());
    }

    @Test
    void reportingLinesAreAcyclicAndOnlyInvolveCurrentEmployees() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Reporting");
        UUID ceo = employee(owner, org, "Ceo");
        UUID director = employee(owner, org, "Director");
        UUID engineer = employee(owner, org, "Engineer");
        setManager(owner, org, director, ceo);
        setManager(owner, org, engineer, director);

        // Self, direct cycle and indirect cycle.
        putAs(owner, path(org, "/employees/" + ceo + "/manager"), body("managerId", ceo)).andExpect(status().isConflict());
        putAs(owner, path(org, "/employees/" + ceo + "/manager"), body("managerId", director))
                .andExpect(status().isConflict());
        putAs(owner, path(org, "/employees/" + ceo + "/manager"), body("managerId", engineer))
                .andExpect(status().isConflict());
        putAs(owner, path(org, "/employees/" + director + "/manager"), body("managerId", engineer))
                .andExpect(status().isConflict());
        // A manager from nowhere does not exist.
        putAs(owner, path(org, "/employees/" + engineer + "/manager"), body("managerId", UUID.randomUUID()))
                .andExpect(status().isNotFound());

        // A manager with current reports cannot be deactivated; after reassigning, they can.
        putAs(owner, path(org, "/employees/" + director + "/status"), body("status", "INACTIVE"))
                .andExpect(status().isConflict());
        setManager(owner, org, engineer, ceo);
        setStatus(owner, org, director, "INACTIVE");
        // An inactive employee cannot become a manager.
        putAs(owner, path(org, "/employees/" + engineer + "/manager"), body("managerId", director))
                .andExpect(status().isConflict());
        // Clearing the manager is always possible.
        putAs(owner, path(org, "/employees/" + engineer + "/manager"), body("managerId", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.manager").doesNotExist());
    }

    @Test
    void deactivationKeepsTheRecordAndReactivationRestoresIt() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Status");
        UUID dept = department(owner, org, "Ops", "OPS", null);
        UUID pos = position(owner, org, "Operator", "OP", dept);
        UUID boss = employee(owner, org, "Boss");
        UUID worker = employee(owner, org, body("displayName", "Worker", "departmentId", dept, "positionId", pos,
                "managerId", boss));
        UUID squad = team(owner, org, "Squad", dept, null);
        addToTeam(owner, org, squad, worker);

        setStatus(owner, org, worker, "ON_LEAVE");
        getAs(owner, path(org, "/employees/" + worker)).andExpect(jsonPath("$.status").value("ON_LEAVE"))
                .andExpect(jsonPath("$.endDate").doesNotExist());

        setStatus(owner, org, worker, "INACTIVE");
        getAs(owner, path(org, "/employees/" + worker)).andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.endDate").isNotEmpty())
                // The professional record is kept as is.
                .andExpect(jsonPath("$.department.name").value("Ops"))
                .andExpect(jsonPath("$.position.title").value("Operator"));
        // The employee left the team (history kept) and no longer counts in the department.
        getAs(owner, path(org, "/teams/" + squad)).andExpect(jsonPath("$.team.memberCount").value(0));
        getAs(owner, path(org, "/teams/" + squad + "/members?includeFormer=true"))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].leftAt").isNotEmpty());
        getAs(owner, path(org, "/departments/" + dept)).andExpect(jsonPath("$.employeeCount").value(0));
        // Inactive employees cannot join teams.
        postAs(owner, path(org, "/teams/" + squad + "/members"), body("employeeId", worker))
                .andExpect(status().isConflict());
        // Idempotent.
        setStatus(owner, org, worker, "INACTIVE");

        setStatus(owner, org, worker, "ACTIVE");
        getAs(owner, path(org, "/employees/" + worker)).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.endDate").doesNotExist());
        getAs(owner, path(org, "/departments/" + dept)).andExpect(jsonPath("$.employeeCount").value(1));
    }

    @Test
    void reactivationNeedsActiveReferencesAndDropsAFormerManagerWhoLeft() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Reactivation");
        UUID dept = department(owner, org, "Temp", "TEMP", null);
        UUID boss = employee(owner, org, "Boss");
        UUID worker = employee(owner, org, body("displayName", "Worker", "departmentId", dept, "managerId", boss));
        setStatus(owner, org, worker, "INACTIVE");
        postAs(owner, path(org, "/departments/" + dept + "/archive"), "{}").andExpect(status().isOk());
        // The department is archived: the employee cannot come back into it.
        putAs(owner, path(org, "/employees/" + worker + "/status"), body("status", "ACTIVE"))
                .andExpect(status().isConflict());
        putAs(owner, path(org, "/employees/" + worker + "/department"), body("departmentId", null))
                .andExpect(status().isOk());
        // The former manager left in the meantime: the reporting line is dropped on return.
        setStatus(owner, org, boss, "INACTIVE");
        setStatus(owner, org, worker, "ACTIVE");
        getAs(owner, path(org, "/employees/" + worker)).andExpect(jsonPath("$.manager").doesNotExist());
    }

    @Test
    void anEmployeeProfileCanBeLinkedToAPlatformMembershipOfTheSameOrganization() throws Exception {
        TestUser owner = newUser("owner");
        TestUser guest = newUser("guest");
        TestUser stranger = newUser("stranger");
        UUID org = createOrganization(owner, "Onboarding");
        UUID otherOrg = createOrganization(stranger, "Elsewhere");

        // Onboarding: B1 invitation -> membership -> employee profile linked to it.
        UUID membership = join(owner, org, guest, "MEMBER");
        UUID employee = employee(owner, org, body("displayName", "Gina Guest", "membershipId", membership));
        getAs(owner, path(org, "/employees/" + employee)).andExpect(jsonPath("$.membershipId").value(membership.toString()))
                .andExpect(jsonPath("$.membershipStatus").value("ACTIVE"));
        // The account's e-mail is never part of the employee record.
        String payload = getAs(owner, path(org, "/employees/" + employee)).andReturn().getResponse().getContentAsString();
        assertThat(payload).doesNotContain(guest.email());

        // One profile per membership.
        postAs(owner, path(org, "/employees"), body("displayName", "Twin", "membershipId", membership))
                .andExpect(status().isConflict());
        UUID second = employee(owner, org, "Second");
        putAs(owner, path(org, "/employees/" + second + "/membership"), body("membershipId", membership))
                .andExpect(status().isConflict());
        // A membership of another organization is not visible here.
        UUID strangerMembership = membershipId(stranger, otherOrg);
        putAs(owner, path(org, "/employees/" + second + "/membership"), body("membershipId", strangerMembership))
                .andExpect(status().isNotFound());
        postAs(owner, path(org, "/employees"), body("displayName", "Spy", "membershipId", strangerMembership))
                .andExpect(status().isNotFound());

        // Suspending the member does not touch the professional record.
        postAs(owner, path(org, "/members/" + membership + "/suspend"), "{}").andExpect(status().isOk());
        getAs(owner, path(org, "/employees/" + employee)).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.membershipStatus").value("SUSPENDED"));
        // Removing the member keeps the employee too, and the membership cannot be linked again.
        deleteAs(owner, path(org, "/members/" + membership)).andExpect(status().isNoContent());
        getAs(owner, path(org, "/employees/" + employee)).andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipStatus").value("REMOVED"));
        putAs(owner, path(org, "/employees/" + second + "/membership"), body("membershipId", membership))
                .andExpect(status().isConflict());

        // Unlinking.
        putAs(owner, path(org, "/employees/" + employee + "/membership"), body("membershipId", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.membershipId").doesNotExist());
    }

    @Test
    void positionsAndDepartmentsNeverChangeAuthorization() throws Exception {
        TestUser owner = newUser("owner");
        TestUser member = newUser("member");
        UUID org = createOrganization(owner, "Authorization");
        UUID membership = join(owner, org, member, "MEMBER");
        UUID dept = department(owner, org, "Management", "MGT", null);
        UUID pos = position(owner, org, "Chief Everything Officer", "CEO", dept);
        UUID employee = employee(owner, org, body("displayName", "Mia Member", "membershipId", membership,
                "departmentId", dept, "positionId", pos));
        UUID reports = employee(owner, org, "Someone reporting");
        setManager(owner, org, reports, employee);

        // A grand position title and managing others grant nothing: still MEMBER permissions.
        List<String> before = permissionsOf(member, org);
        assertThat(before).doesNotContain("organization.update", "employee.manage",
                "department.manage", "member.manage");
        patchAs(member, "/api/v1/organizations/{id}", body("name", "Taken over"), org).andExpect(status().isForbidden());
        putAs(member, path(org, "/employees/" + reports + "/manager"), body("managerId", null))
                .andExpect(status().isForbidden());
        // Changing the position changes nothing either.
        putAs(owner, path(org, "/employees/" + employee + "/position"), body("positionId", null))
                .andExpect(status().isOk());
        assertThat(permissionsOf(member, org)).isEqualTo(before);
        getAs(member, path(org, "/employees")).andExpect(status().isOk());
    }

    private List<String> permissionsOf(TestUser user, UUID org) throws Exception {
        JsonNode permissions = read(getAs(user, "/api/v1/organizations/{id}", org)).get("myPermissions");
        return permissions.valueStream().map(JsonNode::asString).sorted().toList();
    }

    @Test
    void memberRoleCanReadButNotWriteWhileCustomRolesGetTheirExactRights() throws Exception {
        TestUser owner = newUser("owner");
        TestUser reader = newUser("reader");
        TestUser hr = newUser("hr");
        UUID org = createOrganization(owner, "Rights");
        join(owner, org, reader, "MEMBER");
        UUID hrMembership = join(owner, org, hr, "MEMBER");
        UUID target = employee(owner, org, "Target");
        UUID dept = department(owner, org, "Dept", "DEPT", null);

        getAs(reader, path(org, "/employees")).andExpect(status().isOk());
        getAs(reader, path(org, "/departments")).andExpect(status().isOk());
        getAs(reader, path(org, "/positions")).andExpect(status().isOk());
        getAs(reader, path(org, "/teams")).andExpect(status().isOk());
        postAs(reader, path(org, "/employees"), body("displayName", "Nope")).andExpect(status().isForbidden());
        patchAs(reader, path(org, "/employees/" + target), body("displayName", "Nope")).andExpect(status().isForbidden());
        putAs(reader, path(org, "/employees/" + target + "/status"), body("status", "INACTIVE"))
                .andExpect(status().isForbidden());
        postAs(reader, path(org, "/departments"), body("name", "Nope", "code", "NOPE")).andExpect(status().isForbidden());
        postAs(reader, path(org, "/positions"), body("title", "Nope", "code", "NOPE")).andExpect(status().isForbidden());
        postAs(reader, path(org, "/teams"), body("name", "Nope")).andExpect(status().isForbidden());
        deleteAs(reader, path(org, "/departments/" + dept)).andExpect(status().isForbidden());

        // A custom role: may create and edit employees but not deactivate them.
        giveCustomRole(owner, org, hrMembership, "HR assistant",
                List.of("employee.read", "employee.create", "employee.manage"));
        UUID created = employee(hr, org, "Created by HR");
        patchAs(hr, path(org, "/employees/" + created), body("description", "edited")).andExpect(status().isOk());
        putAs(hr, path(org, "/employees/" + created + "/status"), body("status", "ON_LEAVE")).andExpect(status().isOk());
        putAs(hr, path(org, "/employees/" + created + "/status"), body("status", "INACTIVE"))
                .andExpect(status().isForbidden());
        // ...and nothing about departments.
        postAs(hr, path(org, "/departments"), body("name", "Nope", "code", "NOPE")).andExpect(status().isForbidden());
        getAs(hr, path(org, "/departments")).andExpect(status().isForbidden());
        getAs(owner, path(org, "/audit-events")).andExpect(jsonPath("$.content[*].action", hasItem("employee.created")))
                .andExpect(jsonPath("$.content[*].action", not(hasItem("employee.deleted"))));
    }
}
