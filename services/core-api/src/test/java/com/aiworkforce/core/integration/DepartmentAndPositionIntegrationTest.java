package com.aiworkforce.core.integration;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Departments (hierarchy, integrity) and positions (catalog, lifecycle) through the real API. */
class DepartmentAndPositionIntegrationTest extends WorkforceFlows {

    @Test
    void departmentsFormAConfigurableHierarchy() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Hierarchy");
        UUID engineering = department(owner, org, "Engineering", "eng", null);
        UUID frontend = department(owner, org, "Frontend", "FE", engineering);
        UUID backend = department(owner, org, "Backend", "BE", engineering);
        department(owner, org, "Design", "DESIGN", null);

        // The code is normalized to upper case.
        getAs(owner, path(org, "/departments/" + engineering)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ENG"))
                .andExpect(jsonPath("$.childCount").value(2))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        getAs(owner, path(org, "/departments/tree")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].department.name").value("Design"))
                .andExpect(jsonPath("$[1].department.name").value("Engineering"))
                .andExpect(jsonPath("$[1].children", hasSize(2)))
                .andExpect(jsonPath("$[1].children[0].department.name").value("Backend"));

        getAs(owner, path(org, "/departments?rootOnly=true")).andExpect(jsonPath("$.totalElements").value(2));
        getAs(owner, path(org, "/departments?parentId=" + engineering)).andExpect(jsonPath("$.totalElements").value(2));
        getAs(owner, path(org, "/departments?q=front")).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(frontend.toString()));
        // LIKE wildcards in the search are literals.
        getAs(owner, path(org, "/departments?q=%25")).andExpect(jsonPath("$.totalElements").value(0));

        // Deeper nesting and moving.
        UUID qa = department(owner, org, "QA", "QA", backend);
        putAs(owner, path(org, "/departments/" + qa + "/parent"), body("parentId", frontend))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parentId").value(frontend.toString()));
        putAs(owner, path(org, "/departments/" + qa + "/parent"), body("parentId", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parentId").doesNotExist());
    }

    @Test
    void departmentValidationAndUniqueness() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Validation");
        UUID sales = department(owner, org, "Sales", "SALES", null);

        postAs(owner, path(org, "/departments"), body("name", "Other", "code", "sales"))
                .andExpect(status().isConflict());
        postAs(owner, path(org, "/departments"), body("name", "x", "code", "OK")).andExpect(status().isBadRequest());
        postAs(owner, path(org, "/departments"), body("name", "Bad code", "code", "no spaces!"))
                .andExpect(status().isBadRequest());
        postAs(owner, path(org, "/departments"), body("name", "Orphan", "code", "ORPH", "parentId", UUID.randomUUID()))
                .andExpect(status().isNotFound());

        patchAs(owner, path(org, "/departments/" + sales), body("name", "Sales & Support"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Sales & Support"))
                .andExpect(jsonPath("$.code").value("SALES"));
        UUID support = department(owner, org, "Support", "SUP", null);
        patchAs(owner, path(org, "/departments/" + support), body("code", "SALES")).andExpect(status().isConflict());

        // Optimistic locking: a stale expectedVersion is refused.
        JsonNode current = read(getAs(owner, path(org, "/departments/" + sales)));
        long version = current.get("version").asLong();
        patchAs(owner, path(org, "/departments/" + sales), body("description", "v2", "expectedVersion", version))
                .andExpect(status().isOk());
        patchAs(owner, path(org, "/departments/" + sales), body("description", "v3", "expectedVersion", version))
                .andExpect(status().isConflict());
    }

    @Test
    void hierarchyCyclesAreRejected() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Cycles");
        UUID a = department(owner, org, "A dept", "A", null);
        UUID b = department(owner, org, "B dept", "B", a);
        UUID c = department(owner, org, "C dept", "C", b);

        putAs(owner, path(org, "/departments/" + a + "/parent"), body("parentId", a)).andExpect(status().isConflict());
        putAs(owner, path(org, "/departments/" + a + "/parent"), body("parentId", c)).andExpect(status().isConflict());
        putAs(owner, path(org, "/departments/" + a + "/parent"), body("parentId", b)).andExpect(status().isConflict());
        putAs(owner, path(org, "/departments/" + b + "/parent"), body("parentId", c)).andExpect(status().isConflict());
        // The tree is unchanged.
        getAs(owner, path(org, "/departments/tree")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].children[0].children[0].department.name").value("C dept"));
    }

    @Test
    void departmentNestingDepthIsBounded() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Depth");
        UUID parent = null;
        for (int level = 1; level <= 10; level++) {
            parent = department(owner, org, "Level " + level, "L" + level, parent);
        }
        postAs(owner, path(org, "/departments"), body("name", "Too deep", "code", "L11", "parentId", parent))
                .andExpect(status().isConflict());
    }

