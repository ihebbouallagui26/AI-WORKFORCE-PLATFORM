package com.aiworkforce.core.integration;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Reusable API flows for workforce tests (departments, positions, employees, teams). */
abstract class WorkforceFlows extends OrganizationFlows {

    /** Request body builder that accepts null values (Map.of does not). */
    protected static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    protected static String path(UUID org, String suffix) {
        return "/api/v1/organizations/" + org + suffix;
    }

    protected UUID id(JsonNode node) {
        return UUID.fromString(node.get("id").asString());
    }

    protected UUID department(TestUser user, UUID org, String name, String code, UUID parent) throws Exception {
        return id(read(postAs(user, path(org, "/departments"),
                body("name", name, "code", code, "parentId", parent)).andExpect(status().isCreated())));
    }

    protected UUID position(TestUser user, UUID org, String title, String code, UUID departmentId) throws Exception {
        return id(read(postAs(user, path(org, "/positions"),
                body("title", title, "code", code, "departmentId", departmentId)).andExpect(status().isCreated())));
    }

    protected UUID employee(TestUser user, UUID org, String name) throws Exception {
        return id(read(postAs(user, path(org, "/employees"), body("displayName", name))
                .andExpect(status().isCreated())));
    }

    protected UUID employee(TestUser user, UUID org, Map<String, Object> request) throws Exception {
        return id(read(postAs(user, path(org, "/employees"), request).andExpect(status().isCreated())));
    }

    protected UUID team(TestUser user, UUID org, String name, UUID departmentId, UUID leader) throws Exception {
        return id(read(postAs(user, path(org, "/teams"),
                body("name", name, "departmentId", departmentId, "leaderEmployeeId", leader))
                .andExpect(status().isCreated())));
    }

    protected void addToTeam(TestUser user, UUID org, UUID team, UUID employee) throws Exception {
        postAs(user, path(org, "/teams/" + team + "/members"), body("employeeId", employee))
                .andExpect(status().isCreated());
    }

    protected void setManager(TestUser user, UUID org, UUID employee, UUID manager) throws Exception {
        putAs(user, path(org, "/employees/" + employee + "/manager"), body("managerId", manager))
                .andExpect(status().isOk());
    }

    protected void setStatus(TestUser user, UUID org, UUID employee, String status) throws Exception {
        putAs(user, path(org, "/employees/" + employee + "/status"), body("status", status))
                .andExpect(status().isOk());
    }

    protected JsonNode read(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return body(result);
    }

    /** Creates a custom role with the given permission codes and assigns it (alone) to the member. */
    protected void giveCustomRole(TestUser owner, UUID org, UUID membershipId, String name, List<String> permissions)
            throws Exception {
        JsonNode role = body(postAs(owner, path(org, "/roles"), Map.of("name", name, "permissions", permissions))
                .andExpect(status().isCreated()));
        putAs(owner, path(org, "/members/" + membershipId + "/roles"), Map.of("roleIds", List.of(role.get("id").asString())))
                .andExpect(status().isOk());
    }
}
