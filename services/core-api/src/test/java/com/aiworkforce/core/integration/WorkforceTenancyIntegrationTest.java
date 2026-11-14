package com.aiworkforce.core.integration;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tenant isolation of the workforce modules with two organizations: every endpoint, and every
 * reference to another organization's department, position, employee, team or membership.
 */
class WorkforceTenancyIntegrationTest extends WorkforceFlows {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aMemberOfOrganizationACannotReachTheWorkforceOfOrganizationB() throws Exception {
        TestUser alice = newUser("alice");
        TestUser bob = newUser("bob");
        UUID orgA = createOrganization(alice, "Org A");
        UUID orgB = createOrganization(bob, "Org B");
        UUID dept = department(bob, orgB, "Secret department", "SECRET", null);
        UUID pos = position(bob, orgB, "Secret position", "SP", dept);
        UUID emp = employee(bob, orgB, body("displayName", "Bob's employee", "departmentId", dept, "positionId", pos));
        UUID squad = team(bob, orgB, "Secret team", dept, emp);

        // Collections of B: 404, whatever the permission (the organization is simply not visible).
        for (String collection : List.of("/employees", "/departments", "/departments/tree", "/positions", "/teams")) {
            getAs(alice, path(orgB, collection)).andExpect(status().isNotFound());
        }
        // Items of B through B's path.
        getAs(alice, path(orgB, "/employees/" + emp)).andExpect(status().isNotFound());
        getAs(alice, path(orgB, "/departments/" + dept)).andExpect(status().isNotFound());
        getAs(alice, path(orgB, "/positions/" + pos)).andExpect(status().isNotFound());
        getAs(alice, path(orgB, "/teams/" + squad)).andExpect(status().isNotFound());
        getAs(alice, path(orgB, "/employees/" + emp + "/teams")).andExpect(status().isNotFound());
        // Writes on B.
        postAs(alice, path(orgB, "/employees"), body("displayName", "Intruder")).andExpect(status().isNotFound());
        postAs(alice, path(orgB, "/departments"), body("name", "Intruder", "code", "INT")).andExpect(status().isNotFound());
        patchAs(alice, path(orgB, "/employees/" + emp), body("displayName", "Pwned")).andExpect(status().isNotFound());
        putAs(alice, path(orgB, "/employees/" + emp + "/status"), body("status", "INACTIVE"))
                .andExpect(status().isNotFound());
        deleteAs(alice, path(orgB, "/departments/" + dept)).andExpect(status().isNotFound());
        postAs(alice, path(orgB, "/teams/" + squad + "/members"), body("employeeId", emp)).andExpect(status().isNotFound());

        // Items of B through A's path (where alice is OWNER): they do not exist in A.
        getAs(alice, path(orgA, "/employees/" + emp)).andExpect(status().isNotFound());
        getAs(alice, path(orgA, "/departments/" + dept)).andExpect(status().isNotFound());
        getAs(alice, path(orgA, "/positions/" + pos)).andExpect(status().isNotFound());
        getAs(alice, path(orgA, "/teams/" + squad)).andExpect(status().isNotFound());
        patchAs(alice, path(orgA, "/employees/" + emp), body("displayName", "Pwned")).andExpect(status().isNotFound());
        putAs(alice, path(orgA, "/employees/" + emp + "/status"), body("status", "INACTIVE"))
                .andExpect(status().isNotFound());
        patchAs(alice, path(orgA, "/departments/" + dept), body("name", "Pwned")).andExpect(status().isNotFound());
        deleteAs(alice, path(orgA, "/departments/" + dept)).andExpect(status().isNotFound());
        patchAs(alice, path(orgA, "/positions/" + pos), body("title", "Pwned")).andExpect(status().isNotFound());
        patchAs(alice, path(orgA, "/teams/" + squad), body("name", "Pwned")).andExpect(status().isNotFound());
        getAs(alice, path(orgA, "/employees/" + emp + "/teams")).andExpect(status().isNotFound());

