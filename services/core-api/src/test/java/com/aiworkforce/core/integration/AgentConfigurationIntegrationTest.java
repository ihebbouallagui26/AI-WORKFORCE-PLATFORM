package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Agent configuration: provider bindings, skills, tool grants (least privilege) and memory policy. */
class AgentConfigurationIntegrationTest extends AgentFlows {

    private String providers(UUID org, UUID agent) {
        return agents(org, "/" + agent + "/providers");
    }

    @Test
    void anAgentHasOnePrimaryAndOrderedFallbacks() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Bindings");
        UUID agent = agent(owner, org, "Binder");
        UUID claude = validConnection(owner, org, "Claude");
        UUID openai = connect(owner, org, "OPENAI", "OpenAI", "valid-key-openai-1");
        UUID gemini = connect(owner, org, "GEMINI", "Gemini", "valid-key-gemini-1");

        // The primary comes first; a fallback without primary is refused.
        postAs(owner, providers(org, agent), body("connectionId", openai, "modelId", "gpt-test-1", "role", "FALLBACK"))
                .andExpect(status().isConflict());
        bind(owner, org, agent, claude, "claude-test-1", "PRIMARY");
        postAs(owner, providers(org, agent), body("connectionId", openai, "modelId", "gpt-test-1", "role", "PRIMARY"))
                .andExpect(status().isConflict());
        JsonNode both = bind(owner, org, agent, openai, "gpt-test-1", "FALLBACK");
        bind(owner, org, agent, gemini, "gemini-test-1", "FALLBACK");

