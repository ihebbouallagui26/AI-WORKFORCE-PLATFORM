package com.aiworkforce.core.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.support.TestIdentityProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** Reusable flows for tool execution and approval tests (the orchestrator is played through the internal API). */
abstract class ToolFlows extends AgentFlows {

    protected static final String SECRET = "valid-key-c2-very-secret-0123456789";

    protected static String serviceToken() {
        return TestIdentityProvider.token("service-account-ai-orchestrator").serviceAccount("ai-orchestrator").sign();
    }

    protected ResultActions asService(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken()));
    }

    protected static String run(UUID org, UUID execution, String suffix) {
        return "/api/v1/internal/organizations/" + org + "/executions/" + execution + suffix;
    }

    protected ResultActions serviceCall(UUID org, UUID execution, String suffix, Object body) throws Exception {
        return asService(post(run(org, execution, suffix)).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        org.assertj.core.api.Assertions.assertThat(condition.getAsBoolean()).as("condition within 10 s").isTrue();
    }

    /** An ACTIVE agent whose primary Anthropic connection holds a secret. */
    protected UUID agentWithSecret(TestUser owner, UUID org, String name) throws Exception {
        UUID connection = connect(owner, org, "ANTHROPIC", name + " key", SECRET + "-" + UUID.randomUUID());
        postAs(owner, connections(org, "/" + connection + "/test"), "{}").andExpect(status().isOk());
        UUID agent = agent(owner, org, body("name", name, "systemInstructions", "Be precise."));
        bind(owner, org, agent, connection, "model-x", "PRIMARY");
        postAs(owner, agents(org, "/" + agent + "/activate"), "{}").andExpect(status().isOk());
        return agent;
    }

    /** Replaces the tool grants of the agent. Each grant is (code, level, approvalRequired). */
    protected void grant(TestUser owner, UUID org, UUID agent, Object[]... grants) throws Exception {
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (Object[] g : grants) {
            list.add(body("toolCode", g[0], "level", g[1], "approvalRequired", g[2]));
        }
        putAs(owner, agents(org, "/" + agent + "/tools"), body("grants", list)).andExpect(status().isOk());
    }

    protected UUID launch(TestUser user, UUID org, UUID agent, String task) throws Exception {
        return id(read(postAs(user, agents(org, "/" + agent + "/executions"), body("task", task))
                .andExpect(status().isAccepted())));
    }

    /** Launches an execution and plays the orchestrator's first step: PENDING to RUNNING. */
    protected UUID runningExecution(TestUser user, UUID org, UUID agent) throws Exception {
        UUID execution = launch(user, org, agent, "Use your tools");
        serviceCall(org, execution, "/start", Map.of()).andExpect(status().isOk());
        return execution;
    }

    /** The agent asks for a tool; returns the Core API's decision. */
    protected JsonNode requestTool(UUID org, UUID execution, String callId, String toolCode, Object arguments) throws Exception {
        return read(serviceCall(org, execution, "/tool-calls",
                body("toolCallId", callId, "toolCode", toolCode, "arguments", arguments, "reason", "Because the task needs it"))
                .andExpect(status().isOk()));
    }

    protected static Map<String, Object> text(String value) {
        return Map.of("text", value);
    }
}
