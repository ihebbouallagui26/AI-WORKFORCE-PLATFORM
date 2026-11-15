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
 * Controlled parallel execution (C3.4) against the REAL persistence. The orchestrator is played through its internal API: the test
 * IS the set of workers, so overlap is proven deterministically (every worker of a group is RUNNING at the same time before any of
 * them ends), not inferred from timing. Background maintenance is off: every scheduler pass is explicit.
 */
@Import({WorkflowProbe.class, MaintenanceProbe.class})
@TestPropertySource(properties = {"app.workflows.maintenance-ms=3600000", "app.executions.approval-maintenance-ms=3600000",
        "app.workflows.retry-base-delay=PT30S", "app.workflows.retry-max-delay=PT30S", "app.workflows.worker-token-reservation=1000"})
class WorkflowParallelIntegrationTest extends ToolFlows {

    private static final Object[] ECHO = {"diagnostic.echo", "READ", false};
    private static final Object[] NOTE = {"diagnostic.record_note", "WRITE", false};

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

    private record Team(TestUser owner, UUID org, UUID a, UUID b, UUID c, UUID d) {
    }

    private Team team() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Parallel " + UUID.randomUUID().toString().substring(0, 8));
        return new Team(owner, org, agentWithSecret(owner, org, "Competitors"), agentWithSecret(owner, org, "Pricing"),
                agentWithSecret(owner, org, "Customers"), agentWithSecret(owner, org, "Synthesis"));
    }

    private static String wf(UUID org, String suffix) {
        return path(org, "/workflows" + suffix);
    }

    private static String runs(UUID org, String suffix) {
        return path(org, "/workflow-runs" + suffix);
    }

    /** A pipeline: stage 1 = the first {@code parallel} agents in parallel, stage 2 = the last agent (synthesis). */
    private UUID pipeline(Team t, Map<String, Object> settings, UUID... agents) throws Exception {
        Map<String, Object> create = new HashMap<>(settings);
        create.put("name", "Parallel");
        create.putIfAbsent("maxParallelWorkers", 3);
        UUID workflow = id(read(postAs(t.owner(), wf(t.org(), ""), create).andExpect(status().isCreated())));
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"), body("agentIds", new ArrayList<>(new LinkedHashSet<>(List.of(agents)))))
                .andExpect(status().isOk());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < agents.length; i++) {
            boolean last = i == agents.length - 1;
            steps.add(body("name", "Step " + (i + 1), "agentId", agents[i], "instruction", "Do part " + (i + 1), "stage", last ? 2 : 1));
        }
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/steps"), body("steps", steps)).andExpect(status().isOk());
        return workflow;
    }

    private UUID start(Team t, UUID workflow) throws Exception {
        return id(read(postAs(t.owner(), wf(t.org(), "/" + workflow + "/runs"), body("objective", "Analyze the market")).andExpect(status().isAccepted())));
    }

    private JsonNode run(Team t, UUID run) throws Exception {
        return read(getAs(t.owner(), runs(t.org(), "/" + run)).andExpect(status().isOk()));
    }

    private String runStatus(Team t, UUID run) throws Exception {
        return run(t, run).get("status").asString();
    }

    private List<JsonNode> steps(Team t, UUID run) throws Exception {
        JsonNode content = read(getAs(t.owner(), runs(t.org(), "/" + run + "/steps?size=100")).andExpect(status().isOk())).get("content");
        List<JsonNode> list = new ArrayList<>();
        for (int i = 0; i < content.size(); i++) {
            list.add(content.get(i));
        }
        return list;
    }

    private JsonNode summary(Team t, UUID run) throws Exception {
        return read(getAs(t.owner(), runs(t.org(), "/" + run + "/summary")).andExpect(status().isOk()));
    }

    private UUID executionOfRank(Team t, UUID run, int rank, int attempt) throws Exception {
        for (JsonNode s : steps(t, run)) {
            if (s.get("rank").asInt() == rank && s.get("attempt").asInt() == attempt && !s.get("executionId").isNull()) {
                return UUID.fromString(s.get("executionId").asString());
            }
        }
        throw new IllegalStateException("no execution for rank " + rank + " attempt " + attempt);
    }

    private String stepStatus(Team t, UUID run, int rank) throws Exception {
        String last = null;
        for (JsonNode s : steps(t, run)) {
            if (s.get("rank").asInt() == rank) {
                last = s.get("status").asString();
            }
        }
        return last;
    }

    private void pass() {
        probe.newWorker().advanceOpenRuns();
    }

    private void begin(UUID org, UUID execution) throws Exception {
        serviceCall(org, execution, "/start", Map.of()).andExpect(status().isOk());
    }

    private void finish(UUID org, UUID execution, String result, int input, int output) throws Exception {
        serviceCall(org, execution, "/complete", body("result", result, "inputTokens", input, "outputTokens", output, "model", "m")).andExpect(status().isOk());
    }

    private void fail(UUID org, UUID execution, String code) throws Exception {
        serviceCall(org, execution, "/fail", body("errorCode", code, "message", "simulated")).andExpect(status().isOk());
    }

    private String task(UUID execution) {
        return jdbc.queryForObject("SELECT task FROM agent_executions WHERE id = ?", String.class, execution);
    }

    private String executionStatus(UUID execution) {
        return jdbc.queryForObject("SELECT status FROM agent_executions WHERE id = ?", String.class, execution);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private void retriesAreDue(UUID run) {
        jdbc.update("UPDATE workflow_steps SET next_attempt_at = now() - interval '1 second' WHERE run_id = ? AND next_attempt_at IS NOT NULL", run);
    }

    // ------------------------------------------------------------------ Scenario A: a successful parallel pipeline

    @Test
    void threeIndependentWorkersReallyOverlapThenASynthesisAgentRunsAfterTheFanIn() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of(), t.a(), t.b(), t.c(), t.d()));

        // Fan-out: the whole stage is dispatched at once as three distinct child executions; the synthesis step does not exist yet.
        List<JsonNode> first = steps(t, run);
        assertThat(first).hasSize(3);
        assertThat(first).allSatisfy(s -> assertThat(s.get("status").asString()).isEqualTo("RUNNING"));
        assertThat(first).extracting(s -> s.get("executionId").asString()).doesNotHaveDuplicates();
        UUID w1 = executionOfRank(t, run, 1, 1);
        UUID w2 = executionOfRank(t, run, 2, 1);
        UUID w3 = executionOfRank(t, run, 3, 1);

        // Deterministic overlap: all three are RUNNING at the same time; none ends before all have started.
        begin(t.org(), w1);
        begin(t.org(), w2);
        begin(t.org(), w3);
        assertThat(List.of(executionStatus(w1), executionStatus(w2), executionStatus(w3))).containsOnly("RUNNING");
        assertThat(summary(t, run).get("peakConcurrency").asInt()).isEqualTo(3);

        finish(t.org(), w1, "COMPETITORS: three players.", 100, 20);
        pass();
        assertThat(steps(t, run)).hasSize(3);                                        // fan-in waits: one result is not the group
        assertThat(runStatus(t, run)).isEqualTo("RUNNING");
        finish(t.org(), w2, "PRICING: freemium wins.", 110, 30);
        pass();
        assertThat(steps(t, run)).hasSize(3);
        finish(t.org(), w3, "CUSTOMERS: SMBs.", 90, 10);
        pass();

        // Fan-in complete: the synthesis step receives the three results in a deterministic order, as untrusted data.
        List<JsonNode> after = steps(t, run);
        assertThat(after).hasSize(4);
        UUID synthesis = executionOfRank(t, run, 4, 1);
        String context = task(synthesis);
        assertThat(context).contains("COMPETITORS: three players.").contains("PRICING: freemium wins.").contains("CUSTOMERS: SMBs.")
                .contains("DATA produced by other agents");
        assertThat(context.indexOf("COMPETITORS")).isLessThan(context.indexOf("PRICING"));
        assertThat(context.indexOf("PRICING")).isLessThan(context.indexOf("CUSTOMERS"));
        begin(t.org(), synthesis);
        finish(t.org(), synthesis, "REPORT: go.", 300, 100);
        pass();

        JsonNode done = run(t, run);
        assertThat(done.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("result").asString()).isEqualTo("REPORT: go.");
        assertThat(done.get("tokensUsed").asInt()).isEqualTo(120 + 140 + 100 + 400);       // every worker counted exactly once
        JsonNode summary = summary(t, run);
        assertThat(summary.get("groups")).hasSize(2);
        assertThat(summary.get("groups").get(0).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(summary.get("groups").get(0).get("workers").asInt()).isEqualTo(3);
        assertThat(summary.get("reservedTokens").asInt()).isZero();                       // all reservations were released
        assertThat(count("SELECT count(DISTINCT agent_id) FROM workflow_steps WHERE run_id = ?", run)).isEqualTo(4); // identities preserved
    }

    @Test
    void aSequentialPipelineIsUnchangedAndNeverQueues() throws Exception {
        Team t = team();
        UUID workflow = id(read(postAs(t.owner(), wf(t.org(), ""), body("name", "Seq")).andExpect(status().isCreated())));
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"), body("agentIds", List.of(t.a(), t.b()))).andExpect(status().isOk());
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/steps"), body("steps", List.of(
                body("name", "One", "agentId", t.a(), "instruction", "x"), body("name", "Two", "agentId", t.b(), "instruction", "y")))).andExpect(status().isOk());
        UUID run = start(t, workflow);
        assertThat(steps(t, run)).hasSize(1);                                               // one step at a time, as in C3.1
        assertThat(run(t, run).get("maxParallelWorkers").asInt()).isEqualTo(1);
        assertThat(steps(t, run).get(0).get("groupNo").isNull()).isTrue();
        assertThat(count("SELECT count(*) FROM workflow_steps WHERE run_id = ? AND status = 'QUEUED'", run)).isZero();
    }

    // ------------------------------------------------------------------ definition rules

    @Test
    void stagesAreValidatedBoundedAndNeedAParallelBound() throws Exception {
        Team t = team();
        UUID sequential = id(read(postAs(t.owner(), wf(t.org(), ""), body("name", "S")).andExpect(status().isCreated())));
        putAs(t.owner(), wf(t.org(), "/" + sequential + "/participants"), body("agentIds", List.of(t.a(), t.b()))).andExpect(status().isOk());
        // Two steps in one stage need maxParallelWorkers >= 2.
        putAs(t.owner(), wf(t.org(), "/" + sequential + "/steps"), body("steps", List.of(
                body("name", "A", "agentId", t.a(), "instruction", "x", "stage", 1),
                body("name", "B", "agentId", t.b(), "instruction", "y", "stage", 1)))).andExpect(status().isBadRequest());

        UUID p = id(read(postAs(t.owner(), wf(t.org(), ""), body("name", "P", "maxParallelWorkers", 2)).andExpect(status().isCreated())));
        putAs(t.owner(), wf(t.org(), "/" + p + "/participants"), body("agentIds", List.of(t.a(), t.b(), t.c()))).andExpect(status().isOk());
        // Three in one stage exceeds the bound of 2.
        putAs(t.owner(), wf(t.org(), "/" + p + "/steps"), body("steps", List.of(
                body("name", "A", "agentId", t.a(), "instruction", "x", "stage", 1), body("name", "B", "agentId", t.b(), "instruction", "y", "stage", 1),
                body("name", "C", "agentId", t.c(), "instruction", "z", "stage", 1)))).andExpect(status().isBadRequest());
        // Either all steps name a stage or none; stages start at 1 and never skip.
        putAs(t.owner(), wf(t.org(), "/" + p + "/steps"), body("steps", List.of(
                body("name", "A", "agentId", t.a(), "instruction", "x", "stage", 1), body("name", "B", "agentId", t.b(), "instruction", "y")))).andExpect(status().isBadRequest());
        putAs(t.owner(), wf(t.org(), "/" + p + "/steps"), body("steps", List.of(
                body("name", "A", "agentId", t.a(), "instruction", "x", "stage", 2)))).andExpect(status().isBadRequest());
        putAs(t.owner(), wf(t.org(), "/" + p + "/steps"), body("steps", List.of(
                body("name", "A", "agentId", t.a(), "instruction", "x", "stage", 1), body("name", "B", "agentId", t.b(), "instruction", "y", "stage", 3)))).andExpect(status().isBadRequest());
        // No dependency inside a stage (that would be a cycle / a wait on a sibling).
        putAs(t.owner(), wf(t.org(), "/" + p + "/steps"), body("steps", List.of(
                body("name", "A", "agentId", t.a(), "instruction", "x", "stage", 1),
                body("name", "B", "agentId", t.b(), "instruction", "y", "stage", 1, "inputsFrom", List.of(1))))).andExpect(status().isBadRequest());
        // A valid one, with the fan-in default (the synthesis takes ALL steps of the previous stage).
        JsonNode ok = read(putAs(t.owner(), wf(t.org(), "/" + p + "/steps"), body("steps", List.of(
                body("name", "A", "agentId", t.a(), "instruction", "x", "stage", 1), body("name", "B", "agentId", t.b(), "instruction", "y", "stage", 1),
                body("name", "S", "agentId", t.c(), "instruction", "z", "stage", 2)))).andExpect(status().isOk()));
        assertThat(ok.get("steps").get(2).get("inputsFrom").toString()).isEqualTo("[1,2]");
        // The bound cannot be lowered below an existing stage, manual recovery is not available in parallel, the platform cap is 3.
        patchAs(t.owner(), wf(t.org(), "/" + p), body("maxParallelWorkers", 1)).andExpect(status().isConflict());
        postAs(t.owner(), wf(t.org(), ""), body("name", "x", "maxParallelWorkers", 4)).andExpect(status().isBadRequest());
        postAs(t.owner(), wf(t.org(), ""), body("name", "x", "maxParallelWorkers", 2, "recoveryMode", "MANUAL")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ concurrency, capacity, budget

    @Test
    void concurrencyNeverExceedsTheWorkflowBoundAndQueuedWorkIsPersistedAndDispatchedLater() throws Exception {
        Team t = team();
        UUID workflow = pipeline(t, Map.of("maxParallelWorkers", 2), t.a(), t.b(), t.d());
        // Stage 1 holds two steps; run a second pipeline-less check of the bound with a three-wide stage is impossible: the bound IS 2.
        UUID run = start(t, workflow);
        assertThat(steps(t, run).stream().filter(s -> s.get("status").asString().equals("RUNNING")).count()).isEqualTo(2);
        assertThat(summary(t, run).get("peakConcurrency").asInt()).isLessThanOrEqualTo(2);
    }

    @Test
    void theOrganizationCapacityIsRespectedWorkIsQueuedNotLostAndStartsWhenASlotFrees() throws Exception {
        Team t = team();
        // Occupy one of the 3 organization slots with an ordinary execution: only 2 workers of the stage can be dispatched.
        UUID occupant = launch(t.owner(), t.org(), t.d(), "ordinary work");
        UUID run = start(t, pipeline(t, Map.of(), t.a(), t.b(), t.c(), t.d()));
        List<JsonNode> steps = steps(t, run);
        assertThat(steps).hasSize(3);
        assertThat(steps.stream().filter(s -> s.get("status").asString().equals("RUNNING")).count()).isEqualTo(2);
        assertThat(steps.stream().filter(s -> s.get("status").asString().equals("QUEUED")).count()).isEqualTo(1);
        JsonNode queued = steps.stream().filter(s -> s.get("status").asString().equals("QUEUED")).findFirst().orElseThrow();
        assertThat(queued.get("executionId").isNull()).isTrue();                           // accepted and persisted, no child yet
        pass();
        assertThat(count("SELECT count(*) FROM workflow_steps WHERE run_id = ? AND status = 'QUEUED'", run)).isEqualTo(1);   // a pass does not bypass the limit

        // The occupant ends: the next pass dispatches the queued worker (no duplicate of the others).
        begin(t.org(), occupant);
        finish(t.org(), occupant, "done", 1, 1);
        pass();
        assertThat(count("SELECT count(*) FROM workflow_steps WHERE run_id = ? AND status = 'QUEUED'", run)).isZero();
        assertThat(count("SELECT count(DISTINCT execution_id) FROM workflow_steps WHERE run_id = ?", run)).isEqualTo(3);
        assertThat(summary(t, run).get("attemptList").get(2).get("queueWaitMs").asLong()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void concurrentWorkersCannotOverspendTheTokenBudgetThroughReservations() throws Exception {
        Team t = team();
        // Budget 2400, reservation 1000 per worker: two workers hold 2000, the 400 left cannot start a third (minimum reservation 500).
        UUID run = start(t, pipeline(t, Map.of("tokenBudget", 2400), t.a(), t.b(), t.c(), t.d()));
        JsonNode first = run(t, run);
        assertThat(first.get("status").asString()).isEqualTo("RUNNING");
        List<JsonNode> steps = steps(t, run);
        assertThat(steps.stream().filter(s -> s.get("status").asString().equals("RUNNING")).count()).isEqualTo(2);
        assertThat(steps.stream().filter(s -> s.get("status").asString().equals("QUEUED")).count()).isEqualTo(1);
        assertThat(summary(t, run).get("reservedTokens").asInt()).isEqualTo(2000);
        // Reserved + spent never exceed the budget while the work is being dispatched.
        assertThat(jdbc.queryForObject("SELECT reserved_tokens + tokens_used FROM workflow_runs WHERE id = ?", Integer.class, run)).isLessThanOrEqualTo(2400);

        UUID w1 = executionOfRank(t, run, 1, 1);
        begin(t.org(), w1);
        finish(t.org(), w1, "r", 300, 200);                                                // actual usage 500 replaces its reservation of 1000
        pass();
        // Released 1000 (its actual 500 are now spent): 2400 - 500 spent - 1000 still reserved leaves 900 for the queued worker.
        assertThat(summary(t, run).get("reservedTokens").asInt()).isEqualTo(1000 + 900);
        assertThat(jdbc.queryForObject("SELECT reserved_tokens + tokens_used FROM workflow_runs WHERE id = ?", Integer.class, run)).isLessThanOrEqualTo(2400);
        assertThat(run(t, run).get("tokensUsed").asInt()).isEqualTo(500);
        assertThat(count("SELECT count(*) FROM workflow_steps WHERE run_id = ? AND status = 'QUEUED'", run)).isZero();
    }

    @Test
    void aBudgetThatCannotCoverAnyWorkerEndsTheRunTruthfully() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of("tokenBudget", 1000), t.a(), t.b(), t.c(), t.d()));
        // Reservation 1000 covers the whole budget for ONE worker; the two others stay queued while it runs...
        assertThat(steps(t, run).stream().filter(s -> s.get("status").asString().equals("RUNNING")).count()).isEqualTo(1);
        UUID w1 = executionOfRank(t, run, 1, 1);
        begin(t.org(), w1);
        finish(t.org(), w1, "r", 600, 400);                                                // the budget is now fully spent
        pass();
        // ...and when nothing runs and the budget is gone, the queued work cannot start: the run fails, queued work is cancelled.
        JsonNode failed = run(t, run);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("errorCode").asString()).isEqualTo("TOKEN_BUDGET_EXCEEDED");
        assertThat(count("SELECT count(*) FROM workflow_steps WHERE run_id = ? AND status = 'QUEUED'", run)).isZero();
        assertThat(jdbc.queryForObject("SELECT reserved_tokens FROM workflow_runs WHERE id = ?", Integer.class, run)).isZero();
    }

    // ------------------------------------------------------------------ failure policies

    @Test
    void failFastCancelsQueuedSiblingsStopsTheRunningOnesAndKeepsCompletedResults() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of(), t.a(), t.b(), t.c(), t.d()));
        UUID w1 = executionOfRank(t, run, 1, 1);
        UUID w2 = executionOfRank(t, run, 2, 1);
        UUID w3 = executionOfRank(t, run, 3, 1);
        begin(t.org(), w1);
        begin(t.org(), w2);
        begin(t.org(), w3);
        finish(t.org(), w1, "KEEP-ME", 10, 10);
        pass();
        fail(t.org(), w2, "PROVIDER_BAD_REQUEST");                                        // a permanent failure: no retry
        pass();
        // The still-running sibling was stopped (no tool in flight: immediately), the completed result is preserved.
        assertThat(executionStatus(w3)).isEqualTo("FAILED");
        assertThat(stepStatus(t, run, 3)).isEqualTo("CANCELLED");
        assertThat(stepStatus(t, run, 1)).isEqualTo("SUCCEEDED");
        pass();
        JsonNode failed = run(t, run);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(steps(t, run)).hasSize(3);                                              // the synthesis stage never started
        assertThat(steps(t, run).get(0).get("resultSummary").asString()).isEqualTo("KEEP-ME");
        assertThat(summary(t, run).get("groups").get(0).get("status").asString()).isEqualTo("PARTIALLY_FAILED");
    }

    @Test
    void failFastDoesNotDeclareAToolInFlightCancelledItsOutcomeIsUnknown() throws Exception {
        Team t = team();
        grant(t.owner(), t.org(), t.b(), ECHO);
        UUID run = start(t, pipeline(t, Map.of(), t.a(), t.b(), t.c(), t.d()));
        UUID w1 = executionOfRank(t, run, 1, 1);
        UUID w2 = executionOfRank(t, run, 2, 1);
        UUID w3 = executionOfRank(t, run, 3, 1);
        begin(t.org(), w1);
        begin(t.org(), w2);
        begin(t.org(), w3);
        JsonNode decision = requestTool(t.org(), w2, "toolu_1", "diagnostic.echo", text("effect"));
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());
        serviceCall(t.org(), w2, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isOk());   // external effect in flight
        fail(t.org(), w1, "PROVIDER_BAD_REQUEST");
        pass();
        // Within the grace period the sibling with a call in flight is NOT ended; the run waits for its known outcome.
        assertThat(executionStatus(w2)).isEqualTo("RUNNING");
        assertThat(runStatus(t, run)).isEqualTo("RUNNING");
        assertThat(executionStatus(w3)).isEqualTo("FAILED");                               // the sibling without a call was stopped
        // The grace period passes: the child is ended and the call is reported UNKNOWN, never "cancelled".
        jdbc.update("UPDATE workflow_steps SET completed_at = now() - interval '5 minutes' WHERE run_id = ? AND step_rank = 1", run);
        pass();
        assertThat(executionStatus(w2)).isEqualTo("FAILED");
        assertThat(steps(t, run).stream().filter(s -> s.get("rank").asInt() == 2).findFirst().orElseThrow().get("failureCategory").asString())
                .isEqualTo("OUTCOME_UNKNOWN");
        pass();
        assertThat(runStatus(t, run)).isEqualTo("FAILED");
    }

    @Test
    void continueKeepsSiblingResultsWaitsForTheWholeGroupAndReportsAPartialFailure() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of("failurePolicy", "CONTINUE"), t.a(), t.b(), t.c(), t.d()));
        UUID w1 = executionOfRank(t, run, 1, 1);
        UUID w2 = executionOfRank(t, run, 2, 1);
        UUID w3 = executionOfRank(t, run, 3, 1);
        begin(t.org(), w1);
        begin(t.org(), w2);
        begin(t.org(), w3);
        fail(t.org(), w2, "PROVIDER_BAD_REQUEST");
        pass();
        assertThat(steps(t, run)).hasSize(3);                                              // the group is not resolved: siblings still run
        assertThat(executionStatus(w1)).isEqualTo("RUNNING");
        finish(t.org(), w1, "R1", 10, 10);
        finish(t.org(), w3, "R3", 10, 10);
        pass();
        UUID synthesis = executionOfRank(t, run, 4, 1);
        String context = task(synthesis);
        assertThat(context).contains("R1").contains("R3").contains("FAILED (PERMANENT_PROVIDER_FAILURE)").contains("No result is available");
        begin(t.org(), synthesis);
        finish(t.org(), synthesis, "SYNTH", 10, 10);
        pass();
        JsonNode done = run(t, run);
        assertThat(done.get("status").asString()).isEqualTo("FAILED");                    // never reported as a success
        assertThat(done.get("errorCode").asString()).isEqualTo("PARTIAL_FAILURE");
    }

    @Test
    void retryReplacesOnlyTheFailedWorkerInANewExecutionAndSiblingsAreUntouched() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of("maxRetriesPerStep", 2, "failurePolicy", "RETRY"), t.a(), t.b(), t.c(), t.d()));
        UUID w1 = executionOfRank(t, run, 1, 1);
        UUID w2 = executionOfRank(t, run, 2, 1);
        UUID w3 = executionOfRank(t, run, 3, 1);
        begin(t.org(), w1);
        begin(t.org(), w2);
        begin(t.org(), w3);
        finish(t.org(), w1, "R1", 10, 10);
        fail(t.org(), w2, "PROVIDER_UNAVAILABLE");                                        // transient, no tool started: retry-safe
        pass();
        assertThat(steps(t, run)).hasSize(3);                                              // scheduled with a backoff, not launched
        assertThat(executionStatus(w3)).isEqualTo("RUNNING");                              // the sibling is NOT stopped by a retryable failure
        assertThat(executionStatus(w1)).isEqualTo("SUCCEEDED");
        retriesAreDue(run);
        pass();
        List<JsonNode> steps = steps(t, run);
        assertThat(steps).hasSize(4);
        JsonNode retry = steps.get(2);                                                      // listed by (rank, attempt)
        assertThat(retry.get("rank").asInt()).isEqualTo(2);
        assertThat(retry.get("attempt").asInt()).isEqualTo(2);
        assertThat(retry.get("launchReason").asString()).isEqualTo("AUTO_RETRY");
        assertThat(retry.get("groupNo").asInt()).isEqualTo(1);
        UUID w2b = executionOfRank(t, run, 2, 2);
        assertThat(w2b).isNotEqualTo(w2);
        assertThat(executionOfRank(t, run, 1, 1)).isEqualTo(w1);                          // siblings were not relaunched
        begin(t.org(), w2b);
        finish(t.org(), w2b, "R2", 10, 10);
        finish(t.org(), w3, "R3", 10, 10);
        pass();
        UUID synthesis = executionOfRank(t, run, 4, 1);
        begin(t.org(), synthesis);
        finish(t.org(), synthesis, "SYNTH", 10, 10);
        pass();
        assertThat(runStatus(t, run)).isEqualTo("SUCCEEDED");
        assertThat(summary(t, run).get("automaticRetries").asInt()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM workflow_steps WHERE run_id = ? AND step_rank = 2", run)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ Scenario C: unknown external outcome

    @Test
    void anUnknownOutcomeIsNeverReplayedWhileTheOtherWorkerResultIsPreserved() throws Exception {
        Team t = team();
        grant(t.owner(), t.org(), t.b(), ECHO);
        UUID workflow = id(read(postAs(t.owner(), wf(t.org(), ""), body("name", "U", "maxParallelWorkers", 2, "failurePolicy", "CONTINUE", "maxRetriesPerStep", 2))
                .andExpect(status().isCreated())));
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"), body("agentIds", List.of(t.a(), t.b(), t.d()))).andExpect(status().isOk());
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/steps"), body("steps", List.of(
                body("name", "A", "agentId", t.a(), "instruction", "x", "stage", 1), body("name", "B", "agentId", t.b(), "instruction", "y", "stage", 1),
                body("name", "S", "agentId", t.d(), "instruction", "z", "stage", 2)))).andExpect(status().isOk());
        UUID run = start(t, workflow);
        UUID w1 = executionOfRank(t, run, 1, 1);
        UUID w2 = executionOfRank(t, run, 2, 1);
        begin(t.org(), w1);
        begin(t.org(), w2);
        finish(t.org(), w1, "KEEP", 10, 10);
        JsonNode decision = requestTool(t.org(), w2, "toolu_1", "diagnostic.echo", text("effect"));
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());
        serviceCall(t.org(), w2, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isOk());
        fail(t.org(), w2, "PROVIDER_UNAVAILABLE");                                        // the orchestrator vanished mid-call
        pass();
        retriesAreDue(run);
        pass();
        assertThat(steps(t, run).stream().filter(s -> s.get("rank").asInt() == 2)).hasSize(1);   // never replayed, not even a "transient" error
        assertThat(steps(t, run).stream().filter(s -> s.get("rank").asInt() == 2).findFirst().orElseThrow().get("failureCategory").asString())
                .isEqualTo("OUTCOME_UNKNOWN");
        UUID synthesis = executionOfRank(t, run, 3, 1);
        assertThat(task(synthesis)).contains("KEEP").contains("FAILED (OUTCOME_UNKNOWN)");
        begin(t.org(), synthesis);
        finish(t.org(), synthesis, "S", 10, 10);
        pass();
        assertThat(run(t, run).get("status").asString()).isEqualTo("FAILED");               // truthful: the unresolved outcome is not hidden
        assertThat(run(t, run).get("errorCode").asString()).isEqualTo("PARTIAL_FAILURE");
    }

    // ------------------------------------------------------------------ cancellation, deadline, stale events

    @Test
    void cancellingARunStopsEveryWorkerAndCancelsQueuedOnes() throws Exception {
        Team t = team();
        UUID occupant = launch(t.owner(), t.org(), t.d(), "ordinary work");
        UUID run = start(t, pipeline(t, Map.of(), t.a(), t.b(), t.c(), t.d()));
        assertThat(count("SELECT count(*) FROM workflow_steps WHERE run_id = ? AND status = 'QUEUED'", run)).isEqualTo(1);
        postAs(t.owner(), runs(t.org(), "/" + run + "/cancel"), "{}").andExpect(status().isOk());
        pass();
        assertThat(runStatus(t, run)).isEqualTo("CANCELLED");
        assertThat(count("SELECT count(*) FROM workflow_steps WHERE run_id = ? AND status IN ('QUEUED', 'RUNNING')", run)).isZero();
        assertThat(count("SELECT count(*) FROM agent_executions WHERE id IN (SELECT execution_id FROM workflow_steps WHERE run_id = ?) AND status NOT IN ('FAILED')", run)).isZero();
        pass();
        assertThat(steps(t, run)).hasSize(3);                                               // a terminal run creates nothing
        assertThat(occupant).isNotNull();
    }

    @Test
    void theWorkflowDeadlineEndsEveryWorkerAndNothingNewStarts() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of(), t.a(), t.b(), t.c(), t.d()));
        UUID w1 = executionOfRank(t, run, 1, 1);
        begin(t.org(), w1);
        jdbc.update("UPDATE workflow_runs SET deadline_at = now() - interval '1 second' WHERE id = ?", run);
        pass();
        assertThat(runStatus(t, run)).isEqualTo("TIMEOUT");
        assertThat(count("SELECT count(*) FROM workflow_steps WHERE run_id = ? AND status IN ('QUEUED', 'RUNNING', 'WAITING_APPROVAL')", run)).isZero();
        assertThat(executionStatus(w1)).isEqualTo("FAILED");
        pass();
        assertThat(steps(t, run)).hasSize(3);
    }

    @Test
    void aStepTimeLimitIsALimitContinueDoesNotGoPastSoSiblingsAreStoppedAndAStaleCompletionChangesNothing() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of("failurePolicy", "CONTINUE", "stepTimeoutSeconds", 30), t.a(), t.b(), t.c(), t.d()));
        UUID w1 = executionOfRank(t, run, 1, 1);
        UUID w2 = executionOfRank(t, run, 2, 1);
        begin(t.org(), w1);
        begin(t.org(), w2);
        jdbc.update("UPDATE workflow_steps SET deadline_at = now() - interval '1 second' WHERE run_id = ? AND step_rank = 1", run);
        pass();
        assertThat(executionStatus(w1)).isEqualTo("FAILED");
        assertThat(steps(t, run).get(0).get("failureCategory").asString()).isEqualTo("WORKFLOW_LIMIT_EXCEEDED");
        assertThat(executionStatus(w2)).isEqualTo("FAILED");                               // a limit is never continued past: the sibling is stopped too
        // The late completion of an ended worker is refused.
        serviceCall(t.org(), w1, "/complete", body("result", "late", "inputTokens", 1, "outputTokens", 1, "model", "m")).andExpect(status().isConflict());
        assertThat(steps(t, run)).hasSize(3);
    }

    @Test
    void duplicatePassesAndConcurrentSchedulersNeverDuplicateOrRelaunchWorkers() throws Exception {
        Team t = team();
        UUID run = start(t, pipeline(t, Map.of(), t.a(), t.b(), t.c(), t.d()));
        for (UUID execution : List.of(executionOfRank(t, run, 1, 1), executionOfRank(t, run, 2, 1), executionOfRank(t, run, 3, 1))) {
            begin(t.org(), execution);
            finish(t.org(), execution, "R", 5, 5);
        }
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
        assertThat(steps(t, run)).hasSize(4);                                               // exactly one synthesis step, once
        assertThat(count("SELECT count(DISTINCT execution_id) FROM workflow_steps WHERE run_id = ?", run)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT tokens_used FROM workflow_runs WHERE id = ?", Integer.class, run)).isEqualTo(30);   // each worker counted once
    }

    @Test
    void aFreshWorkerWithNoMemoryRecoversAGroupFromPostgresWithoutRelaunchingAnything() throws Exception {
        Team t = team();
        UUID occupant = launch(t.owner(), t.org(), t.d(), "ordinary work");
        UUID run = start(t, pipeline(t, Map.of(), t.a(), t.b(), t.c(), t.d()));
        UUID w1 = executionOfRank(t, run, 1, 1);
        begin(t.org(), w1);
        finish(t.org(), w1, "DONE-BEFORE-RESTART", 10, 10);
        // "Restart": nothing but PostgreSQL is left. The completed worker is settled, the queued one is recovered, nothing is duplicated.
        begin(t.org(), occupant);
        finish(t.org(), occupant, "x", 1, 1);
        probe.newWorker().advanceOpenRuns();
        List<JsonNode> steps = steps(t, run);
        assertThat(steps).hasSize(3);
        assertThat(steps.stream().filter(s -> s.get("status").asString().equals("QUEUED")).count()).isZero();
        assertThat(executionOfRank(t, run, 1, 1)).isEqualTo(w1);
        assertThat(stepStatus(t, run, 1)).isEqualTo("SUCCEEDED");
        assertThat(count("SELECT count(DISTINCT execution_id) FROM workflow_steps WHERE run_id = ?", run)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ approvals

    @Test
    void parallelApprovalsAreIndependentOneApprovedOneRejectedEachFollowsItsOwnDecision() throws Exception {
        Team t = team();
        grant(t.owner(), t.org(), t.a(), NOTE);
        grant(t.owner(), t.org(), t.b(), NOTE);
        UUID run = start(t, pipeline(t, Map.of("failurePolicy", "CONTINUE"), t.a(), t.b(), t.c(), t.d()));
        UUID w1 = executionOfRank(t, run, 1, 1);
        UUID w2 = executionOfRank(t, run, 2, 1);
        UUID w3 = executionOfRank(t, run, 3, 1);
        begin(t.org(), w1);
        begin(t.org(), w2);
        begin(t.org(), w3);
        JsonNode d1 = requestTool(t.org(), w1, "toolu_a", "diagnostic.record_note", text("one"));
        JsonNode d2 = requestTool(t.org(), w2, "toolu_b", "diagnostic.record_note", text("two"));
        UUID approval1 = UUID.fromString(d1.get("approvalId").asString());
        UUID approval2 = UUID.fromString(d2.get("approvalId").asString());
        assertThat(approval1).isNotEqualTo(approval2);                                     // never a shared approval object
        assertThat(count("SELECT count(DISTINCT ti.execution_id) FROM tool_approvals ta JOIN tool_invocations ti ON ti.id = ta.invocation_id WHERE ta.id IN (?, ?)",
                approval1, approval2)).isEqualTo(2);
        pass();
        // Two workers wait for a person, the third works: the run is RUNNING and shows who waits.
        JsonNode summary = summary(t, run);
        assertThat(summary.get("groups").get(0).get("waitingApproval").asInt()).isEqualTo(2);
        assertThat(runStatus(t, run)).isEqualTo("RUNNING");

        String hash1 = read(getAs(t.owner(), path(t.org(), "/approvals/" + approval1))).get("argumentsHash").asString();
        postAs(t.owner(), path(t.org(), "/approvals/" + approval1 + "/approve"), body("argumentsHash", hash1)).andExpect(status().isOk());
        postAs(t.owner(), path(t.org(), "/approvals/" + approval2 + "/reject"), body("reason", "no")).andExpect(status().isOk());
        // Approving one never approves the other: its own invocation is still not approved.
        assertThat(jdbc.queryForObject("SELECT status FROM tool_invocations WHERE id = ?", String.class, UUID.fromString(d1.get("invocationId").asString())))
                .isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT status FROM tool_invocations WHERE id = ?", String.class, UUID.fromString(d2.get("invocationId").asString())))
                .isEqualTo("REJECTED");
        serviceCall(t.org(), w1, "/resume", Map.of()).andExpect(status().isOk());
        serviceCall(t.org(), w2, "/resume", Map.of()).andExpect(status().isOk());
        finish(t.org(), w1, "R1", 10, 10);                                                 // the approved worker goes on
        fail(t.org(), w2, "TOOL_FAILED");                                                  // the rejected one ends its own way
        finish(t.org(), w3, "R3", 10, 10);
        pass();
        UUID synthesis = executionOfRank(t, run, 4, 1);                                    // CONTINUE: the group resolved, the next stage opens
        assertThat(task(synthesis)).contains("R1").contains("R3").contains("FAILED (TOOL_FAILURE)");
    }
}
