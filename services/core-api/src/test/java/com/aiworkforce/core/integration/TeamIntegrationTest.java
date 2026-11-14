package com.aiworkforce.core.integration;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Teams: membership with history, leadership rules and department association. */
class TeamIntegrationTest extends WorkforceFlows {

    @Test
    void teamsMixDepartmentsAndKeepTheirMembers() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Teams");
        UUID eng = department(owner, org, "Engineering", "ENG", null);
        UUID design = department(owner, org, "Design", "DES", null);
        UUID dev = employee(owner, org, body("displayName", "Dev Eloper", "departmentId", eng));
        UUID designer = employee(owner, org, body("displayName", "Des Igner", "departmentId", design));

        UUID launch = team(owner, org, "Launch squad", eng, null);
        addToTeam(owner, org, launch, dev);
        addToTeam(owner, org, launch, designer);

        getAs(owner, path(org, "/teams/" + launch)).andExpect(status().isOk())
                .andExpect(jsonPath("$.team.name").value("Launch squad"))
                .andExpect(jsonPath("$.team.department.name").value("Engineering"))
                .andExpect(jsonPath("$.team.memberCount").value(2))
                .andExpect(jsonPath("$.members", hasSize(2)))
                .andExpect(jsonPath("$.members[0].employee.displayName").value("Dev Eloper"));
        getAs(owner, path(org, "/teams?departmentId=" + eng)).andExpect(jsonPath("$.totalElements").value(1));
        getAs(owner, path(org, "/teams?departmentId=" + design)).andExpect(jsonPath("$.totalElements").value(0));
        getAs(owner, path(org, "/teams?q=squad")).andExpect(jsonPath("$.totalElements").value(1));
        getAs(owner, path(org, "/employees/" + designer + "/teams")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Launch squad"));

        patchAs(owner, path(org, "/teams/" + launch), body("description", "Ship it")).andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Ship it"));
        putAs(owner, path(org, "/teams/" + launch + "/department"), body("departmentId", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.department").doesNotExist());
        postAs(owner, path(org, "/teams"), body("name", "launch SQUAD")).andExpect(status().isConflict());
        postAs(owner, path(org, "/teams"), body("name", "x")).andExpect(status().isBadRequest());
    }

    @Test
    void duplicateAssignmentsAreRejectedAndLeavingKeepsHistory() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "History");
        UUID worker = employee(owner, org, "Worker");
        UUID squad = team(owner, org, "Squad", null, null);

        addToTeam(owner, org, squad, worker);
        postAs(owner, path(org, "/teams/" + squad + "/members"), body("employeeId", worker))
                .andExpect(status().isConflict());
        postAs(owner, path(org, "/teams/" + squad + "/members"), body("employeeId", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        postAs(owner, path(org, "/teams/" + squad + "/members"), "{}").andExpect(status().isBadRequest());

        deleteAs(owner, path(org, "/teams/" + squad + "/members/" + worker)).andExpect(status().isNoContent());
        deleteAs(owner, path(org, "/teams/" + squad + "/members/" + worker)).andExpect(status().isNotFound());
        getAs(owner, path(org, "/teams/" + squad + "/members")).andExpect(jsonPath("$", hasSize(0)));
        getAs(owner, path(org, "/teams/" + squad + "/members?includeFormer=true")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].leftAt").isNotEmpty());

        // Re-joining creates a new participation next to the old one.
        addToTeam(owner, org, squad, worker);
        getAs(owner, path(org, "/teams/" + squad + "/members?includeFormer=true")).andExpect(jsonPath("$", hasSize(2)));
        getAs(owner, path(org, "/teams/" + squad + "/members")).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void theLeaderMustBeAnActiveMemberOfTheTeam() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Leadership");
        UUID lead = employee(owner, org, "Lea Lead");
        UUID other = employee(owner, org, "Otto Other");
        UUID squad = team(owner, org, "Squad", null, null);

        // Not a member yet: refused.
        putAs(owner, path(org, "/teams/" + squad + "/leader"), body("employeeId", lead)).andExpect(status().isConflict());
        addToTeam(owner, org, squad, lead);
        putAs(owner, path(org, "/teams/" + squad + "/leader"), body("employeeId", lead)).andExpect(status().isOk())
                .andExpect(jsonPath("$.leader.displayName").value("Lea Lead"));
        // The leader cannot leave until replaced.
        deleteAs(owner, path(org, "/teams/" + squad + "/members/" + lead)).andExpect(status().isConflict());
        // The leader cannot be deactivated while leading an active team.
        putAs(owner, path(org, "/employees/" + lead + "/status"), body("status", "INACTIVE"))
                .andExpect(status().isConflict());

        addToTeam(owner, org, squad, other);
        putAs(owner, path(org, "/teams/" + squad + "/leader"), body("employeeId", other)).andExpect(status().isOk());
        deleteAs(owner, path(org, "/teams/" + squad + "/members/" + lead)).andExpect(status().isNoContent());
        getAs(owner, path(org, "/teams/" + squad)).andExpect(jsonPath("$.members[?(@.leader == true)].employee.displayName")
                .value("Otto Other"));
        putAs(owner, path(org, "/teams/" + squad + "/leader"), body("employeeId", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.leader").doesNotExist());

        // A leader given at creation joins as the first member.
        UUID founded = team(owner, org, "Founded", null, lead);
        getAs(owner, path(org, "/teams/" + founded)).andExpect(jsonPath("$.team.memberCount").value(1))
                .andExpect(jsonPath("$.team.leader.displayName").value("Lea Lead"));
        // An inactive employee cannot lead.
        setStatus(owner, org, other, "INACTIVE");
        postAs(owner, path(org, "/teams"), body("name", "Inactive lead", "leaderEmployeeId", other))
                .andExpect(status().isConflict());
    }

    @Test
    void archivedTeamsStayReadableButAreFrozen() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Archive");
        UUID worker = employee(owner, org, "Worker");
        UUID squad = team(owner, org, "Squad", null, worker);

        postAs(owner, path(org, "/teams/" + squad + "/archive"), "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        postAs(owner, path(org, "/teams/" + squad + "/archive"), "{}").andExpect(status().isConflict());
        UUID other = employee(owner, org, "Other");
        postAs(owner, path(org, "/teams/" + squad + "/members"), body("employeeId", other))
                .andExpect(status().isConflict());
        getAs(owner, path(org, "/teams?status=ARCHIVED")).andExpect(jsonPath("$.totalElements").value(1));

        // The leader of an archived team may be deactivated: the team simply loses its leader.
        setStatus(owner, org, worker, "INACTIVE");
        getAs(owner, path(org, "/teams/" + squad)).andExpect(jsonPath("$.team.leader").doesNotExist())
                .andExpect(jsonPath("$.team.memberCount").value(0));
        postAs(owner, path(org, "/teams/" + squad + "/restore"), "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