    @Test
    void departmentLifecycleProtectsStructureAndEmployees() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Lifecycle");
        UUID parent = department(owner, org, "Parent", "PARENT", null);
        UUID child = department(owner, org, "Child", "CHILD", parent);
        UUID worker = employee(owner, org, "Worker");
        putAs(owner, path(org, "/employees/" + worker + "/department"), body("departmentId", child))
                .andExpect(status().isOk());

        // Cannot archive or delete a department with active sub-departments or employees.
        postAs(owner, path(org, "/departments/" + parent + "/archive"), "{}").andExpect(status().isConflict());
        deleteAs(owner, path(org, "/departments/" + parent)).andExpect(status().isConflict());
        postAs(owner, path(org, "/departments/" + child + "/archive"), "{}").andExpect(status().isConflict());
        deleteAs(owner, path(org, "/departments/" + child)).andExpect(status().isConflict());

        // After the employee leaves, the child can be archived; then the parent too.
        putAs(owner, path(org, "/employees/" + worker + "/department"), body("departmentId", null))
                .andExpect(status().isOk());
        postAs(owner, path(org, "/departments/" + child + "/archive"), "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        postAs(owner, path(org, "/departments/" + child + "/archive"), "{}").andExpect(status().isConflict());

        // An archived department cannot receive employees, children or a head.
        putAs(owner, path(org, "/employees/" + worker + "/department"), body("departmentId", child))
                .andExpect(status().isConflict());
        postAs(owner, path(org, "/departments"), body("name", "Late", "code", "LATE", "parentId", child))
                .andExpect(status().isConflict());
        putAs(owner, path(org, "/departments/" + child + "/head"), body("employeeId", worker))
                .andExpect(status().isConflict());

        // Archived departments are hidden from the default tree but available on request.
        getAs(owner, path(org, "/departments/tree")).andExpect(jsonPath("$[0].children", hasSize(0)));
        getAs(owner, path(org, "/departments/tree?includeArchived=true"))
                .andExpect(jsonPath("$[0].children", hasSize(1)));

        // Restoring a child needs an active parent.
        postAs(owner, path(org, "/departments/" + child + "/restore"), "{}").andExpect(status().isOk());
        postAs(owner, path(org, "/departments/" + child + "/archive"), "{}").andExpect(status().isOk());
        postAs(owner, path(org, "/departments/" + parent + "/archive"), "{}").andExpect(status().isOk());
        postAs(owner, path(org, "/departments/" + child + "/restore"), "{}").andExpect(status().isConflict());

        // A department nobody belongs to can be deleted; one that still has employees cannot.
        UUID unused = department(owner, org, "Unused", "UNUSED", null);
        deleteAs(owner, path(org, "/departments/" + unused)).andExpect(status().isNoContent());
        getAs(owner, path(org, "/departments/" + unused)).andExpect(status().isNotFound());
        UUID used = department(owner, org, "Used", "USED", null);
        putAs(owner, path(org, "/employees/" + worker + "/department"), body("departmentId", used))
                .andExpect(status().isOk());
        deleteAs(owner, path(org, "/departments/" + used)).andExpect(status().isConflict());
        setStatus(owner, org, worker, "INACTIVE");
        // Even an inactive employee still references the department: archive instead of deleting.
        deleteAs(owner, path(org, "/departments/" + used)).andExpect(status().isConflict());
        postAs(owner, path(org, "/departments/" + used + "/archive"), "{}").andExpect(status().isOk());
    }

