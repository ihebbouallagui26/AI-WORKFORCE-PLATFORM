package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.support.TestIdentityProvider;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * The AI orchestrator's service-to-service surface: only its service account may call it, user tokens
 * and service tokens never cross over, the organization is re-validated and no secret is ever returned.
 */
class OrchestratorInternalApiIntegrationTest extends AgentFlows {

    private static final String CREDENTIAL = "valid-key-internal-secret-0123456789";

    private static String serviceToken() {
        return TestIdentityProvider.token("service-account-ai-orchestrator").serviceAccount("ai-orchestrator").sign();
    }

    private static String contextPath(UUID org, UUID agent) {
        return "/api/v1/internal/organizations/" + org + "/agents/" + agent + "/execution-context";
    }

    private ResultActions asService(String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken()));
    }

    @Test
    void theServiceAccountReadsTheAuthorizedConfigurationOfAnActiveAgentWithoutAnySecret() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Internal context");
        UUID connection = connect(owner, org, "ANTHROPIC", "Primary", CREDENTIAL);
        postAs(owner, connections(org, "/" + connection + "/test"), "{}").andExpect(status().isOk());
        UUID agent = agent(owner, org, body("name", "Context Agent", "systemInstructions", "Be precise."));
        bind(owner, org, agent, connection, "model-x", "PRIMARY");
        UUID skill = skill(owner, org, "Code review", "Dev");
        putAs(owner, agents(org, "/" + agent + "/skills"), body("skillIds", List.of(skill))).andExpect(status().isOk());
        putAs(owner, agents(org, "/" + agent + "/tools"), body("grants", List.of(
                body("toolCode", "docs.search", "level", "READ", "approvalRequired", false)))).andExpect(status().isOk());
        postAs(owner, agents(org, "/" + agent + "/activate"), "{}").andExpect(status().isOk());

        String body = asService(contextPath(org, agent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(org.toString()))
                .andExpect(jsonPath("$.agentId").value(agent.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.systemInstructions").value("Be precise."))
                .andExpect(jsonPath("$.providers", hasSize(1)))
                .andExpect(jsonPath("$.providers[0].connectionId").value(connection.toString()))
                .andExpect(jsonPath("$.providers[0].providerType").value("ANTHROPIC"))
                .andExpect(jsonPath("$.providers[0].modelId").value("model-x"))
                .andExpect(jsonPath("$.providers[0].role").value("PRIMARY"))
                .andExpect(jsonPath("$.tools", hasSize(1)))
                .andExpect(jsonPath("$.tools[0].toolCode").value("docs.search"))
                .andExpect(jsonPath("$.tools[0].level").value("READ"))
                .andExpect(jsonPath("$.skills[0]").value("Code review"))
                .andExpect(jsonPath("$.memory.enabled").value(false))
                .andReturn().getResponse().getContentAsString();
        // No credential, key fragment or secret reference of any kind.
        assertThat(body).doesNotContain(CREDENTIAL).doesNotContain("valid-key").doesNotContain("credential")
                .doesNotContain("secret");
    }

    @Test
    void connectionsThatAreNotValidAreNotOffered() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Internal connections");
        UUID agent = activeAgent(owner, org, "Atlas");
        UUID fallback = connect(owner, org, "OPENAI", "Not validated", "valid-key-" + UUID.randomUUID());
        bind(owner, org, agent, fallback, "model-y", "FALLBACK");

        asService(contextPath(org, agent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.providers", hasSize(1)))
                .andExpect(jsonPath("$.providers[0].modelId").value("claude-test-1"));
    }

    @Test
    void onlyTheServiceAccountMayCallTheInternalSurface() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Internal auth");
        UUID agent = activeAgent(owner, org, "Atlas");

        mvc.perform(get(contextPath(org, agent))).andExpect(status().isUnauthorized());
        // The most privileged human cannot use it, and a user token is not an authority.
        getAs(owner, contextPath(org, agent)).andExpect(status().isForbidden());
        // Another client's service account is not the orchestrator.
        String other = TestIdentityProvider.token("service-account-other").serviceAccount("some-other-client").sign();
        mvc.perform(get(contextPath(org, agent)).header(HttpHeaders.AUTHORIZATION, "Bearer " + other))
                .andExpect(status().isForbidden());
    }

    @Test
    void aServiceTokenNeverWorksOnUserEndpointsAndIsNeverProvisionedAsAUser() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Internal isolation");
        for (String userPath : List.of("/api/v1/me", "/api/v1/organizations", path(org, "/agents"))) {
            mvc.perform(get(userPath).header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken()))
                    .andExpect(status().isUnauthorized());
        }
        // Still unknown to the identity tables: calling /me with it did not create an account.
        JsonNode members = read(getAs(owner, path(org, "/members")).andExpect(status().isOk()));
        assertThat(members.get("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void theOrganizationIsRevalidatedAndOnlyActiveAgentsAreServed() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Org one");
        UUID other = createOrganization(owner, "Org two");
        UUID active = activeAgent(owner, org, "Atlas");
        UUID draft = agent(owner, org, "Drafty");

        asService(contextPath(other, active)).andExpect(status().isNotFound());   // right agent, wrong organization
        asService(contextPath(org, UUID.randomUUID())).andExpect(status().isNotFound());
        asService(contextPath(org, draft)).andExpect(status().isConflict());      // not active

        postAs(owner, agents(org, "/" + active + "/pause"), "{}").andExpect(status().isOk());
        asService(contextPath(org, active)).andExpect(status().isConflict());
        postAs(owner, agents(org, "/" + active + "/archive"), "{}").andExpect(status().isOk());
        asService(contextPath(org, active)).andExpect(status().isConflict());
    }

    @Test
    void theInternalSurfaceIsReadOnly() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Internal readonly");
        UUID agent = activeAgent(owner, org, "Atlas");
        for (var builder : List.of(post(contextPath(org, agent)), put(contextPath(org, agent)),
                delete(contextPath(org, agent)))) {
            mvc.perform(builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken()))
                    .andExpect(status().isMethodNotAllowed());
        }
    }
}