        // Lists in A do not leak B.
        getAs(alice, path(orgA, "/employees")).andExpect(jsonPath("$.totalElements").value(0));
        getAs(alice, path(orgA, "/departments")).andExpect(jsonPath("$.totalElements").value(0));
        getAs(alice, path(orgA, "/teams")).andExpect(jsonPath("$.totalElements").value(0));

        // B is untouched.
        getAs(bob, path(orgB, "/employees/" + emp)).andExpect(jsonPath("$.displayName").value("Bob's employee"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        getAs(bob, path(orgB, "/departments/" + dept)).andExpect(jsonPath("$.name").value("Secret department"));
    }

    @Test
    void referencesToAnotherOrganizationAreRefusedEverywhere() throws Exception {
        TestUser alice = newUser("alice");
        TestUser bob = newUser("bob");
        UUID orgA = createOrganization(alice, "Org A");
        UUID orgB = createOrganization(bob, "Org B");
        UUID deptA = department(alice, orgA, "Dept A", "DA", null);
        UUID empA = employee(alice, orgA, "Employee A");
        UUID squadA = team(alice, orgA, "Team A", null, null);
        UUID deptB = department(bob, orgB, "Dept B", "DB", null);
        UUID posB = position(bob, orgB, "Position B", "PB", null);
        UUID empB = employee(bob, orgB, "Employee B");
        UUID bobMembershipB = membershipId(bob, orgB);

        // Department <-> parent / head.
        postAs(alice, path(orgA, "/departments"), body("name", "Child", "code", "CH", "parentId", deptB))
                .andExpect(status().isNotFound());
        putAs(alice, path(orgA, "/departments/" + deptA + "/parent"), body("parentId", deptB)).andExpect(status().isNotFound());
        putAs(alice, path(orgA, "/departments/" + deptA + "/head"), body("employeeId", empB)).andExpect(status().isNotFound());
        // Position <-> department.
        postAs(alice, path(orgA, "/positions"), body("title", "Cross", "code", "CR", "departmentId", deptB))
                .andExpect(status().isNotFound());
        // Employee <-> department, position, manager, membership (create and update).
        postAs(alice, path(orgA, "/employees"), body("displayName", "X", "departmentId", deptB)).andExpect(status().isNotFound());
        postAs(alice, path(orgA, "/employees"), body("displayName", "X", "positionId", posB)).andExpect(status().isNotFound());
        postAs(alice, path(orgA, "/employees"), body("displayName", "X", "managerId", empB)).andExpect(status().isNotFound());
        postAs(alice, path(orgA, "/employees"), body("displayName", "X", "membershipId", bobMembershipB))
                .andExpect(status().isNotFound());
        putAs(alice, path(orgA, "/employees/" + empA + "/department"), body("departmentId", deptB))
                .andExpect(status().isNotFound());
        putAs(alice, path(orgA, "/employees/" + empA + "/position"), body("positionId", posB)).andExpect(status().isNotFound());
        putAs(alice, path(orgA, "/employees/" + empA + "/manager"), body("managerId", empB)).andExpect(status().isNotFound());
        putAs(alice, path(orgA, "/employees/" + empA + "/membership"), body("membershipId", bobMembershipB))
                .andExpect(status().isNotFound());
        // Team <-> department, leader, members.
        postAs(alice, path(orgA, "/teams"), body("name", "X team", "departmentId", deptB)).andExpect(status().isNotFound());
        postAs(alice, path(orgA, "/teams"), body("name", "X team", "leaderEmployeeId", empB)).andExpect(status().isNotFound());
        putAs(alice, path(orgA, "/teams/" + squadA + "/department"), body("departmentId", deptB))
                .andExpect(status().isNotFound());
        putAs(alice, path(orgA, "/teams/" + squadA + "/leader"), body("employeeId", empB)).andExpect(status().isNotFound());
        postAs(alice, path(orgA, "/teams/" + squadA + "/members"), body("employeeId", empB)).andExpect(status().isNotFound());

        // Nothing of A was modified by the refused requests.
        getAs(alice, path(orgA, "/employees/" + empA)).andExpect(jsonPath("$.department").doesNotExist())
                .andExpect(jsonPath("$.position").doesNotExist()).andExpect(jsonPath("$.manager").doesNotExist())
                .andExpect(jsonPath("$.membershipId").doesNotExist());
        getAs(alice, path(orgA, "/teams/" + squadA)).andExpect(jsonPath("$.team.memberCount").value(0));
        getAs(alice, path(orgA, "/employees")).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void aManagerOfOneOrganizationCannotManageEmployeesOfAnother() throws Exception {
        TestUser alice = newUser("alice");
        TestUser bob = newUser("bob");
        createOrganization(alice, "Org A");
        UUID orgB = createOrganization(bob, "Org B");
        UUID empB = employee(bob, orgB, "Employee B");

        // Alice is OWNER of A with every workforce permission, yet powerless over B.
        for (String operation : List.of("/manager", "/department", "/position", "/membership")) {
            putAs(alice, path(orgB, "/employees/" + empB + operation), body("managerId", null, "departmentId", null,
                    "positionId", null, "membershipId", null)).andExpect(status().isNotFound());
        }
        setStatusExpecting404(alice, orgB, empB);
        getAs(bob, path(orgB, "/employees/" + empB)).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    private void setStatusExpecting404(TestUser user, UUID org, UUID employee) throws Exception {
        putAs(user, path(org, "/employees/" + employee + "/status"), body("status", "INACTIVE"))
                .andExpect(status().isNotFound());
    }

    @Test
    void databaseConstraintsRefuseCrossOrganizationReferencesEvenWhenWrittenDirectly() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id, email, email_verified, display_name, status, created_at, updated_at) "
                + "VALUES (?, ?, true, 'T', 'ACTIVE', now(), now())", user, user + "@example.test");
        UUID orgA = insertOrganization(user);
        UUID orgB = insertOrganization(user);
        UUID deptB = insertDepartment(orgB, "DB", null);
        UUID posB = insertPosition(orgB, "PB");
        UUID empB = insertEmployee(orgB, "Employee B", null, null, null);
        UUID squadB = insertTeam(orgB, "Team B");
        UUID membershipB = insertMembership(orgB, user);

        // The (organization_id, id) composite foreign keys reject each cross-organization link.
        assertRejected(() -> insertEmployee(orgA, "A1", deptB, null, null));
        assertRejected(() -> insertEmployee(orgA, "A2", null, posB, null));
        assertRejected(() -> insertEmployee(orgA, "A3", null, null, empB));
        assertRejected(() -> jdbc.update("INSERT INTO employee_profiles (id, organization_id, membership_id, display_name, "
                + "start_date, created_at, updated_at) VALUES (?, ?, ?, 'A4', current_date, now(), now())",
                UUID.randomUUID(), orgA, membershipB));
        assertRejected(() -> insertDepartment(orgA, "DA", deptB));
        assertRejected(() -> jdbc.update("UPDATE departments SET head_employee_id = ? WHERE id = ?", empB,
                insertDepartment(orgA, "DHEAD", null)));
        assertRejected(() -> jdbc.update("INSERT INTO positions (id, organization_id, title, code, department_id, created_at, "
                + "updated_at) VALUES (?, ?, 'Cross', 'CROSS', ?, now(), now())", UUID.randomUUID(), orgA, deptB));
        assertRejected(() -> jdbc.update("UPDATE teams SET leader_employee_id = ? WHERE id = ?", empB,
                insertTeam(orgA, "Team A")));
        UUID empA = insertEmployee(orgA, "Employee A", null, null, null);
        assertRejected(() -> jdbc.update("INSERT INTO team_members (id, organization_id, team_id, employee_id, joined_at) "
                + "VALUES (?, ?, ?, ?, now())", UUID.randomUUID(), orgA, squadB, empA));
        assertRejected(() -> jdbc.update("INSERT INTO team_members (id, organization_id, team_id, employee_id, joined_at) "
                + "VALUES (?, ?, ?, ?, now())", UUID.randomUUID(), orgA, insertTeam(orgA, "Team A2"), empB));
        // Self references and invalid values.
        assertRejected(() -> jdbc.update("UPDATE employee_profiles SET manager_id = id WHERE id = ?", empA));
        assertRejected(() -> jdbc.update("UPDATE departments SET parent_id = id WHERE id = ?", deptB));
        assertRejected(() -> jdbc.update("UPDATE employee_profiles SET status = 'FIRED' WHERE id = ?", empA));
        assertRejected(() -> jdbc.update("UPDATE employee_profiles SET end_date = start_date - 1 WHERE id = ?", empA));
        assertRejected(() -> jdbc.update("UPDATE departments SET code = 'bad code' WHERE id = ?", deptB));
    }