        assertThat(both).hasSize(2);
        getAs(owner, providers(org, agent)).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].role").value("PRIMARY")).andExpect(jsonPath("$[0].priority").value(0))
                .andExpect(jsonPath("$[0].connection.displayName").value("Claude"))
                .andExpect(jsonPath("$[1].role").value("FALLBACK")).andExpect(jsonPath("$[1].priority").value(1))
                .andExpect(jsonPath("$[2].priority").value(2));
        // No credential material anywhere in the binding views.
        String text = getAs(owner, providers(org, agent)).andReturn().getResponse().getContentAsString();
        assertThat(text).doesNotContain("valid-key").doesNotContainIgnoringCase("credential");

        // Duplicates, explicit priorities, option validation.
        postAs(owner, providers(org, agent), body("connectionId", openai, "modelId", "gpt-test-1", "role", "FALLBACK"))
                .andExpect(status().isConflict());
        postAs(owner, providers(org, agent), body("connectionId", openai, "modelId", "gpt-test-2", "role", "FALLBACK", "priority", 2))
                .andExpect(status().isConflict());
        postAs(owner, providers(org, agent), body("connectionId", openai, "modelId", "bad model!", "role", "FALLBACK"))
                .andExpect(status().isBadRequest());
        postAs(owner, providers(org, agent), body("connectionId", openai, "modelId", "gpt-test-2", "role", "FALLBACK",
                "options", body("temperature", 3.5))).andExpect(status().isBadRequest());
        postAs(owner, providers(org, agent), body("connectionId", openai, "modelId", "gpt-test-2", "role", "FALLBACK",
                "options", body("temperature", 0.2, "maxOutputTokens", 2048, "topP", 0.9)))
                .andExpect(status().isCreated());
        getAs(owner, providers(org, agent)).andExpect(jsonPath("$[3].config").value(containsString("temperature")));
        postAs(owner, providers(org, agent), body("connectionId", gemini, "modelId", "gemini-test-2", "role", "FALLBACK"))
                .andExpect(status().isCreated());
        postAs(owner, providers(org, agent), body("connectionId", gemini, "modelId", "gemini-test-3", "role", "FALLBACK"))
                .andExpect(status().isConflict()); // at most five bindings
        postAs(owner, providers(org, agent), body("connectionId", UUID.randomUUID(), "modelId", "x-1", "role", "FALLBACK"))
                .andExpect(status().isNotFound());
    }

    @Test
    void promotingAFallbackSwapsItWithThePrimaryAndRemovalsKeepTheAgentConsistent() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Promotion");
        UUID agent = agent(owner, org, "Swapper");
        UUID claude = validConnection(owner, org, "Claude");
        UUID openai = connect(owner, org, "OPENAI", "OpenAI pending", "valid-key-openai-2");
        bind(owner, org, agent, claude, "claude-test-1", "PRIMARY");
        JsonNode bindings = bind(owner, org, agent, openai, "gpt-test-1", "FALLBACK");
        UUID fallback = UUID.fromString(bindings.get(1).get("id").asString());
        UUID primary = UUID.fromString(bindings.get(0).get("id").asString());

        postAs(owner, agents(org, "/" + agent + "/activate"), "{}").andExpect(status().isOk());
        // An active agent cannot get an untested connection as primary (the swap is refused).
        patchAs(owner, providers(org, agent) + "/" + fallback, body("makePrimary", true)).andExpect(status().isConflict());
        postAs(owner, connections(org, "/" + openai + "/test"), "{}").andExpect(status().isOk());
        patchAs(owner, providers(org, agent) + "/" + fallback, body("makePrimary", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("PRIMARY")).andExpect(jsonPath("$[0].connection.displayName").value("OpenAI pending"))
                .andExpect(jsonPath("$[1].role").value("FALLBACK")).andExpect(jsonPath("$[1].connection.displayName").value("Claude"))
                .andExpect(jsonPath("$[1].priority").value(1));

        // Changing the model and fallback priority.
        patchAs(owner, providers(org, agent) + "/" + primary, body("modelId", "claude-test-2", "priority", 5)).andExpect(status().isOk())
                .andExpect(jsonPath("$[1].modelId").value("claude-test-2")).andExpect(jsonPath("$[1].priority").value(5));
        patchAs(owner, providers(org, agent) + "/" + UUID.randomUUID(), body("modelId", "x-1")).andExpect(status().isNotFound());

        // The primary of an active agent cannot be removed; a primary with fallbacks needs a promotion first.
        deleteAs(owner, providers(org, agent) + "/" + fallback).andExpect(status().isConflict());
        postAs(owner, agents(org, "/" + agent + "/pause"), "{}").andExpect(status().isOk());
        deleteAs(owner, providers(org, agent) + "/" + fallback).andExpect(status().isConflict());
        deleteAs(owner, providers(org, agent) + "/" + primary).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        deleteAs(owner, providers(org, agent) + "/" + fallback).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        getAs(owner, agents(org, "/" + agent + "/history")).andExpect(jsonPath("$.content[*].action", hasItem("agent.provider.bound")))
                .andExpect(jsonPath("$.content[*].action", hasItem("agent.provider.unbound")))
                .andExpect(jsonPath("$.content[*].action", hasItem("agent.provider.updated")));
    }

    @Test
    void skillsAreOrganizationOwnedAssignedToAgentsAndDeactivatable() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Skills");
        UUID api = skill(owner, org, "API Development", "Engineering");
        UUID review = skill(owner, org, "Code Review", "Engineering");
        UUID design = skill(owner, org, "UI/UX Design", "Design");
        UUID agent = agent(owner, org, "Skilled");

        postAs(owner, path(org, "/skills"), body("name", "api development")).andExpect(status().isConflict());
        postAs(owner, path(org, "/skills"), body("name", "x")).andExpect(status().isBadRequest());
        getAs(owner, path(org, "/skills")).andExpect(jsonPath("$.totalElements").value(3));
        getAs(owner, path(org, "/skills?category=design")).andExpect(jsonPath("$.totalElements").value(1));
        getAs(owner, path(org, "/skills?q=review")).andExpect(jsonPath("$.totalElements").value(1));

        putAs(owner, agents(org, "/" + agent + "/skills"), body("skillIds", List.of(api, review))).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2))).andExpect(jsonPath("$[0].name").value("API Development"));
        getAs(owner, agents(org, "/" + agent)).andExpect(jsonPath("$.skills", hasSize(2)))
                .andExpect(jsonPath("$.agent.skillCount").value(2)).andExpect(jsonPath("$.agent.configVersion").value(2));
        // Replacement semantics, ordering and idempotence.
        putAs(owner, agents(org, "/" + agent + "/skills"), body("skillIds", List.of(review))).andExpect(jsonPath("$", hasSize(1)));
        putAs(owner, agents(org, "/" + agent + "/skills"), body("skillIds", List.of(review)))
                .andExpect(status().isOk());
        getAs(owner, agents(org, "/" + agent)).andExpect(jsonPath("$.agent.configVersion").value(3));
        putAs(owner, agents(org, "/" + agent + "/skills"), body("skillIds", List.of(UUID.randomUUID()))).andExpect(status().isNotFound());

        // In use: cannot be deleted, can be deactivated; a deactivated skill cannot be newly assigned.
        deleteAs(owner, path(org, "/skills/" + review)).andExpect(status().isConflict());
        patchAs(owner, path(org, "/skills/" + design), body("active", false)).andExpect(jsonPath("$.active").value(false));
        putAs(owner, agents(org, "/" + agent + "/skills"), body("skillIds", List.of(review, design))).andExpect(status().isConflict());
        patchAs(owner, path(org, "/skills/" + review), body("active", false)).andExpect(status().isOk());
        putAs(owner, agents(org, "/" + agent + "/skills"), body("skillIds", List.of(review))).andExpect(status().isOk());
        getAs(owner, agents(org, "/" + agent + "/skills")).andExpect(jsonPath("$[0].active").value(false));
        getAs(owner, path(org, "/skills?active=true")).andExpect(jsonPath("$.totalElements").value(1));
        deleteAs(owner, path(org, "/skills/" + design)).andExpect(status().isNoContent());
        patchAs(owner, path(org, "/skills/" + api), body("name", "Code Review")).andExpect(status().isConflict());
    }

    @Test
    void toolsAreMetadataAndDisabledUnlessExplicitlyGranted() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Tools");
        UUID agent = agent(owner, org, "Tooled");

        getAs(owner, path(org, "/tools")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[?(@.code == 'git.access')].risk").value("HIGH"))
                .andExpect(jsonPath("$[?(@.code == 'project.files.read')].mcpServer").value("filesystem"));
        // Least privilege: a new agent has no tool at all.
        getAs(owner, agents(org, "/" + agent + "/tools")).andExpect(jsonPath("$", hasSize(0)));

        putAs(owner, agents(org, "/" + agent + "/tools"), body("grants", List.of(
                body("toolCode", "project.files.read", "level", "READ", "approvalRequired", false),
                body("toolCode", "reports.create", "level", "WRITE", "approvalRequired", true),
                body("toolCode", "git.access", "level", "READ", "approvalRequired", true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[?(@.toolCode == 'reports.create')].approvalRequired").value(true));

        // A level above the tool's maximum, high risk without approval, unknown tools, duplicates, project scope: refused.
        Map<String, Object> tooMuch = body("toolCode", "project.files.read", "level", "WRITE", "approvalRequired", false);
        putAs(owner, agents(org, "/" + agent + "/tools"), body("grants", List.of(tooMuch))).andExpect(status().isBadRequest());
        putAs(owner, agents(org, "/" + agent + "/tools"), body("grants", List.of(
                body("toolCode", "git.access", "level", "WRITE", "approvalRequired", false)))).andExpect(status().isBadRequest());
        putAs(owner, agents(org, "/" + agent + "/tools"), body("grants", List.of(
                body("toolCode", "unknown.tool", "level", "READ", "approvalRequired", true)))).andExpect(status().isNotFound());
        putAs(owner, agents(org, "/" + agent + "/tools"), body("grants", List.of(
                body("toolCode", "code.analyze", "level", "READ", "approvalRequired", true),
                body("toolCode", "code.analyze", "level", "READ", "approvalRequired", true)))).andExpect(status().isBadRequest());
        putAs(owner, agents(org, "/" + agent + "/tools"), body("grants", List.of(
                body("toolCode", "code.analyze", "level", "READ", "approvalRequired", true, "projectId", UUID.randomUUID()))))
                .andExpect(status().isBadRequest());
        getAs(owner, agents(org, "/" + agent + "/tools")).andExpect(jsonPath("$", hasSize(3)));

        // Replacing with an empty list revokes everything; the history says what changed.
        putAs(owner, agents(org, "/" + agent + "/tools"), body("grants", List.of())).andExpect(jsonPath("$", hasSize(0)));
        String history = getAs(owner, agents(org, "/" + agent + "/history")).andExpect(jsonPath("$.content[*].action", hasItem("agent.tools.changed")))
                .andReturn().getResponse().getContentAsString();
        assertThat(history).contains("git.access").contains("revoked");
        assertThat(jdbc().queryForObject("SELECT count(*) FROM agent_tool_grants WHERE agent_id = ?", Long.class, agent)).isZero();
    }

    @Test
    void memoryIsDisabledByDefaultAndOnlyConfigurable() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Memory");
        UUID agent = agent(owner, org, "Rememberer");

        getAs(owner, agents(org, "/" + agent + "/memory-config")).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.scope").value("PROJECT")).andExpect(jsonPath("$.accessMode").value("AGENT_ONLY"))
                .andExpect(jsonPath("$.retentionDays").doesNotExist());
        putAs(owner, agents(org, "/" + agent + "/memory-config"), body("enabled", true, "scope", "ORGANIZATION",
                "accessMode", "TEAM_MEMBERS", "retentionDays", 90)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.scope").value("ORGANIZATION"))
                .andExpect(jsonPath("$.retentionDays").value(90));
        putAs(owner, agents(org, "/" + agent + "/memory-config"), body("enabled", true, "scope", "PROJECT",
                "accessMode", "AGENT_ONLY", "retentionDays", 0)).andExpect(status().isBadRequest());
        putAs(owner, agents(org, "/" + agent + "/memory-config"), body("enabled", true, "scope", "GLOBAL",
                "accessMode", "AGENT_ONLY")).andExpect(status().isBadRequest());
        putAs(owner, agents(org, "/" + agent + "/memory-config"), body("enabled", false, "scope", "PROJECT",
                "accessMode", "AGENT_ONLY")).andExpect(jsonPath("$.enabled").value(false));
        getAs(owner, agents(org, "/" + agent)).andExpect(jsonPath("$.memory.enabled").value(false))
                .andExpect(jsonPath("$.agent.configVersion").value(3));
        // Configuration only: no embedding or memory row of any kind was created.
        assertThat(jdbc().queryForObject("SELECT count(*) FROM agent_memory_configs WHERE agent_id = ?", Long.class, agent)).isOne();
    }

    private org.springframework.jdbc.core.JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
}
