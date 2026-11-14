package com.aiworkforce.core.integration;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Reusable API flows for AI agent tests (providers, agents, configuration). */
abstract class AgentFlows extends WorkforceFlows {

    protected static String agents(UUID org, String suffix) {
        return path(org, "/agents" + suffix);
    }

    protected static String connections(UUID org, String suffix) {
        return path(org, "/providers/connections" + suffix);
    }

    /** Creates a provider connection (PENDING until tested). */
    protected UUID connect(TestUser user, UUID org, String type, String name, String credential) throws Exception {
        return id(read(postAs(user, connections(org, ""),
                body("providerType", type, "displayName", name, "credential", credential))
                .andExpect(status().isCreated())));
    }

    /** Creates a connection and tests it against the fake provider: VALID with a credential starting "valid". */
    protected UUID validConnection(TestUser user, UUID org, String name) throws Exception {
        UUID connection = connect(user, org, "ANTHROPIC", name, "valid-key-" + UUID.randomUUID());
        postAs(user, connections(org, "/" + connection + "/test"), "{}").andExpect(status().isOk());
        return connection;
    }

    protected UUID agent(TestUser user, UUID org, String name) throws Exception {
        return id(read(postAs(user, agents(org, ""), body("name", name)).andExpect(status().isCreated())).get("agent"));
    }

    protected UUID agent(TestUser user, UUID org, Map<String, Object> request) throws Exception {
        return id(read(postAs(user, agents(org, ""), request).andExpect(status().isCreated())).get("agent"));
    }

    protected JsonNode bind(TestUser user, UUID org, UUID agent, UUID connection, String model, String role)
            throws Exception {
        return read(postAs(user, agents(org, "/" + agent + "/providers"),
                body("connectionId", connection, "modelId", model, "role", role)).andExpect(status().isCreated()));
    }

    /** An ACTIVE agent: tested connection, primary binding, activated. */
    protected UUID activeAgent(TestUser user, UUID org, String name) throws Exception {
        UUID agent = agent(user, org, name);
        bind(user, org, agent, validConnection(user, org, name + " connection"), "claude-test-1", "PRIMARY");
        postAs(user, agents(org, "/" + agent + "/activate"), "{}").andExpect(status().isOk());
        return agent;
    }

    protected UUID skill(TestUser user, UUID org, String name, String category) throws Exception {
        return id(read(postAs(user, path(org, "/skills"), body("name", name, "category", category))
                .andExpect(status().isCreated())));
    }

    protected void addAgentToTeam(TestUser user, UUID org, UUID team, UUID agent) throws Exception {
        postAs(user, path(org, "/teams/" + team + "/agents"), body("agentId", agent)).andExpect(status().isCreated());
    }

    protected static List<Map<String, Object>> grants(Map<String, Object>... grants) {
        return List.of(grants);
    }
}