    @Test
    void databaseConstraintsKeepIdentifiersUniqueAndRolesSeparated() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id, email, email_verified, display_name, status, created_at, updated_at) "
                + "VALUES (?, ?, true, 'T', 'ACTIVE', now(), now())", user, user + "@example.test");
        UUID org = insertOrganization(user);
        UUID otherOrg = insertOrganization(user);
        UUID empA = insertEmployee(org, "E1", null, null, null);
        UUID squad = insertTeam(org, "T1");
        UUID membership = insertMembership(org, user);

        // The same code or name may exist in another organization, never twice in one.
        insertDepartment(org, "SAME", null);
        insertDepartment(otherOrg, "SAME", null);
        assertRejected(() -> insertDepartment(org, "SAME", null));
        insertTeam(otherOrg, "T1");
        assertRejected(() -> insertTeam(org, "t1"));
        insertPosition(org, "POS");
        assertRejected(() -> insertPosition(org, "POS"));
        // Only one current team membership per team and employee.
        jdbc.update("INSERT INTO team_members (id, organization_id, team_id, employee_id, joined_at) VALUES (?, ?, ?, ?, now())",
                UUID.randomUUID(), org, squad, empA);
        assertRejected(() -> jdbc.update("INSERT INTO team_members (id, organization_id, team_id, employee_id, joined_at) "
                + "VALUES (?, ?, ?, ?, now())", UUID.randomUUID(), org, squad, empA));
        // One profile per membership; membership is optional (employee without login).
        jdbc.update("UPDATE employee_profiles SET membership_id = ? WHERE id = ?", membership, empA);
        assertRejected(() -> jdbc.update("INSERT INTO employee_profiles (id, organization_id, membership_id, display_name, "
                + "start_date, created_at, updated_at) VALUES (?, ?, ?, 'Dup', current_date, now(), now())",
                UUID.randomUUID(), org, membership));
        // The runtime role can neither delete audit rows nor alter the new tables.
        assertRejected(() -> jdbc.execute("ALTER TABLE employee_profiles ADD COLUMN salary int"));
        assertRejected(() -> jdbc.execute("DROP TABLE team_members"));
    }

    private void assertRejected(Runnable statement) {
        org.assertj.core.api.Assertions.assertThatThrownBy(statement::run)
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private UUID insertOrganization(UUID creator) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, created_by, created_at, updated_at) VALUES (?, 'Org', ?, now(), now())",
                id, creator);
        return id;
    }

    private UUID insertMembership(UUID organizationId, UUID userId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO memberships (id, organization_id, user_id, status, joined_at, status_changed_at, created_at, "
                + "updated_at) VALUES (?, ?, ?, 'ACTIVE', now(), now(), now(), now())", id, organizationId, userId);
        return id;
    }

    private UUID insertDepartment(UUID organizationId, String code, UUID parentId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, organization_id, name, code, parent_id, created_at, updated_at) "
                + "VALUES (?, ?, 'Department', ?, ?, now(), now())", id, organizationId, code, parentId);
        return id;
    }

    private UUID insertPosition(UUID organizationId, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO positions (id, organization_id, title, code, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, now(), now())", id, organizationId, "Title " + code, code);
        return id;
    }

    private UUID insertEmployee(UUID organizationId, String name, UUID departmentId, UUID positionId, UUID managerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO employee_profiles (id, organization_id, display_name, department_id, position_id, manager_id, "
                + "start_date, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, current_date, now(), now())",
                id, organizationId, name, departmentId, positionId, managerId);
        return id;
    }

    private UUID insertTeam(UUID organizationId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO teams (id, organization_id, name, created_at, updated_at) VALUES (?, ?, ?, now(), now())",
                id, organizationId, name);
        return id;
    }
}
