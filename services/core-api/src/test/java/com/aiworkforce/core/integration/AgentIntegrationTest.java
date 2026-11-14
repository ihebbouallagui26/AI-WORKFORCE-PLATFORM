package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** AI agents: identity, validation, lifecycle, department, search, history and team participation. */
class AgentIntegrationTest extends AgentFlows {

    @Test
    void anAgentStartsAsAnUnarmedDraftOwnedByTheOrganization() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Agents");
        UUID dept = department(owner, org, "Engineering", "ENG", null);

        JsonNode created = read(postAs(owner, agents(org, ""), body("name", "  Atlas Backend  ", "title", "Backend Engineer",
                "description", "Builds APIs", "departmentId", dept, "avatarTone", 3,
                "responsibilities", List.of("Design REST APIs", "  ", "Review pull requests"),
                "systemInstructions", "You are a careful backend engineer."))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agent.name").value("Atlas Backend"))
                .andExpect(jsonPath("$.agent.handle").value("atlas-backend"))
                .andExpect(jsonPath("$.agent.status").value("DRAFT"))
                .andExpect(jsonPath("$.agent.configVersion").value(1))
                .andExpect(jsonPath("$.agent.avatarTone").value(3))
                .andExpect(jsonPath("$.agent.department.name").value("Engineering"))
                .andExpect(jsonPath("$.agent.responsibilities", hasSize(2)))
                // Secure defaults: no provider, no skill, no tool, memory disabled.
                .andExpect(jsonPath("$.providers", hasSize(0)))
                .andExpect(jsonPath("$.skills", hasSize(0)))
                .andExpect(jsonPath("$.tools", hasSize(0)))
                .andExpect(jsonPath("$.memory.enabled").value(false))
                .andExpect(jsonPath("$.memory.accessMode").value("AGENT_ONLY"))
                .andExpect(jsonPath("$.teams", hasSize(0)))
                .andExpect(jsonPath("$.systemInstructions").value("You are a careful backend engineer.")));
        UUID agent = id(created.get("agent"));

