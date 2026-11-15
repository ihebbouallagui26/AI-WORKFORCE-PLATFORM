package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.agent.MaintenanceProbe;
import com.aiworkforce.core.agent.WorkflowProbe;
import com.aiworkforce.core.support.RecordingOrchestrator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Parallel delegation by the AI coordinator (C3.4): several accepted delegations in one turn become ONE group of concurrent
 * workers; the coordinator resumes only after the whole group is resolved. The Core API validates and persists every delegation
 * before anything is dispatched; the orchestrator is played through its internal API.
 */
@Import({WorkflowProbe.class, MaintenanceProbe.class})
@TestPropertySource(properties = {"app.workflows.maintenance-ms=3600000", "app.executions.approval-maintenance-ms=3600000",
        "app.workflows.retry-base-delay=PT30S", "app.workflows.retry-max-delay=PT30S", "app.workflows.worker-token-reservation=1000"})
class WorkflowParallelCoordinatorIntegrationTest extends ToolFlows {

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

    private record Team(TestUser owner, UUID org, UUID boss, UUID a, UUID b, UUID c) {
    }

    private Team team() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "ParCoord " + UUID.randomUUID().toString().substring(0, 8));
        return new Team(owner, org, agentWithSecret(owner, org, "Supervisor"), agentWithSecret(owner, org, "Researcher A"),
                agentWithSecret(owner, org, "Researcher B"), agentWithSecret(owner, org, "Analyst C"));
    }

    private static String wf(UUID org, String suffix) {
        return path(org, "/workflows" + suffix);
    }

    private static String runs(UUID org, String suffix) {
        return path(org, "/workflow-runs" + suffix);
    }

    private UUID run(Team t, Map<String, Object> settings) throws Exception {
        Map<String, Object> create = new HashMap<>(settings);
        create.put("name", "ParCoord");
        create.put("mode", "COORDINATED");
        create.putIfAbsent("maxParallelWorkers", 3);
        UUID workflow = id(read(postAs(t.owner(), wf(t.org(), ""), create).andExpect(status().isCreated())));
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"),
                body("agentIds", List.of(t.boss(), t.a(), t.b(), t.c()), "coordinatorAgentId", t.boss())).andExpect(status().isOk());
        return id(read(postAs(t.owner(), wf(t.org(), "/" + workflow + "/runs"), body("objective", "Analyze the European SaaS market"))
                .andExpect(status().isAccepted())));
    }

    private JsonNode runView(Team t, UUID run) throws Exception {
        return read(getAs(t.owner(), runs(t.org(), "/" + run)).andExpect(status().isOk()));
    }

    private List<JsonNode> steps(Team t, UUID run) throws Exception {
        JsonNode content = read(getAs(t.owner(), runs(t.org(), "/" + run + "/steps?size=100")).andExpect(status().isOk())).get("content");
        List<JsonNode> list = new ArrayList<>();
        for (int i = 0; i < content.size(); i++) {
            list.add(content.get(i));
        }
        return list;
    }

    private UUID executionOf(Team t, UUID run, int rank, int attempt) throws Exception {
        for (JsonNode s : steps(t, run)) {
            if (s.get("rank").asInt() == rank && s.get("attempt").asInt() == attempt && !s.get("executionId").isNull()) {
                return UUID.fromString(s.get("executionId").asString());
            }
        }
        throw new IllegalStateException("no execution for rank " + rank);
    }

    private void pass() {
        probe.newWorker().advanceOpenRuns();
    }

    private void begin(UUID org, UUID execution) throws Exception {
        serviceCall(org, execution, "/start", Map.of()).andExpect(status().isOk());
    }

    private JsonNode delegate(UUID org, UUID turn, String callId, String ref, String task) throws Exception {
        return read(serviceCall(org, turn, "/workflow-actions", body("toolCallId", callId, "action", "workflow.delegate",
                "arguments", Map.of("participantRef", ref, "task", task))).andExpect(status().isOk()));
    }

    private JsonNode finishAction(UUID org, UUID turn, String callId, String result) throws Exception {
        return read(serviceCall(org, turn, "/workflow-actions", body("toolCallId", callId, "action", "workflow.finish",
                "arguments", Map.of("result", result))).andExpect(status().isOk()));
    }

    private void complete(UUID org, UUID execution, String result, int input, int output) throws Exception {
        serviceCall(org, execution, "/complete", body("result", result, "inputTokens", input, "outputTokens", output, "model", "m")).andExpect(status().isOk());
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

    /** The coordinator delegates three independent tasks in ONE turn and ends the turn. */
    private void delegateThree(Team t, UUID run) throws Exception {
        UUID turn = executionOf(t, run, 1, 1);
        if (!executionStatus(turn).equals("RUNNING")) {
            begin(t.org(), turn);
        }
        assertThat(delegate(t.org(), turn, "c1", "worker_1", "Analyze competitors").get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(delegate(t.org(), turn, "c2", "worker_2", "Investigate pricing models").get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(delegate(t.org(), turn, "c3", "worker_3", "Identify market trends").get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(steps(t, run)).hasSize(1);                                    // accepted and recorded; nothing exists before the turn ends
        complete(t.org(), turn, "delegating", 100, 50);
        pass();
    }

    // ------------------------------------------------------------------ multi-delegation, fan-out and fan-in

    @Test
    void theCoordinatorDelegatesThreeIndependentTasksInOneTurnTheyOverlapAndItResumesAfterTheWholeGroup() throws Exception {
        Team t = team();
        UUID real = run(t, Map.of());
        JsonNode started = read(serviceCall(t.org(), executionOf(t, real, 1, 1), "/start", Map.of()).andExpect(status().isOk()));
        assertThat(started.get("maxDelegationsPerTurn").asInt()).isEqualTo(3);
        assertThat(task(executionOf(t, real, 1, 1))).contains("up to 3 times");
        delegateThree(t, real);
        List<JsonNode> steps = steps(t, real);
        assertThat(steps).hasSize(4);                                              // the turn + three workers, dispatched together
        assertThat(steps.stream().filter(s -> s.get("role").asString().equals("WORKER") && s.get("status").asString().equals("RUNNING")).count()).isEqualTo(3);
        UUID w1 = executionOf(t, real, 2, 1);
        UUID w2 = executionOf(t, real, 3, 1);
        UUID w3 = executionOf(t, real, 4, 1);
        assertThat(List.of(w1, w2, w3)).doesNotHaveDuplicates();
        assertThat(task(w1)).contains("Analyze competitors");
        assertThat(task(w2)).contains("Investigate pricing models").doesNotContain("Analyze competitors");   // no shared conversation between workers
        begin(t.org(), w1);
        begin(t.org(), w2);
        begin(t.org(), w3);
        assertThat(List.of(executionStatus(w1), executionStatus(w2), executionStatus(w3))).containsOnly("RUNNING");   // deterministic overlap

        complete(t.org(), w1, "COMPETITORS: three.", 100, 10);
        complete(t.org(), w2, "PRICING: freemium.", 100, 10);
        pass();
        assertThat(steps(t, real)).hasSize(4);                                     // two of three: the coordinator does NOT resume
        complete(t.org(), w3, "TRENDS: ai.", 100, 10);
        pass();
        List<JsonNode> after = steps(t, real);
        assertThat(after).hasSize(5);
        assertThat(after.get(4).get("role").asString()).isEqualTo("COORDINATOR");
        UUID turn2 = executionOf(t, real, 5, 1);
        String context = task(turn2);
        assertThat(context).contains("COMPETITORS: three.").contains("PRICING: freemium.").contains("TRENDS: ai.").contains("DATA produced by workers");
        assertThat(context).doesNotContain(t.a().toString()).doesNotContain(t.b().toString());   // opaque refs only
        begin(t.org(), turn2);
        assertThat(finishAction(t.org(), turn2, "f1", "Opportunity report").get("status").asString()).isEqualTo("ACCEPTED");
        complete(t.org(), turn2, "done", 80, 20);
        pass();

        JsonNode done = runView(t, real);
        assertThat(done.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("result").asString()).isEqualTo("Opportunity report");
        assertThat(done.get("tokensUsed").asInt()).isEqualTo(150 + 110 + 110 + 110 + 100);   // coordinator turns and every worker, once
        JsonNode summary = read(getAs(t.owner(), runs(t.org(), "/" + real + "/summary")).andExpect(status().isOk()));
        assertThat(summary.get("peakConcurrency").asInt()).isEqualTo(3);
        assertThat(summary.get("groups")).hasSize(1);
        assertThat(summary.get("coordinatorTokens").asLong()).isEqualTo(250);
        assertThat(summary.get("workerTokens").asLong()).isEqualTo(330);
        assertThat(count("SELECT count(*) FROM workflow_actions WHERE run_id = ? AND status = 'ACCEPTED'", real)).isEqualTo(4);
    }

    @Test
    void theNumberOfDelegationsPerTurnWorkerStepsAndFinishCombinationsAreBounded() throws Exception {
        Team t = team();
        UUID run = run(t, Map.of());
        UUID turn = executionOf(t, run, 1, 1);
        begin(t.org(), turn);
        delegate(t.org(), turn, "c1", "worker_1", "task one");
        delegate(t.org(), turn, "c2", "worker_2", "task two");
        delegate(t.org(), turn, "c3", "worker_3", "task three");
        assertThat(delegate(t.org(), turn, "c4", "worker_1", "task four").get("code").asString()).isEqualTo("GROUP_FULL");
        assertThat(finishAction(t.org(), turn, "f1", "x").get("code").asString()).isEqualTo("ACTION_ALREADY_TAKEN");   // never combined
        assertThat(count("SELECT count(*) FROM workflow_actions WHERE run_id = ? AND status = 'ACCEPTED'", run)).isEqualTo(3);

        // A finish alone, then no delegation.
        UUID other = run(t, Map.of());
        UUID turnB = executionOf(t, other, 1, 1);
        begin(t.org(), turnB);
        assertThat(finishAction(t.org(), turnB, "f1", "nothing to do").get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(delegate(t.org(), turnB, "c1", "worker_1", "late").get("code").asString()).isEqualTo("ACTION_ALREADY_TAKEN");

        // Pending delegations of the turn count against the worker-step capacity.
        UUID limited = run(t, Map.of("maxSteps", 2));
        UUID turnC = executionOf(t, limited, 1, 1);
        begin(t.org(), turnC);
        delegate(t.org(), turnC, "c1", "worker_1", "one");
        delegate(t.org(), turnC, "c2", "worker_2", "two");
        assertThat(delegate(t.org(), turnC, "c3", "worker_3", "three").get("code").asString()).isEqualTo("STEP_LIMIT");
    }

    @Test
    void delegationsAreIdempotentAndTheSameTaskIsNeverGivenTwiceToTheSameWorker() throws Exception {
        Team t = team();
        UUID run = run(t, Map.of());
        UUID turn = executionOf(t, run, 1, 1);
        begin(t.org(), turn);
        delegate(t.org(), turn, "c1", "worker_1", "Analyze competitors");
        assertThat(delegate(t.org(), turn, "c1", "worker_1", "Analyze competitors").get("status").asString()).isEqualTo("ACCEPTED");   // replay
        serviceCall(t.org(), turn, "/workflow-actions", body("toolCallId", "c1", "action", "workflow.delegate",
                "arguments", Map.of("participantRef", "worker_1", "task", "A different task"))).andExpect(status().isConflict());
        assertThat(delegate(t.org(), turn, "c2", "worker_1", "analyze  COMPETITORS").get("code").asString()).isEqualTo("DUPLICATE_DELEGATION");
        assertThat(delegate(t.org(), turn, "c3", "worker_1", "A genuinely different task").get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(count("SELECT count(*) FROM workflow_actions WHERE execution_id = ? AND status = 'ACCEPTED'", turn)).isEqualTo(2);
        complete(t.org(), turn, "delegating", 10, 10);
        pass();
        assertThat(steps(t, run).stream().filter(s -> s.get("role").asString().equals("WORKER")).count()).isEqualTo(2);   // no duplicate worker
        pass();
        pass();
        assertThat(steps(t, run)).hasSize(3);
    }

    @Test
    void aSequentialCoordinatedWorkflowKeepsItsOneActionPerTurnContract() throws Exception {
        Team t = team();
        UUID run = run(t, Map.of("maxParallelWorkers", 1));
        UUID turn = executionOf(t, run, 1, 1);
        JsonNode started = read(serviceCall(t.org(), turn, "/start", Map.of()).andExpect(status().isOk()));
        assertThat(started.get("maxDelegationsPerTurn").asInt()).isEqualTo(1);
        delegate(t.org(), turn, "c1", "worker_1", "one");
        assertThat(delegate(t.org(), turn, "c2", "worker_2", "two").get("code").asString()).isEqualTo("ACTION_ALREADY_TAKEN");
        complete(t.org(), turn, "ok", 5, 5);
        pass();
        assertThat(steps(t, run)).hasSize(2);
        assertThat(steps(t, run).get(1).get("groupNo").isNull()).isTrue();                  // the sequential path, untouched
    }

    // ------------------------------------------------------------------ failure policies and stale events

    @Test
    void failFastStopsTheSiblingsAndALateActionOfTheEndedTurnCannotCreateWork() throws Exception {
        Team t = team();
        UUID run = run(t, Map.of());
        UUID turn1 = executionOf(t, run, 1, 1);
        delegateThree(t, run);
        UUID w1 = executionOf(t, run, 2, 1);
        UUID w2 = executionOf(t, run, 3, 1);
        UUID w3 = executionOf(t, run, 4, 1);
        begin(t.org(), w1);
        begin(t.org(), w2);
        begin(t.org(), w3);
        serviceCall(t.org(), w1, "/fail", body("errorCode", "PROVIDER_BAD_REQUEST", "message", "x")).andExpect(status().isOk());
        complete(t.org(), w2, "KEEP", 10, 10);
        pass();
        assertThat(executionStatus(w3)).isEqualTo("FAILED");                               // the running sibling was stopped
        pass();
        assertThat(runView(t, run).get("status").asString()).isEqualTo("FAILED");
        // A stale action of the coordinator turn that created the group cannot create or change anything.
        serviceCall(t.org(), turn1, "/workflow-actions", body("toolCallId", "late", "action", "workflow.delegate",
                "arguments", Map.of("participantRef", "worker_1", "task", "more"))).andExpect(status().isConflict());
        assertThat(steps(t, run)).hasSize(4);
        assertThat(steps(t, run).get(2).get("resultSummary").asString()).isEqualTo("KEEP");
    }

    @Test
    void continueGivesTheCoordinatorAllResultsAndFailuresOnlyAfterTheWholeGroupIsResolved() throws Exception {
        Team t = team();
        UUID run = run(t, Map.of("failurePolicy", "CONTINUE"));
        delegateThree(t, run);
        UUID w1 = executionOf(t, run, 2, 1);
        UUID w2 = executionOf(t, run, 3, 1);
        UUID w3 = executionOf(t, run, 4, 1);
        begin(t.org(), w1);
        begin(t.org(), w2);
        begin(t.org(), w3);
        serviceCall(t.org(), w2, "/fail", body("errorCode", "PROVIDER_BAD_REQUEST", "message", "x")).andExpect(status().isOk());
        pass();
        assertThat(steps(t, run)).hasSize(4);                                              // siblings still run: no resumption yet
        complete(t.org(), w1, "R1", 10, 10);
        complete(t.org(), w3, "R3", 10, 10);
        pass();
        UUID turn2 = executionOf(t, run, 5, 1);
        String context = task(turn2);
        assertThat(context).contains("R1").contains("R3").contains("FAILED (PERMANENT_PROVIDER_FAILURE").contains("nothing external happened");
        begin(t.org(), turn2);
        finishAction(t.org(), turn2, "f1", "Report from two of three");
        complete(t.org(), turn2, "done", 10, 10);
        pass();
        assertThat(runView(t, run).get("status").asString()).isEqualTo("SUCCEEDED");        // the COORDINATOR decided; the failure is in the record
    }

    @Test
    void retryReplacesOnlyTheFailedWorkerAndTheCoordinatorStillWaitsForTheGroup() throws Exception {
        Team t = team();
        UUID run = run(t, Map.of("maxRetriesPerStep", 2, "failurePolicy", "RETRY"));
        delegateThree(t, run);
        UUID w1 = executionOf(t, run, 2, 1);
        UUID w2 = executionOf(t, run, 3, 1);
        UUID w3 = executionOf(t, run, 4, 1);
        begin(t.org(), w1);
        begin(t.org(), w2);
        begin(t.org(), w3);
        complete(t.org(), w1, "R1", 10, 10);
        complete(t.org(), w3, "R3", 10, 10);
        serviceCall(t.org(), w2, "/fail", body("errorCode", "PROVIDER_UNAVAILABLE", "message", "x")).andExpect(status().isOk());
        pass();
        assertThat(steps(t, run)).hasSize(4);                                              // the group is not resolved: a retry is scheduled
        jdbc.update("UPDATE workflow_steps SET next_attempt_at = now() - interval '1 second' WHERE run_id = ? AND next_attempt_at IS NOT NULL", run);
        pass();
        assertThat(steps(t, run)).hasSize(5);
        UUID w2b = executionOf(t, run, 3, 2);
        assertThat(w2b).isNotEqualTo(w2);
        assertThat(task(w2b)).contains("Investigate pricing models");
        begin(t.org(), w2b);
        complete(t.org(), w2b, "R2", 10, 10);
        pass();
        assertThat(steps(t, run).get(steps(t, run).size() - 1).get("role").asString()).isEqualTo("COORDINATOR");
        assertThat(count("SELECT count(*) FROM workflow_steps WHERE run_id = ? AND step_rank IN (2, 4) AND attempt > 1", run)).isZero();   // siblings untouched
    }

    // ------------------------------------------------------------------ authorization, usage of failed attempts, cancellation

    @Test
    void aRequesterWhoLostTheRightToExecuteCannotKeepDelegatingAndNoWorkerStarts() throws Exception {
        Team t = team();
        TestUser runner = newUser("runner");
        UUID membership = join(t.owner(), t.org(), runner, "MEMBER");
        giveCustomRole(t.owner(), t.org(), membership, "Runner", List.of("workflow.read", "workflow.execute", "agent.execute", "agent.read"));
        UUID workflow = id(read(postAs(t.owner(), wf(t.org(), ""), body("name", "C", "mode", "COORDINATED", "maxParallelWorkers", 3)).andExpect(status().isCreated())));
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"),
                body("agentIds", List.of(t.boss(), t.a(), t.b()), "coordinatorAgentId", t.boss())).andExpect(status().isOk());
        UUID run = id(read(postAs(runner, wf(t.org(), "/" + workflow + "/runs"), body("objective", "x")).andExpect(status().isAccepted())));
        UUID turn = executionOf(t, run, 1, 1);
        begin(t.org(), turn);
        postAs(t.owner(), path(t.org(), "/members/" + membership + "/suspend"), "{}").andExpect(status().isOk());
        assertThat(delegate(t.org(), turn, "c1", "worker_1", "work").get("code").asString()).isEqualTo("REQUESTER_NOT_AUTHORIZED");
        assertThat(steps(t, run)).hasSize(1);
    }

    @Test
    void aFailedAttemptsReportedUsageCountsTowardTheWorkflowBudget() throws Exception {
        Team t = team();
        UUID run = run(t, Map.of());
        delegateThree(t, run);
        UUID w1 = executionOf(t, run, 2, 1);
        begin(t.org(), w1);
        serviceCall(t.org(), w1, "/fail", body("errorCode", "PROVIDER_BAD_REQUEST", "message", "x", "inputTokens", 400, "outputTokens", 100)).andExpect(status().isOk());
        pass();
        JsonNode summary = read(getAs(t.owner(), runs(t.org(), "/" + run + "/summary")).andExpect(status().isOk()));
        assertThat(summary.get("workerTokens").asLong()).isEqualTo(500);                    // the failed attempt consumed provider tokens: they count
        assertThat(runView(t, run).get("tokensUsed").asInt()).isGreaterThanOrEqualTo(150 + 500);
        serviceCall(t.org(), executionOf(t, run, 3, 1), "/fail", body("errorCode", "PROVIDER_BAD_REQUEST", "message", "x", "inputTokens", -5)).andExpect(status().isBadRequest());
    }

    @Test
    void cancellingWhileTheGroupRunsStopsAllWorkersAndAnotherOrganizationSeesNothing() throws Exception {
        Team t = team();
        Team other = team();
        UUID run = run(t, Map.of());
        delegateThree(t, run);
        postAs(t.owner(), runs(t.org(), "/" + run + "/cancel"), "{}").andExpect(status().isOk());
        pass();
        assertThat(runView(t, run).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(count("SELECT count(*) FROM workflow_steps WHERE run_id = ? AND status IN ('QUEUED', 'RUNNING', 'WAITING_APPROVAL')", run)).isZero();
        getAs(other.owner(), runs(other.org(), "/" + run + "/summary")).andExpect(status().isNotFound());
        getAs(other.owner(), runs(other.org(), "/" + run + "/steps")).andExpect(status().isNotFound());
        pass();
        assertThat(steps(t, run)).hasSize(4);                                               // a terminal run is never restarted
    }
}
