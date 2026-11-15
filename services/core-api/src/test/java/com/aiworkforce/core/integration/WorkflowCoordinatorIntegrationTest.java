package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.agent.MaintenanceProbe;
import com.aiworkforce.core.agent.WorkflowProbe;
import com.aiworkforce.core.support.RecordingOrchestrator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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
 * AI coordinator and dynamic delegation (C3.2) against the REAL persistence. The orchestrator is played through its
 * internal API and no provider is involved: this proves the Core API's decisions (what is allowed, what is recorded, what is
 * started next), not any model behaviour. Background maintenance is off so every scheduler pass is explicit.
 */
@Import({WorkflowProbe.class, MaintenanceProbe.class})
@TestPropertySource(properties = {"app.workflows.maintenance-ms=3600000", "app.executions.approval-maintenance-ms=3600000"})
class WorkflowCoordinatorIntegrationTest extends ToolFlows {

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

    private record Team(TestUser owner, UUID org, UUID boss, UUID researcher, UUID writer) {
    }

    private Team team() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Coordinated " + UUID.randomUUID().toString().substring(0, 8));
        return new Team(owner, org, agentWithSecret(owner, org, "Supervisor"), agentWithSecret(owner, org, "Researcher"),
                agentWithSecret(owner, org, "Writer"));
    }

    private static String wf(UUID org, String suffix) {
        return path(org, "/workflows" + suffix);
    }

    private static String runs(UUID org, String suffix) {
        return path(org, "/workflow-runs" + suffix);
    }

    private UUID coordinated(Team t, Map<String, Object> limits) throws Exception {
        Map<String, Object> create = new HashMap<>(limits);
        create.put("name", "Coordinated");
        create.put("mode", "COORDINATED");
        UUID workflow = id(read(postAs(t.owner(), wf(t.org(), ""), create).andExpect(status().isCreated())));
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"),
                body("agentIds", List.of(t.boss(), t.researcher(), t.writer()), "coordinatorAgentId", t.boss())).andExpect(status().isOk());
        return workflow;
    }

    private UUID start(Team t, UUID workflow) throws Exception {
        return id(read(postAs(t.owner(), wf(t.org(), "/" + workflow + "/runs"), body("objective", "Write a report on topic X"))
                .andExpect(status().isAccepted())));
    }

    private JsonNode run(Team t, UUID run) throws Exception {
        return read(getAs(t.owner(), runs(t.org(), "/" + run)).andExpect(status().isOk()));
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

    private JsonNode act(UUID org, UUID execution, String callId, String action, Map<String, Object> arguments) throws Exception {
        return read(serviceCall(org, execution, "/workflow-actions", body("toolCallId", callId, "action", action, "arguments", arguments))
                .andExpect(status().isOk()));
    }

    private JsonNode delegate(UUID org, UUID execution, String callId, String ref, String task) throws Exception {
        return act(org, execution, callId, "workflow.delegate", Map.of("participantRef", ref, "task", task));
    }

    private void runTurn(UUID org, UUID execution) throws Exception {
        serviceCall(org, execution, "/start", Map.of()).andExpect(status().isOk());
    }

    private void complete(UUID org, UUID execution, String result, int input, int output) throws Exception {
        serviceCall(org, execution, "/complete", body("result", result, "inputTokens", input, "outputTokens", output, "model", "m"))
                .andExpect(status().isOk());
    }

    private String task(UUID execution) {
        return jdbc.queryForObject("SELECT task FROM agent_executions WHERE id = ?", String.class, execution);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ------------------------------------------------------------------ the full scenario

    @Test
    void theCoordinatorDelegatesToTheResearcherThenTheWriterThenFinishes() throws Exception {
        Team t = team();
        UUID run = start(t, coordinated(t, Map.of()));
        assertThat(run(t, run).get("mode").asString()).isEqualTo("COORDINATED");

        // Turn 1: only the coordinator exists. Its context names workers by opaque refs, never by id.
        UUID turn1 = execution(t, run, 0);
        assertThat(steps(t, run)).hasSize(1);
        assertThat(steps(t, run).get(0).get("role").asString()).isEqualTo("COORDINATOR");
        assertThat(task(turn1)).contains("worker_1").contains("worker_2").contains("workflow.delegate")
                .doesNotContain(t.researcher().toString()).doesNotContain(t.writer().toString());
        JsonNode started = read(serviceCall(t.org(), turn1, "/start", Map.of()).andExpect(status().isOk()));
        assertThat(started.get("workflowRole").asString()).isEqualTo("COORDINATOR");
        assertThat(started.get("platformActions")).hasSize(2);
        String researcherRef = refOf(t, run, t.researcher());
        JsonNode accepted = delegate(t.org(), turn1, "call_1", researcherRef, "Collect the key facts about topic X");
        assertThat(accepted.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(steps(t, run)).hasSize(1);                        // the action is recorded; nothing is created before the turn ends
        complete(t.org(), turn1, "delegating", 100, 20);
        pass();

        // The worker is launched by the Core API with ITS own agent identity, the delegated task and nothing else.
        List<JsonNode> afterOne = steps(t, run);
        assertThat(afterOne).hasSize(2);
        UUID researcherExecution = execution(t, run, 1);
        assertThat(afterOne.get(1).get("role").asString()).isEqualTo("WORKER");
        assertThat(afterOne.get(1).get("agentId").asString()).isEqualTo(t.researcher().toString());
        assertThat(task(researcherExecution)).contains("Collect the key facts about topic X").contains("Write a report on topic X");
        runTurn(t.org(), researcherExecution);
        complete(t.org(), researcherExecution, "FACTS: 3 key facts.", 200, 50);
        pass();

        // Turn 2: the worker result is DATA inside the coordinator context.
        UUID turn2 = execution(t, run, 2);
        assertThat(task(turn2)).contains("FACTS: 3 key facts.").contains("DATA produced by workers");
        runTurn(t.org(), turn2);
        delegate(t.org(), turn2, "call_2", refOf(t, run, t.writer()), "Write the report from the facts");
        // Re-sending the same provider call changes nothing.
        assertThat(delegate(t.org(), turn2, "call_2", refOf(t, run, t.writer()), "Write the report from the facts").get("status").asString())
                .isEqualTo("ACCEPTED");
        complete(t.org(), turn2, "delegating", 150, 30);
        pass();
        UUID writerExecution = execution(t, run, 3);
        getAs(t.owner(), path(t.org(), "/executions/" + writerExecution)).andExpect(jsonPath("$.agentId").value(t.writer().toString()));
        runTurn(t.org(), writerExecution);
        complete(t.org(), writerExecution, "REPORT: done.", 300, 100);
        pass();

        // Turn 3: finish.
        UUID turn3 = execution(t, run, 4);
        runTurn(t.org(), turn3);
        assertThat(act(t.org(), turn3, "call_3", "workflow.finish", Map.of("result", "Final report ready.")).get("status").asString())
                .isEqualTo("ACCEPTED");
        assertThat(run(t, run).get("status").asString()).isEqualTo("RUNNING");   // the run ends when the coordinator turn does
        complete(t.org(), turn3, "done", 120, 40);
        pass();

        JsonNode done = run(t, run);
        assertThat(done.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("result").asString()).isEqualTo("Final report ready.");
        assertThat(done.get("tokensUsed").asInt()).isEqualTo(120 + 250 + 180 + 400 + 160);   // coordinator turns count too
        assertThat(steps(t, run)).extracting(s -> s.get("executionId").asString()).doesNotHaveDuplicates().hasSize(5);
        assertThat(count("SELECT count(*) FROM workflow_actions WHERE run_id = ? AND status = 'ACCEPTED'", run)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM audit_events WHERE organization_id = ? AND action = 'workflow.action.accepted'", t.org())).isEqualTo(3);
        JsonNode actions = read(getAs(t.owner(), runs(t.org(), "/" + run + "/actions")).andExpect(status().isOk())).get("content");
        assertThat(actions).hasSize(3);
        assertThat(actions.toString()).doesNotContain(t.researcher().toString());   // opaque refs only
    }

    private String refOf(Team t, UUID run, UUID agent) {
        String snapshot = jdbc.queryForObject("SELECT snapshot FROM workflow_runs WHERE id = ?", String.class, run);
        JsonNode members = readTree(snapshot).get("members");
        for (JsonNode m : members) {
            if (m.get("agentId").asString().equals(agent.toString())) {
                return m.get("ref").asString();
            }
        }
        throw new IllegalStateException("no such member");
    }

    private JsonNode readTree(String json) {
        return new tools.jackson.databind.ObjectMapper().readTree(json);
    }

    // ------------------------------------------------------------------ definition rules

    @Test
    void aCoordinatedDefinitionNeedsAParticipantCoordinatorAndHasNoStaticSteps() throws Exception {
        Team t = team();
        UUID workflow = id(read(postAs(t.owner(), wf(t.org(), ""), body("name", "C", "mode", "COORDINATED")).andExpect(status().isCreated())));
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"), body("agentIds", List.of(t.researcher()), "coordinatorAgentId", t.boss()))
                .andExpect(status().isBadRequest());
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"), body("agentIds", List.of(t.researcher(), t.boss())))
                .andExpect(status().isBadRequest());
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/steps"), body("steps", List.of(body("name", "s", "agentId", t.boss(), "instruction", "x"))))
                .andExpect(status().isConflict());
        // A pipeline never has a coordinator.
        UUID pipeline = id(read(postAs(t.owner(), wf(t.org(), ""), body("name", "P")).andExpect(status().isCreated())));
        putAs(t.owner(), wf(t.org(), "/" + pipeline + "/participants"), body("agentIds", List.of(t.boss()), "coordinatorAgentId", t.boss()))
                .andExpect(status().isBadRequest());
        // Without a worker, a coordinated workflow cannot start.
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"), body("agentIds", List.of(t.boss()), "coordinatorAgentId", t.boss()))
                .andExpect(status().isOk());
        postAs(t.owner(), wf(t.org(), "/" + workflow + "/runs"), body("objective", "x")).andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ validation of delegation

    @Test
    void invalidDelegationsAreRejectedAndRecordedAndCreateNothing() throws Exception {
        Team t = team();
        UUID run = start(t, coordinated(t, Map.of()));
        UUID turn = execution(t, run, 0);
        runTurn(t.org(), turn);

        assertThat(delegate(t.org(), turn, "c1", "worker_9", "do it").get("code").asString()).isEqualTo("UNKNOWN_PARTICIPANT");
        assertThat(delegate(t.org(), turn, "c2", t.researcher().toString(), "do it").get("code").asString()).isEqualTo("UNKNOWN_PARTICIPANT");
        assertThat(delegate(t.org(), turn, "c3", "coordinator", "do it").get("code").asString()).isEqualTo("SELF_DELEGATION");
        // Three rejections: the turn stops accepting anything (bounded correction, never an endless loop).
        JsonNode blocked = delegate(t.org(), turn, "c4", "worker_1", "a valid task");
        assertThat(blocked.get("status").asString()).isEqualTo("REJECTED");
        assertThat(blocked.get("code").asString()).isEqualTo("TOO_MANY_REJECTIONS");
        assertThat(count("SELECT count(*) FROM workflow_actions WHERE run_id = ? AND status = 'ACCEPTED'", run)).isZero();
        assertThat(count("SELECT count(*) FROM workflow_actions WHERE run_id = ? AND status = 'REJECTED'", run)).isEqualTo(4);
        assertThat(steps(t, run)).hasSize(1);
        assertThat(count("SELECT count(*) FROM audit_events WHERE organization_id = ? AND action = 'workflow.action.rejected'", t.org())).isEqualTo(4);
    }

    @Test
    void oneActionPerTurnAndUnknownOrMalformedActionsAreRejected() throws Exception {
        Team t = team();
        UUID run = start(t, coordinated(t, Map.of()));
        UUID turn = execution(t, run, 0);
        runTurn(t.org(), turn);

        assertThat(act(t.org(), turn, "u1", "workflow.addParticipant", Map.of("x", 1)).get("code").asString()).isEqualTo("UNKNOWN_ACTION");
        assertThat(act(t.org(), turn, "u2", "workflow.delegate", Map.of("participantRef", "worker_1")).get("code").asString()).isEqualTo("INVALID_TASK");
        assertThat(delegate(t.org(), turn, "ok", "worker_1", "first task").get("status").asString()).isEqualTo("ACCEPTED");
        // A second control action in the same turn is refused.
        assertThat(delegate(t.org(), turn, "dup", "worker_2", "second task").get("code").asString()).isEqualTo("ACTION_ALREADY_TAKEN");
        assertThat(count("SELECT count(*) FROM workflow_actions WHERE run_id = ? AND status = 'ACCEPTED'", run)).isEqualTo(1);
    }

    @Test
    void theSameCallIdWithDifferentArgumentsIsAConflictAndNeverARepeatedDelegation() throws Exception {
        Team t = team();
        UUID run = start(t, coordinated(t, Map.of()));
        UUID turn = execution(t, run, 0);
        runTurn(t.org(), turn);
        delegate(t.org(), turn, "same", "worker_1", "task A");
        serviceCall(t.org(), turn, "/workflow-actions", body("toolCallId", "same", "action", "workflow.delegate",
                "arguments", Map.of("participantRef", "worker_1", "task", "task B"))).andExpect(status().isConflict());
        assertThat(count("SELECT count(*) FROM workflow_actions WHERE execution_id = ?", turn)).isEqualTo(1);
    }

    @Test
    void theSameTaskIsNeverDelegatedTwiceToTheSameWorkerAndInputsMustBeCompletedWorkerSteps() throws Exception {
        Team t = team();
        UUID run = start(t, coordinated(t, Map.of()));
        UUID turn1 = execution(t, run, 0);
        runTurn(t.org(), turn1);
        assertThat(act(t.org(), turn1, "i1", "workflow.delegate", Map.of("participantRef", "worker_1", "task", "t", "inputRanks", List.of(7)))
                .get("code").asString()).isEqualTo("INVALID_INPUT");
        delegate(t.org(), turn1, "d1", "worker_1", "Collect   FACTS");
        complete(t.org(), turn1, "ok", 10, 10);
        pass();
        UUID worker = execution(t, run, 1);
        runTurn(t.org(), worker);
        complete(t.org(), worker, "result", 10, 10);
        pass();
        UUID turn2 = execution(t, run, 2);
        runTurn(t.org(), turn2);
        assertThat(delegate(t.org(), turn2, "d2", "worker_1", "collect facts").get("code").asString()).isEqualTo("DUPLICATE_DELEGATION");
    }

    // ------------------------------------------------------------------ who may act

    @Test
    void onlyACoordinatorTurnMayUseWorkflowActionsAndItNeverReachesMcpTools() throws Exception {
        Team t = team();
        UUID run = start(t, coordinated(t, Map.of()));
        UUID turn = execution(t, run, 0);
        runTurn(t.org(), turn);
        delegate(t.org(), turn, "d1", "worker_1", "work");
        // A coordinator cannot use an MCP tool.
        serviceCall(t.org(), turn, "/tool-calls", body("toolCallId", "t1", "toolCode", "diagnostic.record_note", "arguments", Map.of("note", "x")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("DENIED"))
                .andExpect(jsonPath("$.code").value("COORDINATOR_TOOLS_NOT_ALLOWED"));
        complete(t.org(), turn, "ok", 10, 10);
        pass();
        UUID worker = execution(t, run, 1);
        runTurn(t.org(), worker);
        // A worker cannot delegate (no worker-to-worker, no recursion).
        serviceCall(t.org(), worker, "/workflow-actions", body("toolCallId", "w1", "action", "workflow.delegate",
                "arguments", Map.of("participantRef", "worker_2", "task", "x"))).andExpect(status().isForbidden());
        // A plain execution outside any workflow neither.
        UUID plain = runningExecution(t.owner(), t.org(), t.boss());
        serviceCall(t.org(), plain, "/workflow-actions", body("toolCallId", "p1", "action", "workflow.finish",
                "arguments", Map.of("result", "x"))).andExpect(status().isForbidden());
        // A user token never reaches the internal endpoint.
        postAs(t.owner(), "/api/v1/internal/organizations/" + t.org() + "/executions/" + turn + "/workflow-actions",
                body("toolCallId", "u1", "action", "workflow.finish", "arguments", Map.of("result", "x"))).andExpect(status().isForbidden());
    }

    @Test
    void anotherOrganizationCannotReachTheActionsOfARun() throws Exception {
        Team t = team();
        Team other = team();
        UUID run = start(t, coordinated(t, Map.of()));
        UUID turn = execution(t, run, 0);
        runTurn(t.org(), turn);
        serviceCall(other.org(), turn, "/workflow-actions", body("toolCallId", "x1", "action", "workflow.finish",
                "arguments", Map.of("result", "x"))).andExpect(status().isForbidden());
        getAs(other.owner(), runs(other.org(), "/" + run + "/actions")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ limits and failures

    @Test
    void theNumberOfWorkerStepsIsLimited() throws Exception {
        Team t = team();
        UUID run = start(t, coordinated(t, Map.of("maxSteps", 1)));
        UUID turn1 = execution(t, run, 0);
        runTurn(t.org(), turn1);
        delegate(t.org(), turn1, "d1", "worker_1", "first");
        complete(t.org(), turn1, "ok", 10, 10);
        pass();
        UUID worker = execution(t, run, 1);
        runTurn(t.org(), worker);
        complete(t.org(), worker, "r", 10, 10);
        pass();
        UUID turn2 = execution(t, run, 2);
        runTurn(t.org(), turn2);
        assertThat(delegate(t.org(), turn2, "d2", "worker_2", "second").get("code").asString()).isEqualTo("STEP_LIMIT");
        // Finishing is still possible.
        assertThat(act(t.org(), turn2, "f1", "workflow.finish", Map.of("result", "done")).get("status").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void aTurnThatEndsWithoutActionIsAskedOnceMoreThenTheRunFails() throws Exception {
        Team t = team();
        UUID run = start(t, coordinated(t, Map.of()));
        UUID turn1 = execution(t, run, 0);
        runTurn(t.org(), turn1);
        complete(t.org(), turn1, "I think about it", 10, 10);
        pass();
        assertThat(steps(t, run)).hasSize(2);
        UUID turn2 = execution(t, run, 1);
        assertThat(task(turn2)).contains("ended without an action");
        runTurn(t.org(), turn2);
        complete(t.org(), turn2, "still thinking", 10, 10);
        pass();
        JsonNode failed = run(t, run);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("errorCode").asString()).isEqualTo("COORDINATOR_NO_ACTION");
        assertThat(steps(t, run)).hasSize(2);
    }

    @Test
    void aFailedCoordinatorTurnFailsTheRunAndStartsNoWorker() throws Exception {
        Team t = team();
        UUID run = start(t, coordinated(t, Map.of()));
        UUID turn = execution(t, run, 0);
        runTurn(t.org(), turn);
        delegate(t.org(), turn, "d1", "worker_1", "work");      // accepted, but the turn then fails: it is not honoured
        serviceCall(t.org(), turn, "/fail", body("errorCode", "PROVIDER_ERROR", "message", "boom")).andExpect(status().isOk());
        pass();
        assertThat(run(t, run).get("status").asString()).isEqualTo("FAILED");
        assertThat(steps(t, run)).hasSize(1);
    }

    @Test
    void theTokenBudgetIncludesTheCoordinatorAndStopsNewTurns() throws Exception {
        Team t = team();
        UUID run = start(t, coordinated(t, Map.of("tokenBudget", 1000)));
        UUID turn = execution(t, run, 0);
        runTurn(t.org(), turn);
        delegate(t.org(), turn, "d1", "worker_1", "work");
        complete(t.org(), turn, "ok", 400, 200);                 // 600 of 1000 used by the coordinator alone
        pass();
        JsonNode failed = run(t, run);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("errorCode").asString()).isEqualTo("TOKEN_BUDGET_EXCEEDED");
        assertThat(steps(t, run)).hasSize(1);                    // no worker started below the reserve
    }

    @Test
    void cancellingARunStopsTheCoordinatorAndRefusesLateActions() throws Exception {
        Team t = team();
        UUID run = start(t, coordinated(t, Map.of()));
        UUID turn = execution(t, run, 0);
        runTurn(t.org(), turn);
        postAs(t.owner(), runs(t.org(), "/" + run + "/cancel"), "{}").andExpect(status().isOk());
        pass();
        assertThat(run(t, run).get("status").asString()).isEqualTo("CANCELLED");
        serviceCall(t.org(), turn, "/workflow-actions", body("toolCallId", "late", "action", "workflow.delegate",
                "arguments", Map.of("participantRef", "worker_1", "task", "x"))).andExpect(status().isConflict());
        pass();
        assertThat(steps(t, run)).hasSize(1);                    // a terminal run is never restarted by a late event
    }

    @Test
    void aRequesterWhoLostTheRightToExecuteCannotKeepDelegating() throws Exception {
        Team t = team();
        TestUser runner = newUser("runner");
        UUID runnerMembership = join(t.owner(), t.org(), runner, "MEMBER");
        giveCustomRole(t.owner(), t.org(), runnerMembership, "Runner", List.of("workflow.read", "workflow.execute", "agent.execute", "agent.read"));
        UUID workflow = coordinated(t, Map.of());
        UUID run = id(read(postAs(runner, wf(t.org(), "/" + workflow + "/runs"), body("objective", "Objective")).andExpect(status().isAccepted())));
        UUID turn = execution(t, run, 0);
        runTurn(t.org(), turn);
        postAs(t.owner(), path(t.org(), "/members/" + runnerMembership + "/suspend"), "{}").andExpect(status().isOk());
        assertThat(delegate(t.org(), turn, "d1", "worker_1", "work").get("code").asString()).isEqualTo("REQUESTER_NOT_AUTHORIZED");
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void concurrentIdenticalActionsCreateExactlyOneRecordAndOneWorker() throws Exception {
        Team t = team();
        UUID run = start(t, coordinated(t, Map.of()));
        UUID turn = execution(t, run, 0);
        runTurn(t.org(), turn);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Callable<String> call = () -> {
                gate.await();
                return delegate(t.org(), turn, "race", "worker_1", "same work").get("status").asString();
            };
            results.add(pool.submit(call));
        }
        gate.countDown();
        for (Future<String> f : results) {
            assertThat(f.get()).isEqualTo("ACCEPTED");
        }
        pool.shutdown();
        assertThat(count("SELECT count(*) FROM workflow_actions WHERE execution_id = ?", turn)).isEqualTo(1);
        complete(t.org(), turn, "ok", 10, 10);
        pass();
        pass();
        assertThat(steps(t, run)).hasSize(2);
    }

    @Test
    void aPipelineWorkflowIsUnaffected() throws Exception {
        Team t = team();
        UUID workflow = id(read(postAs(t.owner(), wf(t.org(), ""), body("name", "P")).andExpect(status().isCreated())));
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"), body("agentIds", List.of(t.researcher()))).andExpect(status().isOk());
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/steps"),
                body("steps", List.of(body("name", "S", "agentId", t.researcher(), "instruction", "x")))).andExpect(status().isOk());
        UUID run = start(t, workflow);
        assertThat(run(t, run).get("mode").asString()).isEqualTo("PIPELINE");
        UUID only = execution(t, run, 0);
        serviceCall(t.org(), only, "/workflow-actions", body("toolCallId", "p", "action", "workflow.finish", "arguments", Map.of("result", "x")))
                .andExpect(status().isForbidden());
    }
}