    @Test
    void aDepartmentUsedByAPositionOrTeamCannotBeDeleted() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "References");
        UUID withPosition = department(owner, org, "With position", "WP", null);
        position(owner, org, "Specialist", "SPEC", withPosition);
        deleteAs(owner, path(org, "/departments/" + withPosition)).andExpect(status().isConflict());

        UUID withTeam = department(owner, org, "With team", "WT", null);
        team(owner, org, "Squad", withTeam, null);
        deleteAs(owner, path(org, "/departments/" + withTeam)).andExpect(status().isConflict());
        getAs(owner, path(org, "/departments/" + withTeam)).andExpect(status().isOk());
    }

    @Test
    void departmentHeadMustBeACurrentEmployeeOfTheOrganization() throws Exception {
        TestUser owner = newUser("owner");
        TestUser other = newUser("other");
        UUID org = createOrganization(owner, "Heads");
        UUID otherOrg = createOrganization(other, "Other heads");
        UUID dept = department(owner, org, "Engineering", "ENG", null);
        UUID head = employee(owner, org, "Hedda Head");
        UUID outsider = employee(other, otherOrg, "Olaf Outsider");

        putAs(owner, path(org, "/departments/" + dept + "/head"), body("employeeId", head))
                .andExpect(status().isOk()).andExpect(jsonPath("$.head.displayName").value("Hedda Head"));
        // An employee of another organization does not exist here.
        putAs(owner, path(org, "/departments/" + dept + "/head"), body("employeeId", outsider))
                .andExpect(status().isNotFound());
        postAs(owner, path(org, "/departments"), body("name", "Ops", "code", "OPS", "headEmployeeId", outsider))
                .andExpect(status().isNotFound());
        // The head cannot be deactivated while heading an active department.
        putAs(owner, path(org, "/employees/" + head + "/status"), body("status", "INACTIVE"))
                .andExpect(status().isConflict());
        putAs(owner, path(org, "/departments/" + dept + "/head"), body("employeeId", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.head").doesNotExist());
        setStatus(owner, org, head, "INACTIVE");
        // An inactive employee cannot become a head.
        putAs(owner, path(org, "/departments/" + dept + "/head"), body("employeeId", head))
                .andExpect(status().isConflict());
        // Employee counts only include current employees.
        UUID colleague = employee(owner, org, "Colin Colleague");
        putAs(owner, path(org, "/employees/" + colleague + "/department"), body("departmentId", dept))
                .andExpect(status().isOk());
        getAs(owner, path(org, "/departments/" + dept)).andExpect(jsonPath("$.employeeCount").value(1));
    }

    @Test
    void positionsAreAProfessionalCatalogSeparateFromRoles() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Catalog");
        UUID engineering = department(owner, org, "Engineering", "ENG", null);
        UUID backend = position(owner, org, "Senior Backend Engineer", "SBE", engineering);
        position(owner, org, "Product Manager", "PM", null);
        position(owner, org, "QA Engineer", "QA", engineering);

        getAs(owner, path(org, "/positions")).andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].title").value("Product Manager"));
        getAs(owner, path(org, "/positions?departmentId=" + engineering)).andExpect(jsonPath("$.totalElements").value(2));
        getAs(owner, path(org, "/positions?q=engineer")).andExpect(jsonPath("$.totalElements").value(2));
        getAs(owner, path(org, "/positions/" + backend)).andExpect(status().isOk())
                .andExpect(jsonPath("$.department.name").value("Engineering"))
                .andExpect(jsonPath("$.employeeCount").value(0));

        postAs(owner, path(org, "/positions"), body("title", "senior backend engineer", "code", "X"))
                .andExpect(status().isConflict());
        postAs(owner, path(org, "/positions"), body("title", "Another", "code", "sbe")).andExpect(status().isConflict());
        postAs(owner, path(org, "/positions"), body("title", "Nowhere", "code", "NW", "departmentId", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        postAs(owner, path(org, "/positions"), body("title", "x", "code", "OK")).andExpect(status().isBadRequest());

        patchAs(owner, path(org, "/positions/" + backend), body("title", "Staff Backend Engineer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Staff Backend Engineer"));
        putAs(owner, path(org, "/positions/" + backend + "/department"), body("departmentId", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.department").doesNotExist());
    }

    @Test
    void positionLifecycleFollowsEmployeeUsage() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Position lifecycle");
        UUID architect = position(owner, org, "Software Architect", "ARCH", null);
        UUID worker = employee(owner, org, "Ada Architect");
        putAs(owner, path(org, "/employees/" + worker + "/position"), body("positionId", architect))
                .andExpect(status().isOk()).andExpect(jsonPath("$.position.title").value("Software Architect"));

        getAs(owner, path(org, "/positions/" + architect)).andExpect(jsonPath("$.employeeCount").value(1));
        postAs(owner, path(org, "/positions/" + architect + "/archive"), "{}").andExpect(status().isConflict());
        deleteAs(owner, path(org, "/positions/" + architect)).andExpect(status().isConflict());

        putAs(owner, path(org, "/employees/" + worker + "/position"), body("positionId", null)).andExpect(status().isOk());
        postAs(owner, path(org, "/positions/" + architect + "/archive"), "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        // Archived positions cannot be assigned.
        putAs(owner, path(org, "/employees/" + worker + "/position"), body("positionId", architect))
                .andExpect(status().isConflict());
        postAs(owner, path(org, "/positions/" + architect + "/restore"), "{}").andExpect(status().isOk());
        putAs(owner, path(org, "/employees/" + worker + "/position"), body("positionId", architect))
                .andExpect(status().isOk());
        // Once nobody references them, positions can be deleted.
        putAs(owner, path(org, "/employees/" + worker + "/position"), body("positionId", null)).andExpect(status().isOk());
        UUID unused = position(owner, org, "Unused", "UNUSED", null);
        deleteAs(owner, path(org, "/positions/" + unused)).andExpect(status().isNoContent());
        deleteAs(owner, path(org, "/positions/" + architect)).andExpect(status().isNoContent());
    }
}
