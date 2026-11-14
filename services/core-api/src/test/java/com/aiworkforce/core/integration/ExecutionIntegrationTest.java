package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.support.RecordingOrchestrator;
import com.aiworkforce.core.support.TestIdentityProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Supervised agent executions: launch rules, permissions, tenant isolation, the guarded state machine
 * driven by the orchestrator's service account, single-use credential issuance and safe failures.
 * The orchestrator itself is replaced by a recording stub: nothing here calls a real provider.
 */
class ExecutionIntegrationTest extends AgentFlows {

    private static final String SECRET = "valid-key-c1-very-secret-0123456789";

    @Autowired
    private RecordingOrchestrator.Gateway orchestrator;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetOrchestrator() {
        orchestrator.reset();
    }

    // ------------------------------------------------------------------ helpers

    private static String serviceToken() {
        return TestIdentityProvider.token("service-account-ai-orchestrator").serviceAccount("ai-orchestrator").sign();
    }

    private ResultActions service(MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken()));
    }

    private static String internal(UUID org, UUID execution, String action) {
        return "/api/v1/internal/organizations/" + org + "/executions/" + execution + "/" + action;
    }

    private ResultActions serviceCall(UUID org, UUID execution, String action, Object body) throws Exception {
        return service(post(internal(org, execution, action)).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private static String executions(UUID org, String suffix) {
        return path(org, "/executions" + suffix);
    }

    /** An ACTIVE agent whose primary Anthropic connection holds {@link #SECRET}. */
    private UUID agentWithSecret(TestUser owner, UUID org, String name) throws Exception {
        UUID connection = connect(owner, org, "ANTHROPIC", name + " key", SECRET + "-" + UUID.randomUUID());
        postAs(owner, connections(org, "/" + connection + "/test"), "{}").andExpect(status().isOk());
        UUID agent = agent(owner, org, body("name", name, "systemInstructions", "Be precise."));
        bind(owner, org, agent, connection, "model-x", "PRIMARY");
        postAs(owner, agents(org, "/" + agent + "/activate"), "{}").andExpect(status().isOk());
        return agent;
    }

    private UUID launch(TestUser user, UUID org, UUID agent, String task) throws Exception {
        return id(read(postAs(user, agents(org, "/" + agent + "/executions"), body("task", task))
                .andExpect(status().isAccepted())));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).as("condition within 5 s").isTrue();
    }

    // ------------------------------------------------------------------ launch

    @Test
    void anAuthorizedUserLaunchesAnExecutionThatIsRecordedAndDispatchedWithoutAnySecret() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Exec org");
        UUID agent = agentWithSecret(owner, org, "Atlas");

        String response = postAs(owner, agents(org, "/" + agent + "/executions"),
                body("task", "  Summarize the Q3 plan.  ")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.task").value("Summarize the Q3 plan."))
                .andExpect(jsonPath("$.agentId").value(agent.toString()))
                .andExpect(jsonPath("$.providerType").value("ANTHROPIC"))
                .andExpect(jsonPath("$.modelId").value("model-x"))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        UUID execution = UUID.fromString(read(getAs(owner, executions(org, "?size=1")))
                .get("content").get(0).get("id").asString());

        // After commit, the orchestrator is asked to run it (identifiers only).
        await(() -> orchestrator.dispatched().size() == 1);
        assertThat(orchestrator.dispatched().getFirst()).isEqualTo(new RecordingOrchestrator.Dispatched(org, execution));

        getAs(owner, executions(org, "/" + execution)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(response).doesNotContain("valid-key").doesNotContain("credential").doesNotContain("secret");
        // The launch is audited without the instruction.
        List<String> audit = jdbc.queryForList("SELECT details::text FROM audit_events WHERE action = 'agent.execution.requested'"
                + " AND organization_id = ?", String.class, org);
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst()).doesNotContain("Summarize").doesNotContain("valid-key");
    }

    @Test
    void onlyEligibleAgentsAndProvidersCanBeExecuted() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Eligibility");
        UUID draft = agent(owner, org, "Drafty");
        postAs(owner, agents(org, "/" + draft + "/executions"), body("task", "hi")).andExpect(status().isConflict());

        UUID active = agentWithSecret(owner, org, "Atlas");
        postAs(owner, agents(org, "/" + active + "/executions"), body("task", "   ")).andExpect(status().isBadRequest());
        postAs(owner, agents(org, "/" + active + "/executions"), body("task", "x".repeat(8001))).andExpect(status().isBadRequest());

        // Paused agents cannot run.
        postAs(owner, agents(org, "/" + active + "/pause"), "{}").andExpect(status().isOk());
        postAs(owner, agents(org, "/" + active + "/executions"), body("task", "hi")).andExpect(status().isConflict());
        postAs(owner, agents(org, "/" + active + "/activate"), "{}").andExpect(status().isOk());

        // A primary connection that is no longer VALID (credential replaced, not re-tested) blocks the launch.
        UUID replaced = agentWithSecret(owner, org, "Replaced");
        UUID connection = id(read(getAs(owner, agents(org, "/" + replaced))).get("providers").get(0).get("connection"));
        putAs(owner, connections(org, "/" + connection + "/credential"), body("credential", "valid-key-replaced-again"))
                .andExpect(status().isOk());
        postAs(owner, agents(org, "/" + replaced + "/executions"), body("task", "hi")).andExpect(status().isConflict());

        // OpenAI/Gemini adapters exist but are not enabled for execution in C1.
        UUID openAi = agent(owner, org, "Open");
        UUID openAiConnection = connect(owner, org, "OPENAI", "OpenAI key", "valid-key-" + UUID.randomUUID());
        postAs(owner, connections(org, "/" + openAiConnection + "/test"), "{}").andExpect(status().isOk());
        bind(owner, org, openAi, openAiConnection, "model-o", "PRIMARY");
        postAs(owner, agents(org, "/" + openAi + "/activate"), "{}").andExpect(status().isOk());
        postAs(owner, agents(org, "/" + openAi + "/executions"), body("task", "hi")).andExpect(status().isConflict());
        assertThat(orchestrator.dispatched()).isEmpty();
    }

    @Test
    void permissionsAreEnforcedByTheServer() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Execution rbac");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        TestUser member = newUser("member");
        UUID memberShip = join(owner, org, member, "MEMBER");
        TestUser runner = newUser("runner");
        giveCustomRole(owner, org, join(owner, org, runner, "MEMBER"), "Runner", List.of("agent.read", "agent.execute"));
        TestUser reader = newUser("reader");
        giveCustomRole(owner, org, join(owner, org, reader, "MEMBER"), "Reader", List.of("agent.read", "agent.execution.read"));

        // Plain members have neither permission (least privilege, not widened by the migration).
        postAs(member, agents(org, "/" + agent + "/executions"), body("task", "hi")).andExpect(status().isForbidden());
        getAs(member, executions(org, "")).andExpect(status().isForbidden());
        assertThat(memberShip).isNotNull();

        UUID execution = launch(runner, org, agent, "Run as runner");
        // The runner may launch but not read; the reader may read but not launch.
        getAs(runner, executions(org, "/" + execution)).andExpect(status().isForbidden());
        postAs(reader, agents(org, "/" + agent + "/executions"), body("task", "hi")).andExpect(status().isForbidden());
        getAs(reader, executions(org, "/" + execution)).andExpect(status().isOk());
        getAs(reader, agents(org, "/" + agent + "/executions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void anotherOrganizationCanNeverLaunchReadOrDriveAnExecution() throws Exception {
        TestUser alice = newUser("alice");
        TestUser bob = newUser("bob");
        UUID orgA = createOrganization(alice, "Org A");
        UUID orgB = createOrganization(bob, "Org B");
        UUID agentA = agentWithSecret(alice, orgA, "Atlas");
        UUID executionA = launch(alice, orgA, agentA, "Secret plan");

        // Bob is not a member of A: 404 whatever he tries, including with his own organization's path.
        postAs(bob, agents(orgA, "/" + agentA + "/executions"), body("task", "hi")).andExpect(status().isNotFound());
        getAs(bob, executions(orgA, "/" + executionA)).andExpect(status().isNotFound());
        getAs(bob, executions(orgA, "")).andExpect(status().isNotFound());
        getAs(bob, executions(orgB, "/" + executionA)).andExpect(status().isNotFound());
        postAs(bob, agents(orgB, "/" + agentA + "/executions"), body("task", "hi")).andExpect(status().isNotFound());
        getAs(bob, agents(orgB, "/" + agentA + "/executions")).andExpect(status().isNotFound());
        getAs(bob, executions(orgB, "")).andExpect(jsonPath("$.totalElements").value(0));

        // The service account also revalidates the pair (organization, execution).
        serviceCall(orgB, executionA, "start", Map.of()).andExpect(status().isNotFound());
        serviceCall(orgB, executionA, "credential", Map.of()).andExpect(status().isNotFound());
        serviceCall(orgB, executionA, "complete", body("result", "x")).andExpect(status().isNotFound());
        getAs(alice, executions(orgA, "/" + executionA)).andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void historyIsPaginatedNewestFirstAndLimitsConcurrentRuns() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "History");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        UUID other = agentWithSecret(owner, org, "Other");

        UUID first = launch(owner, org, agent, "one");
        UUID second = launch(owner, org, agent, "two");
        UUID third = launch(owner, org, other, "three");
        // Default limit: 3 open executions per organization.
        postAs(owner, agents(org, "/" + agent + "/executions"), body("task", "four")).andExpect(status().isConflict());

        getAs(owner, executions(org, "?size=2&page=0")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content", hasSize(2))).andExpect(jsonPath("$.content[0].id").value(third.toString()));
        getAs(owner, agents(org, "/" + agent + "/executions")).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(second.toString()))
                .andExpect(jsonPath("$.content[1].id").value(first.toString()));
        getAs(owner, executions(org, "?status=SUCCEEDED")).andExpect(jsonPath("$.totalElements").value(0));
        getAs(owner, executions(org, "?size=500")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ orchestrator-driven lifecycle

    @Test
    void theStateMachineIsEnforcedAndCompletionCannotBeDuplicated() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "State machine");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        UUID execution = launch(owner, org, agent, "Write a haiku");

        // Not running yet: no credential, no completion.
        serviceCall(org, execution, "credential", Map.of()).andExpect(status().isConflict());
        serviceCall(org, execution, "complete", body("result", "early")).andExpect(status().isConflict());

        serviceCall(org, execution, "start", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.task").value("Write a haiku")).andExpect(jsonPath("$.agentId").value(agent.toString()));
        serviceCall(org, execution, "start", Map.of()).andExpect(status().isConflict());   // exactly once
        getAs(owner, executions(org, "/" + execution)).andExpect(jsonPath("$.status").value("RUNNING"));

        serviceCall(org, execution, "complete",
                body("result", "Autumn moonlight", "inputTokens", 12, "outputTokens", 7, "model", "model-x-2025"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result").value("Autumn moonlight")).andExpect(jsonPath("$.inputTokens").value(12))
                .andExpect(jsonPath("$.outputTokens").value(7)).andExpect(jsonPath("$.modelId").value("model-x-2025"));
        // Terminal states are final: no second completion, no failure after success, no restart.
        serviceCall(org, execution, "complete", body("result", "again")).andExpect(status().isConflict());
        serviceCall(org, execution, "fail", body("errorCode", "LATE", "message", "late")).andExpect(status().isConflict());
        serviceCall(org, execution, "start", Map.of()).andExpect(status().isConflict());
        getAs(owner, executions(org, "/" + execution)).andExpect(jsonPath("$.result").value("Autumn moonlight"))
                .andExpect(jsonPath("$.completedAt").exists()).andExpect(jsonPath("$.errorCode").doesNotExist());
    }

    @Test
    void failuresAreRecordedWithASafeCodeAndMessage() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Failures");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        UUID running = launch(owner, org, agent, "task");
        serviceCall(org, running, "start", Map.of()).andExpect(status().isOk());

        serviceCall(org, running, "fail", body("errorCode", "PROVIDER_RATE_LIMIT",
                "message", "The provider is rate limiting requests.\u0007\nSecond line")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.errorCode").value("PROVIDER_RATE_LIMIT"));
        getAs(owner, executions(org, "/" + running)).andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").value("The provider is rate limiting requests. Second line"));

        // A failure may also happen before the run started (e.g. nothing could pick it up); odd codes are normalized.
        UUID pending = launch(owner, org, agent, "task 2");
        serviceCall(org, pending, "fail", body("errorCode", "not a valid code!", "message", "x".repeat(900)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errorCode").value("EXECUTION_FAILED"));
        assertThat(jdbc.queryForObject("SELECT length(error_message) FROM agent_executions WHERE id = ?", Integer.class, pending))
                .isLessThanOrEqualTo(300);
        serviceCall(org, pending, "start", Map.of()).andExpect(status().isConflict());
    }

    @Test
    void theCredentialIsIssuedOnceToTheOrchestratorOnlyAndNeverStoredOrExposed() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Credential");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        UUID execution = launch(owner, org, agent, "task");
        serviceCall(org, execution, "start", Map.of()).andExpect(status().isOk());

        // Users (even owners) cannot reach the endpoint; unauthenticated callers neither.
        postAs(owner, internal(org, execution, "credential"), Map.of()).andExpect(status().isForbidden());
        mvc.perform(post(internal(org, execution, "credential"))).andExpect(status().isUnauthorized());
        // A service account of another client is not the orchestrator.
        String other = TestIdentityProvider.token("service-account-other").serviceAccount("some-other-client").sign();
        mvc.perform(post(internal(org, execution, "credential")).header(HttpHeaders.AUTHORIZATION, "Bearer " + other))
                .andExpect(status().isForbidden());

        String issued = serviceCall(org, execution, "credential", Map.of()).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.providerType").value("ANTHROPIC")).andExpect(jsonPath("$.modelId").value("model-x"))
                .andExpect(jsonPath("$.credential").value(startsWith(SECRET)))
                .andReturn().getResponse().getContentAsString();
        String secret = JsonPath.secretOf(issued);
        serviceCall(org, execution, "complete", body("result", "ok")).andExpect(status().isOk());
        // Single use.
        serviceCall(org, execution, "credential", Map.of()).andExpect(status().isConflict());

        // The secret is in no execution view, no execution row and no audit record.
        String view = getAs(owner, executions(org, "/" + execution)).andReturn().getResponse().getContentAsString();
        assertThat(view).doesNotContain(secret).doesNotContain("valid-key");
        for (String column : jdbc.queryForList("SELECT row_to_json(e)::text FROM agent_executions e WHERE id = ?", String.class, execution)) {
            assertThat(column).doesNotContain(secret).doesNotContain("valid-key");
        }
        for (String details : jdbc.queryForList("SELECT details::text FROM audit_events WHERE organization_id = ?", String.class, org)) {
            assertThat(details).doesNotContain(secret).doesNotContain("valid-key");
        }
    }

    @Test
    void theCredentialIsRefusedWhenTheAgentOrItsProviderChangedAfterLaunch() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Changed");
        UUID agent = agentWithSecret(owner, org, "Atlas");

        UUID paused = launch(owner, org, agent, "task");
        serviceCall(org, paused, "start", Map.of()).andExpect(status().isOk());
        postAs(owner, agents(org, "/" + agent + "/pause"), "{}").andExpect(status().isOk());
        serviceCall(org, paused, "credential", Map.of()).andExpect(status().isConflict());
        postAs(owner, agents(org, "/" + agent + "/activate"), "{}").andExpect(status().isOk());

        // The connection stops being VALID (credential replaced and not re-tested): fail closed.
        UUID second = launch(owner, org, agent, "task 2");
        serviceCall(org, second, "start", Map.of()).andExpect(status().isOk());
        UUID connection = id(read(getAs(owner, agents(org, "/" + agent))).get("providers").get(0).get("connection"));
        putAs(owner, connections(org, "/" + connection + "/credential"), body("credential", "valid-key-rotated-1234"))
                .andExpect(status().isOk());
        serviceCall(org, second, "credential", Map.of()).andExpect(status().isConflict());
        // A refused issuance does not consume the single use.
        assertThat(jdbc.queryForObject("SELECT credential_resolved_at FROM agent_executions WHERE id = ?",
                java.sql.Timestamp.class, second)).isNull();
    }

    @Test
    void userAndServiceTokensStayInTheirOwnWorlds() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Worlds");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        // A service token cannot launch or read executions through the user API.
        service(post(agents(org, "/" + agent + "/executions")).contentType(MediaType.APPLICATION_JSON).content("{\"task\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
        service(get(executions(org, ""))).andExpect(status().isUnauthorized());
        // A user token cannot drive an execution.
        UUID execution = launch(owner, org, agent, "task");
        postAs(owner, internal(org, execution, "start"), Map.of()).andExpect(status().isForbidden());
        postAs(owner, internal(org, execution, "complete"), body("result", "x")).andExpect(status().isForbidden());
        getAs(owner, executions(org, "/" + execution)).andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void anUnreachableOrchestratorFailsTheExecutionImmediatelyAndSafely() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Unreachable");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        orchestrator.unreachable(true);
        UUID execution = launch(owner, org, agent, "task");

        await(() -> {
            try {
                return "FAILED".equals(read(getAs(owner, executions(org, "/" + execution))).get("status").asString());
            } catch (Exception e) {
                return false;
            }
        });
        JsonNode failed = read(getAs(owner, executions(org, "/" + execution)).andExpect(status().isOk()));
        assertThat(failed.get("errorCode").asString()).isEqualTo("ORCHESTRATOR_UNAVAILABLE");
        assertThat(failed.get("errorMessage").asString()).doesNotContain("unreachable").doesNotContain("Exception");
    }

    /** Tiny helper: the secret is the credential field of the issued JSON (kept out of the assertions' messages). */
    private static final class JsonPath {

        static String secretOf(String json) {
            int start = json.indexOf("\"credential\":\"") + "\"credential\":\"".length();
            return json.substring(start, json.indexOf('"', start));
        }
    }

    // ------------------------------------------------------------------ F1: dispatch errors never overwrite a started run

    private void startAsOrchestrator(RecordingOrchestrator.Dispatched request) {
        try {
            serviceCall(request.organizationId(), request.executionId(), "start", Map.of()).andExpect(status().isOk());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String statusOf(TestUser user, UUID org, UUID execution) throws Exception {
        return read(getAs(user, executions(org, "/" + execution))).get("status").asString();
    }

    @Test
    void aLostDispatchAnswerNeverFailsAnExecutionThatTheOrchestratorAlreadyStarted() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Lost answer");
        UUID agent = agentWithSecret(owner, org, "Atlas");

        // The orchestrator accepts the hand-over and starts the run, but the HTTP answer never comes back.
        orchestrator.unreachable(true);
        orchestrator.onArrival(this::startAsOrchestrator);
        UUID execution = launch(owner, org, agent, "A run that is alive");
        await(() -> orchestrator.attempts() == 1);
        Thread.sleep(500);                       // the dispatcher has processed the error by now

        JsonNode running = read(getAs(owner, executions(org, "/" + execution)).andExpect(status().isOk()));
        assertThat(running.get("status").asString()).isEqualTo("RUNNING");
        assertThat(running.get("errorCode").isNull()).isTrue();

        // The late result is accepted: nothing was lost and nothing was double-closed.
        serviceCall(org, execution, "complete", body("result", "Finished after the lost answer", "inputTokens", 5, "outputTokens", 3))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        getAs(owner, executions(org, "/" + execution)).andExpect(jsonPath("$.result").value("Finished after the lost answer"))
                .andExpect(jsonPath("$.errorCode").doesNotExist());
    }

    @Test
    void aDispatchErrorNeverOverwritesAFinalExecution() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Final states");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        orchestrator.gate(gate);                 // the hand-over waits, so the run can finish first
        orchestrator.unreachable(true);

        UUID succeeded = launch(owner, org, agent, "will succeed");
        UUID failed = launch(owner, org, agent, "will fail");
        await(() -> orchestrator.attempts() == 2);
        serviceCall(org, succeeded, "start", Map.of()).andExpect(status().isOk());
        serviceCall(org, succeeded, "complete", body("result", "done")).andExpect(status().isOk());
        serviceCall(org, failed, "start", Map.of()).andExpect(status().isOk());
        serviceCall(org, failed, "fail", body("errorCode", "PROVIDER_TIMEOUT", "message", "The provider did not answer in time."))
                .andExpect(status().isOk());

        gate.countDown();                        // now both hand-overs report an error
        Thread.sleep(700);
        assertThat(statusOf(owner, org, succeeded)).isEqualTo("SUCCEEDED");
        getAs(owner, executions(org, "/" + failed)).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("PROVIDER_TIMEOUT"));
    }

    @Test
    void aGenuinelyPendingExecutionIsStillFailedWhenTheHandOverFails() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Still pending");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        orchestrator.unreachable(true);          // nothing reaches the orchestrator: it never started
        UUID execution = launch(owner, org, agent, "never delivered");
        await(() -> "FAILED".equals(statusOfQuietly(owner, org, execution)));
        getAs(owner, executions(org, "/" + execution)).andExpect(jsonPath("$.errorCode").value("ORCHESTRATOR_UNAVAILABLE"))
                .andExpect(jsonPath("$.completedAt").exists());
        // It is final: the orchestrator cannot start it afterwards.
        serviceCall(org, execution, "start", Map.of()).andExpect(status().isConflict());
    }

    private String statusOfQuietly(TestUser user, UUID org, UUID execution) {
        try {
            return statusOf(user, org, execution);
        } catch (Exception e) {
            return "";
        }
    }

}