        // An agent is not a user nor an employee: nothing was created in those domains.
        getAs(owner, path(org, "/members")).andExpect(jsonPath("$.totalElements").value(1));
        getAs(owner, path(org, "/employees")).andExpect(jsonPath("$.totalElements").value(0));
        getAs(owner, agents(org, "/" + agent)).andExpect(status().isOk());
        // Identifiers are derived uniquely.
        agent(owner, org, "Atlas Backend");
        getAs(owner, agents(org, "?q=atlas")).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].handle", hasItem("atlas-backend-2")));
    }

    @Test
    void validationAndUniqueness() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Agent validation");
        agent(owner, org, body("name", "First", "handle", "first-agent"));

        postAs(owner, agents(org, ""), body("name", "Second", "handle", "first-agent")).andExpect(status().isConflict());
        postAs(owner, agents(org, ""), body("name", "x")).andExpect(status().isBadRequest());
        postAs(owner, agents(org, ""), body("name", "Bad handle", "handle", "Not Valid!")).andExpect(status().isBadRequest());
        postAs(owner, agents(org, ""), body("name", "Tone", "avatarTone", 9)).andExpect(status().isBadRequest());
        postAs(owner, agents(org, ""), body("name", "Long", "systemInstructions", "x".repeat(8001)))
                .andExpect(status().isBadRequest());
        postAs(owner, agents(org, ""), body("name", "Duties", "responsibilities", java.util.Collections.nCopies(21, "duty")))
                .andExpect(status().isBadRequest());
        postAs(owner, agents(org, ""), body("name", "Orphan", "departmentId", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        getAs(owner, agents(org, "")).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void editingBumpsTheConfigurationVersionAndIsAudited() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Agent edits");
        UUID agent = agent(owner, org, body("name", "Edith", "systemInstructions", "Secret prompt text"));

        patchAs(owner, agents(org, "/" + agent), body("title", "Editor", "systemInstructions", "New secret prompt"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.agent.title").value("Editor"))
                .andExpect(jsonPath("$.agent.configVersion").value(2));
        // No change, no new version.
        patchAs(owner, agents(org, "/" + agent), body("title", "Editor")).andExpect(jsonPath("$.agent.configVersion").value(2));
        long version = read(getAs(owner, agents(org, "/" + agent))).get("agent").get("version").asLong();
        patchAs(owner, agents(org, "/" + agent), body("description", "ok", "expectedVersion", version))
                .andExpect(status().isOk());
        patchAs(owner, agents(org, "/" + agent), body("description", "stale", "expectedVersion", version))
                .andExpect(status().isConflict());
        patchAs(owner, agents(org, "/" + agent), body("name", " ")).andExpect(status().isBadRequest());

        // History lists what changed, never the instructions themselves.
        String history = getAs(owner, agents(org, "/" + agent + "/history")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].action", hasItem("agent.updated")))
                .andExpect(jsonPath("$.content[*].action", hasItem("agent.created")))
                .andReturn().getResponse().getContentAsString();
        assertThat(history).contains("systemInstructions").doesNotContain("Secret prompt").doesNotContain("New secret prompt");
        String auditTrail = getAs(owner, path(org, "/audit-events?size=100")).andReturn().getResponse().getContentAsString();
        assertThat(auditTrail).doesNotContain("Secret prompt").doesNotContain("New secret prompt");
    }

    @Test
    void activationRequiresATestedValidPrimaryProvider() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Activation");
        UUID agent = agent(owner, org, "Ada");

        // No provider at all.
        postAs(owner, agents(org, "/" + agent + "/activate"), "{}").andExpect(status().isConflict());
        // A saved credential is not a valid connection: it is PENDING until tested.
        UUID pending = connect(owner, org, "OPENAI", "Pending provider", "valid-key-pending");
        bind(owner, org, agent, pending, "gpt-test-1", "PRIMARY");
        postAs(owner, agents(org, "/" + agent + "/activate"), "{}").andExpect(status().isConflict());
        // A rejected credential is not valid either.
        UUID bad = connect(owner, org, "OPENAI", "Bad provider", "bad-key-1234");
        postAs(owner, connections(org, "/" + bad + "/test"), "{}").andExpect(jsonPath("$.outcome").value("INVALID"));

        // Once tested and valid, the agent can be activated.
        postAs(owner, connections(org, "/" + pending + "/test"), "{}").andExpect(jsonPath("$.outcome").value("VALID"));
        postAs(owner, agents(org, "/" + agent + "/activate"), "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.agent.status").value("ACTIVE"));
        postAs(owner, agents(org, "/" + agent + "/activate"), "{}").andExpect(status().isConflict());
    }

    @Test
    void lifecycleDraftActivePausedArchived() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Lifecycle");
        UUID agent = activeAgent(owner, org, "Lia");

        postAs(owner, agents(org, "/" + agent + "/pause"), "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.agent.status").value("PAUSED"));
        postAs(owner, agents(org, "/" + agent + "/pause"), "{}").andExpect(status().isConflict());
        patchAs(owner, agents(org, "/" + agent), body("title", "Paused agents can be edited")).andExpect(status().isOk());
        postAs(owner, agents(org, "/" + agent + "/activate"), "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.agent.status").value("ACTIVE"));

        postAs(owner, agents(org, "/" + agent + "/archive"), "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.agent.status").value("ARCHIVED"));
        // Archived is final: frozen configuration, no lifecycle change.
        postAs(owner, agents(org, "/" + agent + "/archive"), "{}").andExpect(status().isConflict());
        postAs(owner, agents(org, "/" + agent + "/activate"), "{}").andExpect(status().isConflict());
        postAs(owner, agents(org, "/" + agent + "/pause"), "{}").andExpect(status().isConflict());
        patchAs(owner, agents(org, "/" + agent), body("title", "Nope")).andExpect(status().isConflict());
        putAs(owner, agents(org, "/" + agent + "/memory-config"),
                body("enabled", true, "scope", "PROJECT", "accessMode", "AGENT_ONLY")).andExpect(status().isConflict());
        putAs(owner, agents(org, "/" + agent + "/department"), body("departmentId", null)).andExpect(status().isConflict());
        getAs(owner, agents(org, "?status=ARCHIVED")).andExpect(jsonPath("$.totalElements").value(1));
        getAs(owner, agents(org, "/" + agent + "/history")).andExpect(jsonPath("$.content[*].action", hasItem("agent.archived")))
                .andExpect(jsonPath("$.content[*].action", hasItem("agent.activated")))
                .andExpect(jsonPath("$.content[*].action", hasItem("agent.paused")));
    }

    @Test
    void departmentsKeepHumanAndAgentCountsSeparate() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Departments");
        UUID dept = department(owner, org, "Platform", "PLAT", null);
        employee(owner, org, body("displayName", "Hugo Human", "departmentId", dept));
        UUID agent = agent(owner, org, body("name", "Robo", "departmentId", dept));

        getAs(owner, path(org, "/departments/" + dept)).andExpect(jsonPath("$.employeeCount").value(1))
                .andExpect(jsonPath("$.agentCount").value(1));
        getAs(owner, agents(org, "?departmentId=" + dept)).andExpect(jsonPath("$.totalElements").value(1));
        // A department with agents cannot be archived or deleted; archived departments take no agent.
        postAs(owner, path(org, "/departments/" + dept + "/archive"), "{}").andExpect(status().isConflict());
        deleteAs(owner, path(org, "/departments/" + dept)).andExpect(status().isConflict());
        putAs(owner, agents(org, "/" + agent + "/department"), body("departmentId", null)).andExpect(status().isOk());
        getAs(owner, path(org, "/departments/" + dept)).andExpect(jsonPath("$.agentCount").value(0));
        UUID other = department(owner, org, "Archive me", "OLD", null);
        postAs(owner, path(org, "/departments/" + other + "/archive"), "{}").andExpect(status().isOk());
        putAs(owner, agents(org, "/" + agent + "/department"), body("departmentId", other)).andExpect(status().isConflict());
        postAs(owner, agents(org, ""), body("name", "Late", "departmentId", other)).andExpect(status().isConflict());
        // Archiving an agent frees its department.
        putAs(owner, agents(org, "/" + agent + "/department"), body("departmentId", dept)).andExpect(status().isOk());
        postAs(owner, agents(org, "/" + agent + "/archive"), "{}").andExpect(status().isOk());
        getAs(owner, path(org, "/departments/" + dept)).andExpect(jsonPath("$.agentCount").value(0));
    }

    @Test
    void agentsJoinSeveralTeamsAlongsideHumansButNeverTwiceOrWhenArchived() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Teams");
        UUID human = employee(owner, org, "Hana Human");
        UUID agent = agent(owner, org, "Team bot");
        UUID alpha = team(owner, org, "Alpha", null, null);
        UUID beta = team(owner, org, "Beta", null, null);
        addToTeam(owner, org, alpha, human);

        addAgentToTeam(owner, org, alpha, agent);
        addAgentToTeam(owner, org, beta, agent);
        postAs(owner, path(org, "/teams/" + alpha + "/agents"), body("agentId", agent)).andExpect(status().isConflict());
        postAs(owner, path(org, "/teams/" + alpha + "/agents"), body("agentId", UUID.randomUUID())).andExpect(status().isNotFound());

        // Humans and agents stay separate, with their own counts.
        getAs(owner, path(org, "/teams/" + alpha)).andExpect(jsonPath("$.team.memberCount").value(1))
                .andExpect(jsonPath("$.team.agentCount").value(1))
                .andExpect(jsonPath("$.members", hasSize(1)));
        getAs(owner, path(org, "/teams/" + alpha + "/agents")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].agent.name").value("Team bot"));
        getAs(owner, agents(org, "/" + agent)).andExpect(jsonPath("$.teams", hasSize(2)));

        deleteAs(owner, path(org, "/teams/" + alpha + "/agents/" + agent)).andExpect(status().isNoContent());
        deleteAs(owner, path(org, "/teams/" + alpha + "/agents/" + agent)).andExpect(status().isNotFound());
        getAs(owner, path(org, "/teams/" + alpha + "/agents?includeFormer=true")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].leftAt").isNotEmpty());
        addAgentToTeam(owner, org, alpha, agent);

        // An archived team takes no agent; an archived agent takes no team and leaves its teams.
        UUID frozen = team(owner, org, "Frozen", null, null);
        postAs(owner, path(org, "/teams/" + frozen + "/archive"), "{}").andExpect(status().isOk());
        postAs(owner, path(org, "/teams/" + frozen + "/agents"), body("agentId", agent)).andExpect(status().isConflict());
        postAs(owner, agents(org, "/" + agent + "/archive"), "{}").andExpect(status().isOk());
        getAs(owner, path(org, "/teams/" + alpha)).andExpect(jsonPath("$.team.agentCount").value(0));
        getAs(owner, path(org, "/teams/" + beta + "/agents")).andExpect(jsonPath("$", hasSize(0)));
        postAs(owner, path(org, "/teams/" + beta + "/agents"), body("agentId", agent)).andExpect(status().isConflict());
        // Former membership stays in the history.
        getAs(owner, path(org, "/teams/" + beta + "/agents?includeFormer=true")).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void searchFiltersAndPagination() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Search");
        UUID dev = agent(owner, org, body("name", "Dev Bot", "title", "Developer"));
        agent(owner, org, body("name", "Doc Bot", "title", "Technical Writer"));
        activeAgent(owner, org, "Zed Bot");

        getAs(owner, agents(org, "")).andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].name").value("Dev Bot"));
        getAs(owner, agents(org, "?q=writer")).andExpect(jsonPath("$.totalElements").value(1));
        getAs(owner, agents(org, "?q=BOT&size=2&page=1")).andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalPages").value(2));
        getAs(owner, agents(org, "?status=ACTIVE")).andExpect(jsonPath("$.totalElements").value(1));
        getAs(owner, agents(org, "?status=DRAFT")).andExpect(jsonPath("$.totalElements").value(2));
        getAs(owner, agents(org, "?q=%25")).andExpect(jsonPath("$.totalElements").value(0));
        getAs(owner, agents(org, "?size=101")).andExpect(status().isBadRequest());
        assertThat(dev).isNotNull();
    }
}
