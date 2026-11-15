package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.agent.MaintenanceProbe;
import com.aiworkforce.core.agent.WorkflowProbe;
import com.aiworkforce.core.support.RecordingOrchestrator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Workflow reliability (C3.3) against the REAL persistence: failure classification, bounded durable retries, failure policies,
 * manual recovery, step timeouts and the usage summary. The orchestrator is played through its internal API; no provider is
 * involved. Retry delays are long (about 30 s) so that "time passing" is simulated explicitly in the database.
 */
@Import({WorkflowProbe.class, MaintenanceProbe.class})
@TestPropertySource(properties = {"app.workflows.maintenance-ms=3600000", "app.executions.approval-maintenance-ms=3600000",
        "app.workflows.retry-base-delay=PT30S", "app.workflows.retry-max-delay=PT30S"})
class WorkflowReliabilityIntegrationTest extends ToolFlows {

    private static final Object[] ECHO = {"diagnostic.echo", "READ", false};

    @Autowired
    private RecordingOrchestrator.Gateway orchestrator;

    @Autowired
    private WorkflowProbe.Probe probe;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        orchestrator.reset();
    }

    private record Team(TestUser owner, UUID org, UUID a, UUID b, UUID c) {
    }

    private Team team() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Reliability " + UUID.randomUUID().toString().substring(0, 8));
        return new Team(owner, org, agentWithSecret(owner, org, "Researcher"), agentWithSecret(owner, org, "Writer"),
                agentWithSecret(owner, org, "Reviewer"));
    }

    private static String wf(UUID org, String suffix) {
        return path(org, "/workflows" + suffix);
    }

    private static String runs(UUID org, String suffix) {
        return path(org, "/workflow-runs" + suffix);
    }

    private UUID pipeline(Team t, Map<String, Object> settings, UUID... stepAgents) throws Exception {
        Map<String, Object> create = new HashMap<>(settings);
        create.put("name", "Reliable");
        UUID workflow = id(read(postAs(t.owner(), wf(t.org(), ""), create).andExpect(status().isCreated())));
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"),
                body("agentIds", new ArrayList<>(new LinkedHashSet<>(List.of(stepAgents))))).andExpect(status().isOk());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < stepAgents.length; i++) {
            steps.add(body("name", "Step " + (i + 1), "agentId", stepAgents[i], "instruction", "Do part " + (i + 1)));
        }
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/steps"), body("steps", steps)).andExpect(status().isOk());
        return workflow;
    }

    private UUID start(Team t, UUID workflow) throws Exception {
        return id(read(postAs(t.owner(), wf(t.org(), "/" + workflow + "/runs"), body("objective", "Reliable objective")).andExpect(status().isAccepted())));
    }

    private JsonNode run(Team t, UUID run) throws Exception {
        return read(getAs(t.owner(), runs(t.org(), "/" + run)).andExpect(status().isOk()));
    }

    private String runStatus(Team t, UUID run) throws Exception {
        return run(t, run).get("status").asString();
    }

    private List<JsonNode> steps(Team t, UUID run) throws Exception {
        JsonNode content = read(getAs(t.owner(), runs(t.org(), "/" + run + "/steps")).andExpect(status().isOk())).get("content");
        List<JsonNode> list = new ArrayList<>();
        for (int i = 0; i < content.size(); i++) {
            list.add(content.get(i));
        }
        return list;
    }

    private UUID execution(Team t, UUID run, int index) throws Exception {
        return UUID.fromString(steps(t, run).get(index).get("executionId").asString());
    }

    private void pass() {
        probe.newWorker().advanceOpenRuns();
    }

    private void fail(UUID org, UUID execution, String code) throws Exception {
        serviceCall(org, execution, "/start", Map.of()).andExpect(status().isOk());
        serviceCall(org, execution, "/fail", body("errorCode", code, "message", "simulated")).andExpect(status().isOk());
    }

    private void succeed(UUID org, UUID execution, String result, int input, int output) throws Exception {
        serviceCall(org, execution, "/start", Map.of()).andExpect(status().isOk());
        serviceCall(org, execution, "/complete", body("result", result, "inputTokens", input, "outputTokens", output, "model", "m"))
                .andExpect(status().isOk());
    }

    /** Simulates the passage of time: the scheduled retries of the run are now due. */
    private void retriesAreDue(UUID run) {
        jdbc.update("UPDATE workflow_steps SET next_attempt_at = now() - interval '1 second' WHERE run_id = ? AND next_attempt_at IS NOT NULL", run);
    }

    private String task(UUID execution) {
        return jdbc.queryForObject("SELECT task FROM agent_executions WHERE id = ?", String.class, execution);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ------------------------------------------------------------------ automatic retry

    @Test
    void aTransientProviderFailureIsRetriedAfterADurableBackoffInANewExecutionAndTheWorkflowSucceeds() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of("maxRetriesPerStep", 2), t.a(), t.b()));
        UUID first = execution(t, run, 0);
        fail(t.org(), first, "PROVIDER_UNAVAILABLE");
        pass();

        // The retry is scheduled, not launched: the due time is stored in PostgreSQL.
        List<JsonNode> waiting = steps(t, run);
        assertThat(waiting).hasSize(1);
        assertThat(waiting.get(0).get("failureCategory").asString()).isEqualTo("TRANSIENT_PROVIDER_FAILURE");
        assertThat(waiting.get(0).get("retrySafe").asBoolean()).isTrue();
        assertThat(waiting.get(0).get("nextAttemptAt").asString()).isNotBlank();
        assertThat(runStatus(t, run)).isEqualTo("RUNNING");
        pass();                                                   // a repeated pass before the due time changes nothing
        assertThat(steps(t, run)).hasSize(1);

        retriesAreDue(run);                                       // time passes (and a brand-new worker has no memory of any of it)
        pass();
        List<JsonNode> after = steps(t, run);
        assertThat(after).hasSize(2);
        assertThat(after.get(1).get("attempt").asInt()).isEqualTo(2);
        assertThat(after.get(1).get("launchReason").asString()).isEqualTo("AUTO_RETRY");
        UUID second = execution(t, run, 1);
        assertThat(second).isNotEqualTo(first);
        assertThat(after.get(0).get("status").asString()).isEqualTo("FAILED");            // the first attempt is kept, never overwritten
        assertThat(count("SELECT count(*) FROM agent_executions WHERE id IN (?, ?)", first, second)).isEqualTo(2);

        succeed(t.org(), second, "R1", 100, 40);
        pass();
        UUID third = execution(t, run, 2);
        succeed(t.org(), third, "R2", 50, 10);
        pass();
        JsonNode done = run(t, run);
        assertThat(done.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("tokensUsed").asInt()).isEqualTo(200);

        JsonNode summary = read(getAs(t.owner(), runs(t.org(), "/" + run + "/summary")).andExpect(status().isOk()));
        assertThat(summary.get("attempts").asInt()).isEqualTo(3);
        assertThat(summary.get("automaticRetries").asInt()).isEqualTo(1);
        assertThat(summary.get("failedAttempts").asInt()).isEqualTo(1);
        assertThat(summary.get("failuresByCategory").get("TRANSIENT_PROVIDER_FAILURE").asInt()).isEqualTo(1);
        assertThat(summary.get("inputTokens").asLong() + summary.get("outputTokens").asLong()).isEqualTo(200);
        assertThat(summary.get("workerTokens").asLong()).isEqualTo(200);
        assertThat(summary.get("coordinatorTokens").asLong()).isZero();
        assertThat(summary.get("tokensRemaining").asLong()).isEqualTo(done.get("tokenBudget").asLong() - 200);
        assertThat(summary.get("attemptList")).hasSize(3);
        assertThat(count("SELECT count(*) FROM audit_events WHERE organization_id = ? AND action = 'workflow.step.retry-scheduled'", t.org())).isEqualTo(1);
    }

    @Test
    void theRetryBudgetIsBoundedAndTheAttemptsStayAuditable() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of("maxRetriesPerStep", 1, "failurePolicy", "RETRY"), t.a()));
        fail(t.org(), execution(t, run, 0), "PROVIDER_TIMEOUT");
        pass();
        retriesAreDue(run);
        pass();
        assertThat(steps(t, run)).hasSize(2);
        fail(t.org(), execution(t, run, 1), "PROVIDER_TIMEOUT");   // the single allowed retry also fails
        pass();
        retriesAreDue(run);
        pass();
        assertThat(steps(t, run)).hasSize(2);                      // no third attempt
        JsonNode failed = run(t, run);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("errorCode").asString()).isEqualTo("STEP_FAILED");
    }

    @Test
    void permanentAndAuthorizationFailuresAreNeverRetriedAutomatically() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of("maxRetriesPerStep", 2), t.a()));
        fail(t.org(), execution(t, run, 0), "PROVIDER_AUTHENTICATION");
        pass();
        retriesAreDue(run);
        pass();
        assertThat(steps(t, run)).hasSize(1);
        assertThat(steps(t, run).get(0).get("failureCategory").asString()).isEqualTo("PERMANENT_PROVIDER_FAILURE");
        assertThat(steps(t, run).get(0).get("nextAttemptAt").isNull()).isTrue();
        assertThat(runStatus(t, run)).isEqualTo("FAILED");
    }

    @Test
    void aRetryThatWouldPassTheWorkflowDeadlineIsNotScheduled() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of("maxRetriesPerStep", 2), t.a()));
        jdbc.update("UPDATE workflow_runs SET deadline_at = now() + interval '5 seconds' WHERE id = ?", run);
        fail(t.org(), execution(t, run, 0), "PROVIDER_UNAVAILABLE");
        pass();
        assertThat(steps(t, run)).hasSize(1);
        assertThat(steps(t, run).get(0).get("nextAttemptAt").isNull()).isTrue();
        assertThat(runStatus(t, run)).isEqualTo("FAILED");
    }

    // ------------------------------------------------------------------ unknown outcomes

    @Test
    void anExternalActionWhoseOutcomeIsLostIsNeverReplayedAndTheWorkflowSaysSo() throws Exception {
        Team t = team();
        grant(t.owner(), t.org(), t.a(), ECHO);
        UUID run = start(t, pipeline(t, Map.of("maxRetriesPerStep", 2, "recoveryMode", "MANUAL"), t.a(), t.b()));
        UUID first = execution(t, run, 0);
        serviceCall(t.org(), first, "/start", Map.of()).andExpect(status().isOk());
        JsonNode decision = requestTool(t.org(), first, "toolu_1", "diagnostic.echo", text("effect"));
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());
        serviceCall(t.org(), first, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isOk());
        // The orchestrator disappears while the call runs: the execution is failed by the platform.
        serviceCall(t.org(), first, "/fail", body("errorCode", "PROVIDER_UNAVAILABLE", "message", "lost")).andExpect(status().isOk());
        pass();
        retriesAreDue(run);
        pass();

        List<JsonNode> steps = steps(t, run);
        assertThat(steps).hasSize(1);                                   // nothing was replayed, not even a "transient" error
        assertThat(steps.get(0).get("failureCategory").asString()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(steps.get(0).get("retrySafe").asBoolean()).isFalse();
        JsonNode failed = run(t, run);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");  // a truthful terminal state, not WAITING_RECOVERY
        // No manual retry either: the outcome is unknown and there is no reconciliation mechanism.
        postAs(t.owner(), runs(t.org(), "/" + run + "/steps/1/retry"), "{}").andExpect(status().isConflict());
        assertThat(count("SELECT count(*) FROM workflow_steps WHERE run_id = ?", run)).isEqualTo(1);
    }

    @Test
    void aToolFailureIsNeverRetriedBecauseNothingProvesItIsIdempotent() throws Exception {
        Team t = team();
        grant(t.owner(), t.org(), t.a(), ECHO);
        UUID run = start(t, pipeline(t, Map.of("maxRetriesPerStep", 2, "recoveryMode", "MANUAL"), t.a()));
        UUID first = execution(t, run, 0);
        serviceCall(t.org(), first, "/start", Map.of()).andExpect(status().isOk());
        JsonNode decision = requestTool(t.org(), first, "toolu_1", "diagnostic.echo", text("x"));
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());
        serviceCall(t.org(), first, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isOk());
        serviceCall(t.org(), first, "/tool-calls/" + invocation + "/complete",
                body("success", false, "output", "", "truncated", false, "errorCode", "TOOL_ERROR", "message", "denied by the tool"))
                .andExpect(status().isOk());
        serviceCall(t.org(), first, "/fail", body("errorCode", "TOOL_FAILED", "message", "tool failed")).andExpect(status().isOk());
        pass();
        assertThat(steps(t, run).get(0).get("failureCategory").asString()).isEqualTo("TOOL_FAILURE");
        assertThat(steps(t, run)).hasSize(1);
        assertThat(runStatus(t, run)).isEqualTo("FAILED");
    }

    // ------------------------------------------------------------------ failure policies

    @Test
    void failFastStopsEverythingAfterTheFailedStepAndKeepsThePreviousResults() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of(), t.a(), t.b(), t.c()));
        succeed(t.org(), execution(t, run, 0), "KEEP-ME", 10, 10);
        pass();
        fail(t.org(), execution(t, run, 1), "PROVIDER_BAD_REQUEST");
        pass();
        assertThat(steps(t, run)).hasSize(2);
        assertThat(steps(t, run).get(0).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(steps(t, run).get(0).get("resultSummary").asString()).isEqualTo("KEEP-ME");
        assertThat(runStatus(t, run)).isEqualTo("FAILED");
    }

    @Test
    void continueKeepsTheFailureAndTellsTheNextStepThatDeclaredItAsAnInputNeverPresentingItAsASuccess() throws Exception {
        Team t = team();
        UUID workflow = pipeline(t, Map.of("failurePolicy", "CONTINUE"), t.a(), t.b(), t.c());
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/steps"), body("steps", List.of(
                body("name", "Research", "agentId", t.a(), "instruction", "Research"),
                body("name", "Draft", "agentId", t.b(), "instruction", "Draft", "inputsFrom", List.of(1)),
                body("name", "Review", "agentId", t.c(), "instruction", "Review", "inputsFrom", List.of(2))))).andExpect(status().isOk());
        UUID run = start(t, workflow);
        succeed(t.org(), execution(t, run, 0), "RESULT-ONE", 10, 10);
        pass();
        fail(t.org(), execution(t, run, 1), "PROVIDER_BAD_REQUEST");
        pass();
        assertThat(steps(t, run)).hasSize(3);                                   // the workflow went on
        String third = task(execution(t, run, 2));
        assertThat(third).contains("FAILED (PERMANENT_PROVIDER_FAILURE)").contains("No result is available").doesNotContain("RESULT-ONE");
        succeed(t.org(), execution(t, run, 2), "RESULT-THREE", 10, 10);
        pass();
        JsonNode done = run(t, run);
        assertThat(done.get("status").asString()).isEqualTo("FAILED");           // never reported as a success
        assertThat(done.get("errorCode").asString()).isEqualTo("PARTIAL_FAILURE");
        assertThat(steps(t, run).get(2).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(steps(t, run).get(1).get("status").asString()).isEqualTo("FAILED");
    }

    @Test
    void continueNeverGoesPastAWorkflowLimitOrARevokedAuthorization() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of("failurePolicy", "CONTINUE", "stepTimeoutSeconds", 30), t.a(), t.b()));
        jdbc.update("UPDATE workflow_steps SET deadline_at = now() - interval '1 second' WHERE run_id = ?", run);
        serviceCall(t.org(), execution(t, run, 0), "/start", Map.of()).andExpect(status().isOk());
        pass();
        assertThat(steps(t, run)).hasSize(1);
        assertThat(steps(t, run).get(0).get("errorCode").asString()).isEqualTo("STEP_TIMEOUT");
        assertThat(steps(t, run).get(0).get("failureCategory").asString()).isEqualTo("WORKFLOW_LIMIT_EXCEEDED");
        assertThat(runStatus(t, run)).isEqualTo("FAILED");
    }

    @Test
    void inACoordinatedWorkflowContinueGivesTheCoordinatorABoundedFailureSummary() throws Exception {
        Team t = team();
        UUID workflow = id(read(postAs(t.owner(), wf(t.org(), ""), body("name", "C", "mode", "COORDINATED", "failurePolicy", "CONTINUE"))
                .andExpect(status().isCreated())));
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"), body("agentIds", List.of(t.a(), t.b()), "coordinatorAgentId", t.a()))
                .andExpect(status().isOk());
        UUID run = start(t, workflow);
        UUID turn1 = execution(t, run, 0);
        serviceCall(t.org(), turn1, "/start", Map.of()).andExpect(status().isOk());
        serviceCall(t.org(), turn1, "/workflow-actions", body("toolCallId", "c1", "action", "workflow.delegate",
                "arguments", Map.of("participantRef", "worker_1", "task", "Do the work"))).andExpect(status().isOk());
        serviceCall(t.org(), turn1, "/complete", body("result", "delegating", "inputTokens", 10, "outputTokens", 5, "model", "m")).andExpect(status().isOk());
        pass();
        fail(t.org(), execution(t, run, 1), "PROVIDER_BAD_REQUEST");
        pass();
        assertThat(steps(t, run)).hasSize(3);
        UUID turn2 = execution(t, run, 2);
        assertThat(steps(t, run).get(2).get("role").asString()).isEqualTo("COORDINATOR");
        assertThat(task(turn2)).contains("FAILED (PERMANENT_PROVIDER_FAILURE").contains("nothing external happened");
        serviceCall(t.org(), turn2, "/start", Map.of()).andExpect(status().isOk());
        serviceCall(t.org(), turn2, "/workflow-actions", body("toolCallId", "c2", "action", "workflow.finish",
                "arguments", Map.of("result", "Finished without the worker"))).andExpect(status().isOk());
        serviceCall(t.org(), turn2, "/complete", body("result", "done", "inputTokens", 10, "outputTokens", 5, "model", "m")).andExpect(status().isOk());
        pass();
        assertThat(runStatus(t, run)).isEqualTo("SUCCEEDED");                       // the COORDINATOR decided, the failure was not hidden
        JsonNode summary = read(getAs(t.owner(), runs(t.org(), "/" + run + "/summary")).andExpect(status().isOk()));
        assertThat(summary.get("coordinatorTokens").asLong()).isEqualTo(30);
        assertThat(summary.get("failuresByCategory").get("PERMANENT_PROVIDER_FAILURE").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ manual recovery

    @Test
    void aSafeFailureHoldsTheRunForAPersonWhoRetriesTheStepAsANewAttempt() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of("recoveryMode", "MANUAL"), t.a(), t.b()));
        UUID first = execution(t, run, 0);
        fail(t.org(), first, "PROVIDER_AUTHENTICATION");
        pass();
        assertThat(runStatus(t, run)).isEqualTo("WAITING_RECOVERY");
        pass();
        assertThat(steps(t, run)).hasSize(1);                                    // nothing starts by itself

        // Who may retry: workflow.execute AND agent.execute, in this organization only.
        TestUser stranger = newUser("stranger");
        UUID other = createOrganization(stranger, "Other " + UUID.randomUUID().toString().substring(0, 8));
        postAs(stranger, runs(other, "/" + run + "/steps/1/retry"), "{}").andExpect(status().isNotFound());
        TestUser member = newUser("member");
        join(t.owner(), t.org(), member, "MEMBER");
        postAs(member, runs(t.org(), "/" + run + "/steps/1/retry"), "{}").andExpect(status().isForbidden());
        postAs(t.owner(), runs(t.org(), "/" + run + "/steps/2/retry"), "{}").andExpect(status().isConflict());   // not the failed step

        postAs(t.owner(), runs(t.org(), "/" + run + "/steps/1/retry"), "{}").andExpect(status().isOk());
        List<JsonNode> after = steps(t, run);
        assertThat(after).hasSize(2);
        assertThat(after.get(1).get("attempt").asInt()).isEqualTo(2);
        assertThat(after.get(1).get("launchReason").asString()).isEqualTo("MANUAL_RETRY");
        assertThat(execution(t, run, 1)).isNotEqualTo(first);
        assertThat(after.get(0).get("status").asString()).isEqualTo("FAILED");
        assertThat(runStatus(t, run)).isEqualTo("RUNNING");
        // A duplicate request creates nothing.
        postAs(t.owner(), runs(t.org(), "/" + run + "/steps/1/retry"), "{}").andExpect(status().isConflict());
        assertThat(steps(t, run)).hasSize(2);

        succeed(t.org(), execution(t, run, 1), "R1", 10, 10);
        pass();
        succeed(t.org(), execution(t, run, 2), "R2", 10, 10);
        pass();
        assertThat(runStatus(t, run)).isEqualTo("SUCCEEDED");
        // A terminal run can never be retried.
        postAs(t.owner(), runs(t.org(), "/" + run + "/steps/1/retry"), "{}").andExpect(status().isConflict());
        JsonNode summary = read(getAs(t.owner(), runs(t.org(), "/" + run + "/summary")).andExpect(status().isOk()));
        assertThat(summary.get("manualRetries").asInt()).isEqualTo(1);
    }

    @Test
    void theManualRetryBudgetIsBounded() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of("recoveryMode", "MANUAL"), t.a()));
        fail(t.org(), execution(t, run, 0), "PROVIDER_AUTHENTICATION");
        pass();
        for (int i = 1; i <= 3; i++) {
            assertThat(runStatus(t, run)).isEqualTo("WAITING_RECOVERY");
            postAs(t.owner(), runs(t.org(), "/" + run + "/steps/1/retry"), "{}").andExpect(status().isOk());
            fail(t.org(), execution(t, run, i), "PROVIDER_AUTHENTICATION");
            pass();
        }
        assertThat(steps(t, run)).hasSize(4);
        assertThat(runStatus(t, run)).isEqualTo("FAILED");                          // budget used up: the run ends, it never loops
        postAs(t.owner(), runs(t.org(), "/" + run + "/steps/1/retry"), "{}").andExpect(status().isConflict());
    }

    @Test
    void manualRecoveryRevalidatesTheAgentAndTheRequesterAndNeverRestoresRevokedAccess() throws Exception {
        Team t = team();
        // The agent was paused meanwhile: the retry is refused and the run keeps waiting.
        UUID run = start(t, pipeline(t, Map.of("recoveryMode", "MANUAL"), t.a()));
        fail(t.org(), execution(t, run, 0), "PROVIDER_AUTHENTICATION");
        pass();
        postAs(t.owner(), agents(t.org(), "/" + t.a() + "/pause"), "{}").andExpect(status().isOk());
        postAs(t.owner(), runs(t.org(), "/" + run + "/steps/1/retry"), "{}").andExpect(status().isConflict());
        assertThat(runStatus(t, run)).isEqualTo("WAITING_RECOVERY");
        assertThat(steps(t, run)).hasSize(1);
        // Cancelling a run that waits for recovery settles it.
        postAs(t.owner(), runs(t.org(), "/" + run + "/cancel"), "{}").andExpect(status().isOk());
        pass();
        assertThat(runStatus(t, run)).isEqualTo("CANCELLED");

        // The person who started the run lost the right to execute: a retry by someone else does not restore it.
        TestUser runner = newUser("runner");
        UUID membership = join(t.owner(), t.org(), runner, "MEMBER");
        giveCustomRole(t.owner(), t.org(), membership, "Runner", List.of("workflow.read", "workflow.execute", "agent.execute", "agent.read"));
        UUID workflow = pipeline(t, Map.of("recoveryMode", "MANUAL"), t.b());
        UUID run2 = id(read(postAs(runner, wf(t.org(), "/" + workflow + "/runs"), body("objective", "Objective")).andExpect(status().isAccepted())));
        fail(t.org(), execution(t, run2, 0), "PROVIDER_AUTHENTICATION");
        pass();
        postAs(t.owner(), path(t.org(), "/members/" + membership + "/suspend"), "{}").andExpect(status().isOk());
        postAs(t.owner(), runs(t.org(), "/" + run2 + "/steps/1/retry"), "{}").andExpect(status().isOk());
        JsonNode failed = run(t, run2);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("errorCode").asString()).isEqualTo("REQUESTER_NOT_AUTHORIZED");
        assertThat(steps(t, run2)).hasSize(1);
    }

    @Test
    void aRunThatWaitsForRecoveryStillEndsAtItsDeadline() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of("recoveryMode", "MANUAL"), t.a()));
        fail(t.org(), execution(t, run, 0), "PROVIDER_AUTHENTICATION");
        pass();
        assertThat(runStatus(t, run)).isEqualTo("WAITING_RECOVERY");
        jdbc.update("UPDATE workflow_runs SET deadline_at = now() - interval '1 second' WHERE id = ?", run);
        pass();
        assertThat(runStatus(t, run)).isEqualTo("TIMEOUT");
        postAs(t.owner(), runs(t.org(), "/" + run + "/steps/1/retry"), "{}").andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ timeouts, recovery after restart, stale events

    @Test
    void aStepThatExceedsItsOwnTimeLimitIsEndedWithAPreciseCode() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of("stepTimeoutSeconds", 30), t.a(), t.b()));
        assertThat(steps(t, run).get(0).get("deadlineAt").asString()).isNotBlank();
        UUID first = execution(t, run, 0);
        serviceCall(t.org(), first, "/start", Map.of()).andExpect(status().isOk());
        jdbc.update("UPDATE workflow_steps SET deadline_at = now() - interval '1 second' WHERE run_id = ?", run);
        pass();
        assertThat(steps(t, run).get(0).get("errorCode").asString()).isEqualTo("STEP_TIMEOUT");
        assertThat(jdbc.queryForObject("SELECT status FROM agent_executions WHERE id = ?", String.class, first)).isEqualTo("FAILED");
        assertThat(steps(t, run)).hasSize(1);                                    // later steps never start
        assertThat(runStatus(t, run)).isEqualTo("FAILED");
        // The late completion of the ended child changes nothing.
        serviceCall(t.org(), first, "/complete", body("result", "late", "inputTokens", 1, "outputTokens", 1, "model", "m")).andExpect(status().isConflict());
    }

    @Test
    void theOrchestratorLearnsTheRemainingTimeOfAWorkflowExecutionAndNothingForAnOrdinaryOne() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of("deadlineSeconds", 600, "stepTimeoutSeconds", 120), t.a()));
        JsonNode started = read(serviceCall(t.org(), execution(t, run, 0), "/start", Map.of()).andExpect(status().isOk()));
        assertThat(started.get("deadlineSeconds").asLong()).isBetween(1L, 120L);
        UUID plain = launch(t.owner(), t.org(), t.b(), "ordinary");
        JsonNode ordinary = read(serviceCall(t.org(), plain, "/start", Map.of()).andExpect(status().isOk()));
        assertThat(ordinary.get("deadlineSeconds") == null || ordinary.get("deadlineSeconds").isNull()).isTrue();
    }

    @Test
    void aDelayedCompletionOfAnOldAttemptNeverRevivesOrChangesAnything() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of("maxRetriesPerStep", 1), t.a()));
        UUID first = execution(t, run, 0);
        fail(t.org(), first, "PROVIDER_UNAVAILABLE");
        pass();
        retriesAreDue(run);
        pass();
        UUID second = execution(t, run, 1);
        serviceCall(t.org(), first, "/complete", body("result", "late", "inputTokens", 1, "outputTokens", 1, "model", "m")).andExpect(status().isConflict());
        succeed(t.org(), second, "REAL", 10, 10);
        pass();
        assertThat(run(t, run).get("result").asString()).isEqualTo("REAL");
        // The run is terminal: late events and passes change nothing.
        serviceCall(t.org(), second, "/complete", body("result", "again", "inputTokens", 1, "outputTokens", 1, "model", "m")).andExpect(status().isConflict());
        pass();
        assertThat(steps(t, run)).hasSize(2);
        assertThat(runStatus(t, run)).isEqualTo("SUCCEEDED");
    }

    @Test
    void concurrentMaintenanceWorkersLaunchTheDueRetryExactlyOnce() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of("maxRetriesPerStep", 2), t.a()));
        fail(t.org(), execution(t, run, 0), "PROVIDER_RATE_LIMIT");
        pass();
        retriesAreDue(run);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            done.add(pool.submit(() -> {
                gate.await();
                probe.newWorker().advanceOpenRuns();
                return null;
            }));
        }
        gate.countDown();
        for (Future<?> f : done) {
            f.get();
        }
        pool.shutdown();
        assertThat(steps(t, run)).hasSize(2);
        assertThat(count("SELECT count(DISTINCT execution_id) FROM workflow_steps WHERE run_id = ?", run)).isEqualTo(2);
    }

    @Test
    void aScheduledRetrySurvivesTheLossOfEveryInMemoryObject() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of("maxRetriesPerStep", 2), t.a()));
        fail(t.org(), execution(t, run, 0), "PROVIDER_UNAVAILABLE");
        pass();
        // "Restart": nothing but PostgreSQL is left; a brand-new worker finds the stored due time and acts on it.
        assertThat(count("SELECT count(*) FROM workflow_steps WHERE run_id = ? AND next_attempt_at IS NOT NULL", run)).isEqualTo(1);
        retriesAreDue(run);
        probe.newWorker().advanceOpenRuns();
        assertThat(steps(t, run)).hasSize(2);
        assertThat(steps(t, run).get(1).get("launchReason").asString()).isEqualTo("AUTO_RETRY");
    }


    // ------------------------------------------------------------------ coordinated retries

    private UUID coordinatedRun(Team t, Map<String, Object> settings) throws Exception {
        Map<String, Object> create = new HashMap<>(settings);
        create.put("name", "C");
        create.put("mode", "COORDINATED");
        UUID workflow = id(read(postAs(t.owner(), wf(t.org(), ""), create).andExpect(status().isCreated())));
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"), body("agentIds", List.of(t.a(), t.b()), "coordinatorAgentId", t.a()))
                .andExpect(status().isOk());
        return start(t, workflow);
    }

    private void delegate(UUID org, UUID turn, String callId, String task) throws Exception {
        serviceCall(org, turn, "/start", Map.of()).andExpect(status().isOk());
        serviceCall(org, turn, "/workflow-actions", body("toolCallId", callId, "action", "workflow.delegate",
                "arguments", Map.of("participantRef", "worker_1", "task", task))).andExpect(status().isOk());
        serviceCall(org, turn, "/complete", body("result", "delegating", "inputTokens", 10, "outputTokens", 5, "model", "m")).andExpect(status().isOk());
    }

    @Test
    void aTransientWorkerFailureInACoordinatedWorkflowIsRetriedWithTheSameDelegatedTaskInANewExecution() throws Exception {
        Team t = team();
        UUID run = coordinatedRun(t, Map.of("maxRetriesPerStep", 2));
        delegate(t.org(), execution(t, run, 0), "d1", "Collect the facts");
        pass();
        UUID worker1 = execution(t, run, 1);
        fail(t.org(), worker1, "PROVIDER_UNAVAILABLE");
        pass();
        assertThat(steps(t, run)).hasSize(2);                                      // scheduled, not launched
        retriesAreDue(run);
        pass();
        List<JsonNode> steps = steps(t, run);
        assertThat(steps).hasSize(3);
        assertThat(steps.get(2).get("role").asString()).isEqualTo("WORKER");
        assertThat(steps.get(2).get("rank").asInt()).isEqualTo(2);
        assertThat(steps.get(2).get("attempt").asInt()).isEqualTo(2);
        assertThat(steps.get(2).get("launchReason").asString()).isEqualTo("AUTO_RETRY");
        UUID worker2 = execution(t, run, 2);
        assertThat(worker2).isNotEqualTo(worker1);
        assertThat(task(worker2)).contains("Collect the facts");
        succeed(t.org(), worker2, "FACTS", 20, 10);
        pass();
        UUID turn2 = execution(t, run, 3);                                         // the coordinator decides again with the result
        assertThat(steps(t, run).get(3).get("role").asString()).isEqualTo("COORDINATOR");
        assertThat(task(turn2)).contains("FACTS").doesNotContain("FAILED");
        // The retried attempt still counts as ONE worker step against the limit.
        assertThat(count("SELECT count(*) FROM workflow_steps WHERE run_id = ? AND role = 'WORKER' AND attempt = 1", run)).isEqualTo(1);
    }

    @Test
    void aTransientCoordinatorTurnFailureIsRetriedAndNoWorkerExistsUntilAnActionIsAccepted() throws Exception {
        Team t = team();
        UUID run = coordinatedRun(t, Map.of("maxRetriesPerStep", 1));
        UUID turn1 = execution(t, run, 0);
        fail(t.org(), turn1, "PROVIDER_RATE_LIMIT");
        pass();
        retriesAreDue(run);
        pass();
        List<JsonNode> steps = steps(t, run);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(1).get("role").asString()).isEqualTo("COORDINATOR");
        assertThat(steps.get(1).get("attempt").asInt()).isEqualTo(2);
        UUID turn2 = execution(t, run, 1);
        assertThat(turn2).isNotEqualTo(turn1);
        // The failed turn cannot act any more: a delayed action from it is refused.
        serviceCall(t.org(), turn1, "/workflow-actions", body("toolCallId", "late", "action", "workflow.finish",
                "arguments", Map.of("result", "x"))).andExpect(status().isConflict());
        delegate(t.org(), turn2, "d1", "Real task");
        pass();
        assertThat(steps(t, run)).hasSize(3);
        assertThat(steps(t, run).get(2).get("role").asString()).isEqualTo("WORKER");
    }

    @Test
    void aWorkerOutcomeUnknownInACoordinatedWorkflowIsReportedToTheCoordinatorAsAnExternalEffectMayHaveHappened() throws Exception {
        Team t = team();
        grant(t.owner(), t.org(), t.b(), ECHO);
        UUID workflow = id(read(postAs(t.owner(), wf(t.org(), ""), body("name", "C", "mode", "COORDINATED", "failurePolicy", "CONTINUE",
                "maxRetriesPerStep", 2)).andExpect(status().isCreated())));
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"), body("agentIds", List.of(t.a(), t.b()), "coordinatorAgentId", t.a()))
                .andExpect(status().isOk());
        UUID run = start(t, workflow);
        delegate(t.org(), execution(t, run, 0), "d1", "Use the tool");
        pass();
        UUID worker = execution(t, run, 1);
        serviceCall(t.org(), worker, "/start", Map.of()).andExpect(status().isOk());
        JsonNode decision = requestTool(t.org(), worker, "toolu_1", "diagnostic.echo", text("effect"));
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());
        serviceCall(t.org(), worker, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isOk());
        serviceCall(t.org(), worker, "/fail", body("errorCode", "PROVIDER_UNAVAILABLE", "message", "lost")).andExpect(status().isOk());
        pass();
        retriesAreDue(run);
        pass();
        assertThat(steps(t, run)).hasSize(3);                                      // no replay: the next step is a coordinator turn
        assertThat(steps(t, run).get(2).get("role").asString()).isEqualTo("COORDINATOR");
        assertThat(steps(t, run).get(1).get("failureCategory").asString()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(task(execution(t, run, 2))).contains("FAILED (OUTCOME_UNKNOWN").contains("an external effect may have happened");
    }

    // ------------------------------------------------------------------ configuration and isolation

    @Test
    void reliabilitySettingsAreValidatedAndVisibleOnTheDefinitionAndTheSummaryIsOrganizationScoped() throws Exception {
        Team t = team();
        postAs(t.owner(), wf(t.org(), ""), body("name", "x", "failurePolicy", "RETRY", "maxRetriesPerStep", 0)).andExpect(status().isBadRequest());
        postAs(t.owner(), wf(t.org(), ""), body("name", "x", "failurePolicy", "CONTINUE", "recoveryMode", "MANUAL")).andExpect(status().isBadRequest());
        postAs(t.owner(), wf(t.org(), ""), body("name", "x", "deadlineSeconds", 60, "stepTimeoutSeconds", 120)).andExpect(status().isBadRequest());
        postAs(t.owner(), wf(t.org(), ""), body("name", "x", "failurePolicy", "SOMETHING")).andExpect(status().isBadRequest());
        JsonNode ok = read(postAs(t.owner(), wf(t.org(), ""), body("name", "ok", "failurePolicy", "RETRY", "maxRetriesPerStep", 2,
                "recoveryMode", "MANUAL", "stepTimeoutSeconds", 60)).andExpect(status().isCreated()));
        assertThat(ok.get("failurePolicy").asString()).isEqualTo("RETRY");
        assertThat(ok.get("recoveryMode").asString()).isEqualTo("MANUAL");
        assertThat(ok.get("stepTimeoutSeconds").asInt()).isEqualTo(60);
        // Defaults keep C3.1 behaviour.
        JsonNode plain = read(postAs(t.owner(), wf(t.org(), ""), body("name", "plain")).andExpect(status().isCreated()));
        assertThat(plain.get("failurePolicy").asString()).isEqualTo("FAIL_FAST");
        assertThat(plain.get("recoveryMode").asString()).isEqualTo("NONE");

        UUID run = start(t, pipeline(t, Map.of(), t.a()));
        TestUser stranger = newUser("stranger");
        UUID other = createOrganization(stranger, "Other " + UUID.randomUUID().toString().substring(0, 8));
        getAs(stranger, runs(other, "/" + run + "/summary")).andExpect(status().isNotFound());
        TestUser member = newUser("member");
        join(t.owner(), t.org(), member, "MEMBER");
        getAs(member, runs(t.org(), "/" + run + "/summary")).andExpect(status().isForbidden());
    }
}
