package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * Deterministic multi-agent pipeline (C3.1) against the REAL persistence (PostgreSQL, Flyway, the production services).
 * The orchestrator is played through its internal API and the provider is never involved: this proves the workflow
 * state machine, not any model behaviour. The background maintenance is turned off so each pass is explicit.
 */
@Import({WorkflowProbe.class, MaintenanceProbe.class})
@TestPropertySource(properties = {"app.workflows.maintenance-ms=3600000", "app.executions.approval-maintenance-ms=3600000"})
class WorkflowPipelineIntegrationTest extends ToolFlows {

    private static final Object[] NOTE = {"diagnostic.record_note", "WRITE", false};

    @Autowired
    private RecordingOrchestrator.Gateway orchestrator;

    @Autowired
    private WorkflowProbe.Probe probe;

    @Autowired
    private MaintenanceProbe.Probe approvalProbe;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        orchestrator.reset();
    }

    // ------------------------------------------------------------------ helpers

    private static String wf(UUID org, String suffix) {
        return path(org, "/workflows" + suffix);
    }

    private static String runs(UUID org, String suffix) {
        return path(org, "/workflow-runs" + suffix);
    }

    private UUID workflow(TestUser owner, UUID org, Map<String, Object> limits, UUID... stepAgents) throws Exception {
        Map<String, Object> create = new HashMap<>(limits);
        create.put("name", "Pipeline");
        UUID workflow = id(read(postAs(owner, wf(org, ""), create).andExpect(status().isCreated())));
        List<UUID> distinct = new ArrayList<>(new java.util.LinkedHashSet<>(List.of(stepAgents)));
        putAs(owner, wf(org, "/" + workflow + "/participants"), body("agentIds", distinct)).andExpect(status().isOk());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < stepAgents.length; i++) {
            steps.add(body("name", "Step " + (i + 1), "agentId", stepAgents[i], "instruction", "Do part " + (i + 1)));
        }
        putAs(owner, wf(org, "/" + workflow + "/steps"), body("steps", steps)).andExpect(status().isOk());
        return workflow;
    }

    private JsonNode start(TestUser owner, UUID org, UUID workflow, String objective) throws Exception {
        return read(postAs(owner, wf(org, "/" + workflow + "/runs"), body("objective", objective)).andExpect(status().isAccepted()));
    }

    private JsonNode run(TestUser owner, UUID org, UUID run) throws Exception {
        return read(getAs(owner, runs(org, "/" + run)).andExpect(status().isOk()));
    }

    private String runStatus(TestUser owner, UUID org, UUID run) throws Exception {
        return run(owner, org, run).get("status").asString();
    }

    private List<JsonNode> steps(TestUser owner, UUID org, UUID run) throws Exception {
        JsonNode content = read(getAs(owner, runs(org, "/" + run + "/steps")).andExpect(status().isOk())).get("content");
        List<JsonNode> list = new ArrayList<>();
        for (int i = 0; i < content.size(); i++) {
            list.add(content.get(i));
        }
        return list;
    }

    private List<String> messageKinds(TestUser owner, UUID org, UUID run) throws Exception {
        JsonNode content = read(getAs(owner, runs(org, "/" + run + "/messages")).andExpect(status().isOk())).get("content");
        List<String> kinds = new ArrayList<>();
        for (int i = 0; i < content.size(); i++) {
            kinds.add(content.get(i).get("kind").asString());
        }
        return kinds;
    }

    private UUID child(TestUser owner, UUID org, UUID run, int index) throws Exception {
        return UUID.fromString(steps(owner, org, run).get(index).get("executionId").asString());
    }

    /** The orchestrator takes the execution and finishes it. */
    private void orchestrate(UUID org, UUID execution, String result, int input, int output) throws Exception {
        serviceCall(org, execution, "/start", Map.of()).andExpect(status().isOk());
        serviceCall(org, execution, "/complete", body("result", result, "inputTokens", input, "outputTokens", output, "model", "model-x"))
                .andExpect(status().isOk());
    }

    private void pass() {
        probe.newWorker().advanceOpenRuns();
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

    private int audit(UUID org, String action) {
        return count("SELECT count(*) FROM audit_events WHERE organization_id = ? AND action = ?", org, action);
    }

    private record Team(TestUser owner, UUID org, UUID a, UUID b, UUID c) {
    }

    private Team team() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Workflows " + UUID.randomUUID().toString().substring(0, 8));
        return new Team(owner, org, agentWithSecret(owner, org, "Researcher"), agentWithSecret(owner, org, "Writer"),
                agentWithSecret(owner, org, "Reviewer"));
    }

    // ------------------------------------------------------------------ the pipeline

    @Test
    void aTwoAgentPipelineRunsSequentiallyAndEveryStepIsARealExecution() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        JsonNode started = start(t.owner(), t.org(), workflow, "Write a summary of the quarterly numbers");
        UUID run = UUID.fromString(started.get("id").asString());

        // Starting launches ONLY the first step, as a normal C1 execution of the first agent, by the same person.
        List<JsonNode> steps = steps(t.owner(), t.org(), run);
        assertThat(steps).hasSize(1);
        UUID first = child(t.owner(), t.org(), run, 0);
        assertThat(runStatus(t.owner(), t.org(), run)).isEqualTo("RUNNING");
        getAs(t.owner(), path(t.org(), "/executions/" + first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(t.a().toString())).andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(jdbc.queryForObject("SELECT requested_by FROM agent_executions WHERE id = ?", UUID.class, first)).isNotNull();
        await(() -> orchestrator.dispatched().stream().anyMatch(d -> d.executionId().equals(first)));   // handed over after commit
        assertThat(task(first)).contains("step 1 of 2").contains("Write a summary of the quarterly numbers").contains("Do part 1");

        // Nothing happens until the first child ends; repeating the pass changes nothing (idempotent).
        pass();
        pass();
        assertThat(steps(t.owner(), t.org(), run)).hasSize(1);

        orchestrate(t.org(), first, "Findings: revenue grew 12%.", 100, 40);
        pass();
        pass();

        List<JsonNode> after = steps(t.owner(), t.org(), run);
        assertThat(after).hasSize(2);
        assertThat(after.get(0).get("status").asString()).isEqualTo("SUCCEEDED");
        UUID second = child(t.owner(), t.org(), run, 1);
        assertThat(second).isNotEqualTo(first);
        getAs(t.owner(), path(t.org(), "/executions/" + second)).andExpect(jsonPath("$.agentId").value(t.b().toString()));
        // The second agent receives the objective, its assignment and the bounded result of step 1 as untrusted data.
        assertThat(task(second)).contains("step 2 of 2").contains("Do part 2").contains("revenue grew 12%").contains("DATA produced by other agents");

        orchestrate(t.org(), second, "Final report: revenue grew 12%.", 150, 60);
        pass();
        JsonNode done = run(t.owner(), t.org(), run);
        assertThat(done.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("result").asString()).isEqualTo("Final report: revenue grew 12%.");
        assertThat(done.get("tokensUsed").asInt()).isEqualTo(350);
        assertThat(messageKinds(t.owner(), t.org(), run)).containsExactly("OBJECTIVE", "ASSIGNMENT", "RESULT", "ASSIGNMENT", "RESULT", "STATUS");
        assertThat(audit(t.org(), "workflow.run.succeeded")).isEqualTo(1);
        assertThat(audit(t.org(), "workflow.step.started")).isEqualTo(2);
    }

    @Test
    void aThreeAgentPipelineGivesEachWorkerOnlyTheInputsItWasAssigned() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b(), t.c());
        // Step 3 takes ONLY step 1 as input (not the previous step).
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/steps"), body("steps", List.of(
                body("name", "Research", "agentId", t.a(), "instruction", "Research"),
                body("name", "Draft", "agentId", t.b(), "instruction", "Draft"),
                body("name", "Review", "agentId", t.c(), "instruction", "Review", "inputsFrom", List.of(1)))))
                .andExpect(status().isOk());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective X").get("id").asString());

        orchestrate(t.org(), child(t.owner(), t.org(), run, 0), "RESULT-ONE", 10, 10);
        pass();
        orchestrate(t.org(), child(t.owner(), t.org(), run, 1), "RESULT-TWO", 10, 10);
        pass();
        String third = task(child(t.owner(), t.org(), run, 2));
        assertThat(third).contains("RESULT-ONE").doesNotContain("RESULT-TWO");     // shared context is NOT given wholesale
        assertThat(third).doesNotContain("Draft");                                   // not the assignment of another agent
        orchestrate(t.org(), child(t.owner(), t.org(), run, 2), "RESULT-THREE", 10, 10);
        pass();
        assertThat(runStatus(t.owner(), t.org(), run)).isEqualTo("SUCCEEDED");
        assertThat(steps(t.owner(), t.org(), run)).hasSize(3);
    }

    @Test
    void aStartedRunKeepsItsSnapshotWhateverHappensToTheDefinition() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        int versionAtStart = run(t.owner(), t.org(), run).get("definitionVersion").asInt();

        // Edit the instructions and the steps, then archive the definition: the run is not affected.
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/steps"), body("steps", List.of(
                body("name", "Changed", "agentId", t.a(), "instruction", "CHANGED instruction"),
                body("name", "Changed 2", "agentId", t.b(), "instruction", "CHANGED 2")))).andExpect(status().isOk());
        postAs(t.owner(), wf(t.org(), "/" + workflow + "/archive"), "{}").andExpect(status().isOk());
        getAs(t.owner(), wf(t.org(), "/" + workflow)).andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.version").value(org.hamcrest.Matchers.greaterThan(versionAtStart)));
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/steps"), body("steps", List.of(body("name", "x", "agentId", t.a(), "instruction", "y"))))
                .andExpect(status().isConflict());

        orchestrate(t.org(), child(t.owner(), t.org(), run, 0), "one", 5, 5);
        pass();
        String second = task(child(t.owner(), t.org(), run, 1));
        assertThat(second).contains("Do part 2").doesNotContain("CHANGED");
        assertThat(run(t.owner(), t.org(), run).get("definitionVersion").asInt()).isEqualTo(versionAtStart);
        // An archived definition cannot be started again.
        postAs(t.owner(), wf(t.org(), "/" + workflow + "/runs"), body("objective", "again")).andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ participants and authorization

    @Test
    void participantsAreExplicitAndValidated() throws Exception {
        Team t = team();
        UUID workflow = id(read(postAs(t.owner(), wf(t.org(), ""), body("name", "P")).andExpect(status().isCreated())));
        // A team does not make its agents participants.
        UUID team = team(t.owner(), t.org(), "Agents team", null, null);
        addAgentToTeam(t.owner(), t.org(), team, t.a());
        getAs(t.owner(), wf(t.org(), "/" + workflow)).andExpect(jsonPath("$.participants.length()").value(0));
        // A step cannot use an agent that was not explicitly added.
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/steps"), body("steps", List.of(body("name", "S", "agentId", t.a(), "instruction", "I"))))
                .andExpect(status().isBadRequest());
        // An agent of ANOTHER organization is unknown here (404), an inactive one is refused (409).
        TestUser other = newUser("other");
        UUID otherOrg = createOrganization(other, "Other " + UUID.randomUUID().toString().substring(0, 8));
        UUID foreign = agentWithSecret(other, otherOrg, "Foreign");
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"), body("agentIds", List.of(foreign))).andExpect(status().isNotFound());
        UUID draft = agent(t.owner(), t.org(), "Draft agent");
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"), body("agentIds", List.of(draft))).andExpect(status().isConflict());
        // Every participant must be assigned to a step before the workflow can run.
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"), body("agentIds", List.of(t.a(), t.b()))).andExpect(status().isOk());
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/steps"), body("steps", List.of(body("name", "S", "agentId", t.a(), "instruction", "I"))))
                .andExpect(status().isOk());
        postAs(t.owner(), wf(t.org(), "/" + workflow + "/runs"), body("objective", "x")).andExpect(status().isConflict());
        // An agent used by a step cannot be removed behind its back.
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"), body("agentIds", List.of(t.b()))).andExpect(status().isConflict());
    }

    @Test
    void workflowExecuteNeverReplacesAgentExecuteAndEveryOperationIsAuthorized() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        TestUser member = newUser("member");
        UUID membership = join(t.owner(), t.org(), member, "MEMBER");
        // A plain member can do nothing with workflows.
        getAs(member, wf(t.org(), "")).andExpect(status().isForbidden());
        postAs(member, wf(t.org(), ""), body("name", "x")).andExpect(status().isForbidden());
        postAs(member, wf(t.org(), "/" + workflow + "/runs"), body("objective", "x")).andExpect(status().isForbidden());
        // workflow.execute WITHOUT agent.execute cannot start a run: the participants' own permission is needed too.
        giveCustomRole(t.owner(), t.org(), membership, "Workflow runner", List.of("workflow.read", "workflow.execute", "agent.read"));
        postAs(member, wf(t.org(), "/" + workflow + "/runs"), body("objective", "x")).andExpect(status().isForbidden());
        getAs(member, wf(t.org(), "")).andExpect(status().isOk());
        postAs(member, wf(t.org(), "/" + workflow + "/archive"), "{}").andExpect(status().isForbidden());
        assertThat(count("SELECT count(*) FROM workflow_runs WHERE organization_id = ?", t.org())).isZero();
    }

    @Test
    void anotherOrganizationSeesAndTouchesNothing() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Secret objective").get("id").asString());
        TestUser bob = newUser("bob");
        UUID orgB = createOrganization(bob, "B " + UUID.randomUUID().toString().substring(0, 8));
        getAs(bob, wf(orgB, "/" + workflow)).andExpect(status().isNotFound());
        postAs(bob, wf(orgB, "/" + workflow + "/runs"), body("objective", "x")).andExpect(status().isNotFound());
        getAs(bob, runs(orgB, "/" + run)).andExpect(status().isNotFound());
        getAs(bob, runs(orgB, "/" + run + "/steps")).andExpect(status().isNotFound());
        getAs(bob, runs(orgB, "/" + run + "/messages")).andExpect(status().isNotFound());
        postAs(bob, runs(orgB, "/" + run + "/cancel"), "{}").andExpect(status().isNotFound());
        getAs(bob, runs(orgB, "")).andExpect(jsonPath("$.totalElements").value(0));
        getAs(bob, wf(orgB, "")).andExpect(jsonPath("$.totalElements").value(0));
        // ...and nobody reads another organization's run through his own path with a foreign id in the organization segment.
        getAs(bob, runs(t.org(), "/" + run)).andExpect(status().isNotFound());
        assertThat(runStatus(t.owner(), t.org(), run)).isEqualTo("RUNNING");
    }

    // ------------------------------------------------------------------ limits and budgets

    @Test
    void stepCountLimitsAreEnforced() throws Exception {
        Team t = team();
        UUID workflow = id(read(postAs(t.owner(), wf(t.org(), ""), body("name", "Limits", "maxSteps", 2)).andExpect(status().isCreated())));
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/participants"), body("agentIds", List.of(t.a(), t.b()))).andExpect(status().isOk());
        List<Map<String, Object>> three = List.of(body("name", "1", "agentId", t.a(), "instruction", "i"),
                body("name", "2", "agentId", t.b(), "instruction", "i"), body("name", "3", "agentId", t.a(), "instruction", "i"));
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/steps"), body("steps", three)).andExpect(status().isBadRequest());
        // The platform maximum (8) cannot be exceeded either, whatever the definition says.
        List<Map<String, Object>> nine = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            nine.add(body("name", "S" + i, "agentId", t.a(), "instruction", "i"));
        }
        putAs(t.owner(), wf(t.org(), "/" + workflow + "/steps"), body("steps", nine)).andExpect(status().isBadRequest());
        postAs(t.owner(), wf(t.org(), ""), body("name", "Too big", "maxSteps", 9)).andExpect(status().isBadRequest());
    }

    @Test
    void theNumberOfOpenRunsPerOrganizationIsLimitedIndependentlyOfTheExecutionLimit() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        List<UUID> open = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            open.add(UUID.fromString(start(t.owner(), t.org(), workflow, "run " + i).get("id").asString()));
        }
        postAs(t.owner(), wf(t.org(), "/" + workflow + "/runs"), body("objective", "one too many")).andExpect(status().isConflict());
        // Finishing (here: cancelling) one run frees a slot.
        postAs(t.owner(), runs(t.org(), "/" + open.get(0) + "/cancel"), "{}").andExpect(status().isOk());
        pass();
        assertThat(runStatus(t.owner(), t.org(), open.get(0))).isEqualTo("CANCELLED");
        start(t.owner(), t.org(), workflow, "now it fits");
    }

    @Test
    void whenTheOrganizationIsAtItsExecutionLimitTheStepWaitsAndIsNotLost() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        List<UUID> others = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            others.add(runningExecution(t.owner(), t.org(), t.c()));
        }
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        assertThat(steps(t.owner(), t.org(), run)).isEmpty();                       // no capacity: nothing launched, nothing failed
        assertThat(runStatus(t.owner(), t.org(), run)).isEqualTo("PENDING");
        pass();
        assertThat(steps(t.owner(), t.org(), run)).isEmpty();
        serviceCall(t.org(), others.get(0), "/complete", body("result", "done")).andExpect(status().isOk());
        pass();
        pass();
        assertThat(steps(t.owner(), t.org(), run)).hasSize(1);                       // exactly one, however many passes
        assertThat(runStatus(t.owner(), t.org(), run)).isEqualTo("RUNNING");
    }

    @Test
    void theCumulativeTokenBudgetStopsTheWorkflowBetweenSteps() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of("tokenBudget", 1000), t.a(), t.b());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        orchestrate(t.org(), child(t.owner(), t.org(), run, 0), "expensive", 900, 300);   // 1200 > 1000
        pass();
        JsonNode failed = run(t.owner(), t.org(), run);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("errorCode").asString()).isEqualTo("TOKEN_BUDGET_EXCEEDED");
        assertThat(steps(t.owner(), t.org(), run)).hasSize(1);                          // the second step never started
        assertThat(steps(t.owner(), t.org(), run).get(0).get("status").asString()).isEqualTo("SUCCEEDED");   // results preserved
    }

    @Test
    void theGlobalDeadlineEndsTheRunAndItsActiveChild() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        UUID first = child(t.owner(), t.org(), run, 0);
        serviceCall(t.org(), first, "/start", Map.of()).andExpect(status().isOk());
        jdbc.update("UPDATE workflow_runs SET deadline_at = now() - interval '1 minute' WHERE id = ?", run);
        pass();
        JsonNode ended = run(t.owner(), t.org(), run);
        assertThat(ended.get("status").asString()).isEqualTo("TIMEOUT");
        assertThat(ended.get("errorCode").asString()).isEqualTo("WORKFLOW_TIMEOUT");
        assertThat(executionStatus(first)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM agent_executions WHERE id = ?", String.class, first)).isEqualTo("WORKFLOW_TIMEOUT");
        assertThat(steps(t.owner(), t.org(), run)).hasSize(1);
        serviceCall(t.org(), first, "/complete", body("result", "too late")).andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ failure policy

    @Test
    void aFailedStepFailsTheRunAndNothingAfterItEverStarts() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b(), t.c());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        orchestrate(t.org(), child(t.owner(), t.org(), run, 0), "first is fine", 10, 10);
        pass();
        UUID second = child(t.owner(), t.org(), run, 1);
        serviceCall(t.org(), second, "/start", Map.of()).andExpect(status().isOk());
        serviceCall(t.org(), second, "/fail", body("errorCode", "PROVIDER_ERROR", "message", "boom")).andExpect(status().isOk());
        pass();
        pass();
        JsonNode failed = run(t.owner(), t.org(), run);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("errorCode").asString()).isEqualTo("STEP_FAILED");
        List<JsonNode> steps = steps(t.owner(), t.org(), run);
        assertThat(steps).hasSize(2);                                                    // the third step was never created
        assertThat(steps.get(0).get("status").asString()).isEqualTo("SUCCEEDED");        // previous results preserved
        assertThat(steps.get(0).get("resultSummary").asString()).isEqualTo("first is fine");
        assertThat(steps.get(1).get("status").asString()).isEqualTo("FAILED");
        assertThat(audit(t.org(), "workflow.run.failed")).isEqualTo(1);
    }

    @Test
    void aTransientProviderFailureMayBeRetriedAsANewExecutionButOnlyWhenNoToolEffectStarted() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of("maxRetriesPerStep", 1), t.a(), t.b());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        UUID first = child(t.owner(), t.org(), run, 0);
        serviceCall(t.org(), first, "/start", Map.of()).andExpect(status().isOk());
        serviceCall(t.org(), first, "/fail", body("errorCode", "PROVIDER_TIMEOUT", "message", "slow")).andExpect(status().isOk());
        pass();
        pass();
        List<JsonNode> steps = steps(t.owner(), t.org(), run);
        assertThat(steps).hasSize(2);                                                    // a NEW execution, same rank, attempt 2
        assertThat(steps.get(1).get("attempt").asInt()).isEqualTo(2);
        assertThat(steps.get(1).get("rank").asInt()).isEqualTo(1);
        assertThat(steps.get(1).get("executionId").asString()).isNotEqualTo(steps.get(0).get("executionId").asString());
        assertThat(executionStatus(first)).isEqualTo("FAILED");                          // the failed execution is preserved
        orchestrate(t.org(), child(t.owner(), t.org(), run, 1), "second try worked", 5, 5);
        pass();
        assertThat(steps(t.owner(), t.org(), run)).hasSize(3);                            // the pipeline continues with rank 2

        // A second run: the step started a tool call, then the provider failed transiently: NEVER retried.
        Team u = team();
        UUID wf2 = workflow(u.owner(), u.org(), Map.of("maxRetriesPerStep", 2), u.a(), u.b());
        grant(u.owner(), u.org(), u.a(), NOTE);
        UUID run2 = UUID.fromString(start(u.owner(), u.org(), wf2, "Objective").get("id").asString());
        UUID e = child(u.owner(), u.org(), run2, 0);
        serviceCall(u.org(), e, "/start", Map.of()).andExpect(status().isOk());
        JsonNode decision = requestTool(u.org(), e, "toolu_1", "diagnostic.record_note", text("effect"));
        UUID approval = UUID.fromString(decision.get("approvalId").asString());
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());
        String hash = read(getAs(u.owner(), path(u.org(), "/approvals/" + approval))).get("argumentsHash").asString();
        postAs(u.owner(), path(u.org(), "/approvals/" + approval + "/approve"), body("argumentsHash", hash)).andExpect(status().isOk());
        serviceCall(u.org(), e, "/resume", Map.of()).andExpect(status().isOk());
        serviceCall(u.org(), e, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isOk());   // the effect STARTED
        serviceCall(u.org(), e, "/fail", body("errorCode", "PROVIDER_TIMEOUT", "message", "slow")).andExpect(status().isOk());
        pass();
        pass();
        assertThat(steps(u.owner(), u.org(), run2)).hasSize(1);
        assertThat(run(u.owner(), u.org(), run2).get("status").asString()).isEqualTo("FAILED");
    }

    @Test
    void secretsInAnAgentResultNeverReachTheJournalTheSummaryOrTheNextAgent() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        orchestrate(t.org(), child(t.owner(), t.org(), run, 0), "ok Bearer abcdefghijklmnop1234567890 and password: hunter2value", 5, 5);
        pass();
        String next = task(child(t.owner(), t.org(), run, 1));
        String journal = read(getAs(t.owner(), runs(t.org(), "/" + run + "/messages"))).toString();
        String summary = steps(t.owner(), t.org(), run).get(0).get("resultSummary").asString();
        for (String leak : new String[] {"abcdefghijklmnop", "hunter2value"}) {
            assertThat(next).doesNotContain(leak);
            assertThat(journal).doesNotContain(leak);
            assertThat(summary).doesNotContain(leak);
        }
        assertThat(summary).contains("[REDACTED]");
    }

    @Test
    void anAgentThatIsNoLongerActiveStopsTheRunBeforeItsStep() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        postAs(t.owner(), agents(t.org(), "/" + t.b() + "/pause"), "{}").andExpect(status().isOk());
        orchestrate(t.org(), child(t.owner(), t.org(), run, 0), "done", 5, 5);
        pass();
        JsonNode failed = run(t.owner(), t.org(), run);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("errorCode").asString()).isEqualTo("STEP_NOT_RUNNABLE");
        assertThat(steps(t.owner(), t.org(), run)).hasSize(1);
    }

    @Test
    void thePersonWhoStartedTheRunIsAuthorizedAgainBeforeEveryStep() throws Exception {
        Team t = team();
        TestUser runner = newUser("runner");
        UUID membership = join(t.owner(), t.org(), runner, "MEMBER");
        giveCustomRole(t.owner(), t.org(), membership, "Runner", List.of("workflow.read", "workflow.execute", "agent.execute", "agent.read"));
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        UUID run = UUID.fromString(start(runner, t.org(), workflow, "Objective").get("id").asString());
        postAs(t.owner(), path(t.org(), "/members/" + membership + "/suspend"), "{}").andExpect(status().isOk());
        orchestrate(t.org(), child(t.owner(), t.org(), run, 0), "done", 5, 5);
        pass();
        JsonNode failed = run(t.owner(), t.org(), run);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("errorCode").asString()).isEqualTo("REQUESTER_NOT_AUTHORIZED");
        assertThat(steps(t.owner(), t.org(), run)).hasSize(1);
    }

    // ------------------------------------------------------------------ approvals inside a step

    @Test
    void aToolApprovalInsideAStepPausesTheRunAndTheWorkflowContinuesAfterTheDecision() throws Exception {
        Team t = team();
        grant(t.owner(), t.org(), t.a(), NOTE);
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        UUID first = child(t.owner(), t.org(), run, 0);
        serviceCall(t.org(), first, "/start", Map.of()).andExpect(status().isOk());
        JsonNode decision = requestTool(t.org(), first, "toolu_1", "diagnostic.record_note", text("note"));
        assertThat(decision.get("decision").asString()).isEqualTo("APPROVAL_REQUIRED");
        pass();
        assertThat(runStatus(t.owner(), t.org(), run)).isEqualTo("WAITING_APPROVAL");
        assertThat(steps(t.owner(), t.org(), run).get(0).get("status").asString()).isEqualTo("WAITING_APPROVAL");

        UUID approval = UUID.fromString(decision.get("approvalId").asString());
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());
        String hash = read(getAs(t.owner(), path(t.org(), "/approvals/" + approval))).get("argumentsHash").asString();
        postAs(t.owner(), path(t.org(), "/approvals/" + approval + "/approve"), body("argumentsHash", hash)).andExpect(status().isOk());
        serviceCall(t.org(), first, "/resume", Map.of()).andExpect(status().isOk());
        serviceCall(t.org(), first, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isOk());
        serviceCall(t.org(), first, "/tool-calls/" + invocation + "/complete", body("success", true, "output", "noted")).andExpect(status().isOk());
        serviceCall(t.org(), first, "/complete", body("result", "noted and done", "inputTokens", 5, "outputTokens", 5)).andExpect(status().isOk());
        pass();
        assertThat(runStatus(t.owner(), t.org(), run)).isEqualTo("RUNNING");
        assertThat(steps(t.owner(), t.org(), run)).hasSize(2);
        assertThat(audit(t.org(), "agent.tool.started")).isEqualTo(1);                   // the tool ran exactly once
    }

    @Test
    void anExpiredApprovalFailsTheStepAndTheRunWithoutRunningAnything() throws Exception {
        Team t = team();
        grant(t.owner(), t.org(), t.a(), NOTE);
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        UUID first = child(t.owner(), t.org(), run, 0);
        serviceCall(t.org(), first, "/start", Map.of()).andExpect(status().isOk());
        JsonNode decision = requestTool(t.org(), first, "toolu_1", "diagnostic.record_note", text("late"));
        UUID approval = UUID.fromString(decision.get("approvalId").asString());
        pass();
        jdbc.update("UPDATE tool_approvals SET requested_at = now() - interval '2 hours', expires_at = now() - interval '1 hour' WHERE id = ?", approval);
        approvalProbe.newWorker().expireDue();
        pass();
        pass();
        JsonNode failed = run(t.owner(), t.org(), run);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(steps(t.owner(), t.org(), run)).hasSize(1);
        assertThat(steps(t.owner(), t.org(), run).get(0).get("errorCode").asString()).isEqualTo("APPROVAL_EXPIRED");
        assertThat(audit(t.org(), "agent.tool.started")).isZero();
    }

    // ------------------------------------------------------------------ cancellation

    @Test
    void cancellingBeforeAnyStepStartsSettlesImmediately() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        for (int i = 0; i < 3; i++) {
            runningExecution(t.owner(), t.org(), t.c());                                // the organization is full
        }
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        assertThat(steps(t.owner(), t.org(), run)).isEmpty();
        postAs(t.owner(), runs(t.org(), "/" + run + "/cancel"), "{}").andExpect(status().isOk());
        JsonNode cancelled = run(t.owner(), t.org(), run);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("errorCode").asString()).isEqualTo("WORKFLOW_CANCELLED");
        pass();
        assertThat(steps(t.owner(), t.org(), run)).isEmpty();                            // nothing ever started
        postAs(t.owner(), runs(t.org(), "/" + run + "/cancel"), "{}").andExpect(status().isConflict());
    }

    @Test
    void cancellingWhileAChildIsActivePreventsFutureStepsAndEndsTheChild() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b(), t.c());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        UUID first = child(t.owner(), t.org(), run, 0);
        serviceCall(t.org(), first, "/start", Map.of()).andExpect(status().isOk());
        postAs(t.owner(), runs(t.org(), "/" + run + "/cancel"), "{}").andExpect(status().isOk());
        // No tool call is in flight: the child is ended right away and the run settles.
        assertThat(executionStatus(first)).isEqualTo("FAILED");
        assertThat(runStatus(t.owner(), t.org(), run)).isEqualTo("CANCELLED");
        assertThat(steps(t.owner(), t.org(), run).get(0).get("status").asString()).isEqualTo("CANCELLED");
        // The orchestrator finds out with its next call: a conflict, and nothing new ever starts.
        serviceCall(t.org(), first, "/complete", body("result", "ignored")).andExpect(status().isConflict());
        pass();
        pass();
        assertThat(steps(t.owner(), t.org(), run)).hasSize(1);
        assertThat(messageKinds(t.owner(), t.org(), run)).contains("STATUS");
        assertThat(audit(t.org(), "workflow.run.cancel-requested")).isEqualTo(1);
    }

    @Test
    void cancellingWhileAToolIsInFlightWaitsForAKnownOutcomeAndNeverClaimsARollback() throws Exception {
        Team t = team();
        grant(t.owner(), t.org(), t.a(), NOTE);
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        UUID first = child(t.owner(), t.org(), run, 0);
        serviceCall(t.org(), first, "/start", Map.of()).andExpect(status().isOk());
        JsonNode decision = requestTool(t.org(), first, "toolu_1", "diagnostic.record_note", text("effect"));
        UUID approval = UUID.fromString(decision.get("approvalId").asString());
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());
        String hash = read(getAs(t.owner(), path(t.org(), "/approvals/" + approval))).get("argumentsHash").asString();
        postAs(t.owner(), path(t.org(), "/approvals/" + approval + "/approve"), body("argumentsHash", hash)).andExpect(status().isOk());
        serviceCall(t.org(), first, "/resume", Map.of()).andExpect(status().isOk());
        serviceCall(t.org(), first, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isOk());   // IN FLIGHT

        postAs(t.owner(), runs(t.org(), "/" + run + "/cancel"), "{}").andExpect(status().isOk());
        assertThat(runStatus(t.owner(), t.org(), run)).isEqualTo("CANCEL_REQUESTED");     // not settled: the call has no outcome yet
        assertThat(executionStatus(first)).isEqualTo("RUNNING");
        pass();
        assertThat(runStatus(t.owner(), t.org(), run)).isEqualTo("CANCEL_REQUESTED");

        // The call reaches a KNOWN outcome: it is recorded as it happened, then the child is ended.
        serviceCall(t.org(), first, "/tool-calls/" + invocation + "/complete", body("success", true, "output", "done")).andExpect(status().isOk());
        pass();
        assertThat(runStatus(t.owner(), t.org(), run)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM tool_invocations WHERE id = ?", String.class, invocation)).isEqualTo("SUCCEEDED");
        assertThat(steps(t.owner(), t.org(), run)).hasSize(1);
    }

    @Test
    void ifTheGracePeriodEndsWhileAToolIsStillRunningItsOutcomeIsReportedUnknownNeverUndone() throws Exception {
        Team t = team();
        grant(t.owner(), t.org(), t.a(), NOTE);
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        UUID first = child(t.owner(), t.org(), run, 0);
        serviceCall(t.org(), first, "/start", Map.of()).andExpect(status().isOk());
        JsonNode decision = requestTool(t.org(), first, "toolu_1", "diagnostic.record_note", text("effect"));
        UUID approval = UUID.fromString(decision.get("approvalId").asString());
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());
        String hash = read(getAs(t.owner(), path(t.org(), "/approvals/" + approval))).get("argumentsHash").asString();
        postAs(t.owner(), path(t.org(), "/approvals/" + approval + "/approve"), body("argumentsHash", hash)).andExpect(status().isOk());
        serviceCall(t.org(), first, "/resume", Map.of()).andExpect(status().isOk());
        serviceCall(t.org(), first, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isOk());
        postAs(t.owner(), runs(t.org(), "/" + run + "/cancel"), "{}").andExpect(status().isOk());

        jdbc.update("UPDATE workflow_runs SET cancel_requested_at = now() - interval '5 minutes' WHERE id = ?", run);
        pass();
        assertThat(runStatus(t.owner(), t.org(), run)).isEqualTo("CANCELLED");
        assertThat(executionStatus(first)).isEqualTo("FAILED");
        approvalProbe.newWorker().closeObsolete();
        // The external effect may have happened: the record says UNKNOWN, it is not retried and never "rolled back".
        assertThat(jdbc.queryForObject("SELECT error_code FROM tool_invocations WHERE id = ?", String.class, invocation)).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(audit(t.org(), "agent.tool.outcome-unknown")).isEqualTo(1);
        serviceCall(t.org(), first, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isConflict());
        assertThat(steps(t.owner(), t.org(), run)).hasSize(1);
    }

    // ------------------------------------------------------------------ duplicates, concurrency, recovery

    @Test
    void duplicateCompletionsAndRepeatedPassesNeverCreateAnotherStepOrExecution() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        UUID first = child(t.owner(), t.org(), run, 0);
        orchestrate(t.org(), first, "once", 5, 5);
        serviceCall(t.org(), first, "/complete", body("result", "twice")).andExpect(status().isConflict());   // duplicate notification
        for (int i = 0; i < 5; i++) {
            pass();
        }
        assertThat(steps(t.owner(), t.org(), run)).hasSize(2);
        assertThat(count("SELECT count(*) FROM agent_executions WHERE organization_id = ? AND agent_id = ?", t.org(), t.b())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM workflow_steps WHERE run_id = ?", run)).isEqualTo(2);
    }

    @Test
    void concurrentSchedulerPassesScheduleEachStepExactlyOnce() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        orchestrate(t.org(), child(t.owner(), t.org(), run, 0), "first", 5, 5);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                WorkflowProbe.Worker worker = probe.newWorker();
                Callable<Integer> task = () -> {
                    go.await();
                    return worker.advanceOpenRuns();
                };
                futures.add(pool.submit(task));
            }
            go.countDown();
            for (Future<Integer> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(steps(t.owner(), t.org(), run)).hasSize(2);
        assertThat(count("SELECT count(*) FROM agent_executions WHERE organization_id = ? AND agent_id = ?", t.org(), t.b())).isEqualTo(1);
    }

    @Test
    void aWorkflowSurvivesARestartWithoutDuplicatingAnythingBecauseAllStateIsInPostgreSql() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b(), t.c());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        // The first child finishes while "the Core API is down": nobody advances the run.
        orchestrate(t.org(), child(t.owner(), t.org(), run, 0), "done while down", 5, 5);
        assertThat(steps(t.owner(), t.org(), run)).hasSize(1);
        // A brand-new worker (no memory) discovers it from the database and continues, once.
        probe.newWorker().advanceOpenRuns();
        probe.newWorker().advanceOpenRuns();
        assertThat(steps(t.owner(), t.org(), run)).hasSize(2);
        // The second child is lost with the orchestrator (never completed): it is not duplicated either.
        probe.newWorker().advanceOpenRuns();
        assertThat(steps(t.owner(), t.org(), run)).hasSize(2);
        assertThat(runStatus(t.owner(), t.org(), run)).isEqualTo("RUNNING");
        UUID second = child(t.owner(), t.org(), run, 1);
        await(() -> orchestrator.dispatched().stream().anyMatch(d -> d.executionId().equals(second)));
        assertThat(orchestrator.dispatched().stream().filter(d -> d.executionId().equals(second)).count()).isEqualTo(1);
    }

    @Test
    void aRepeatedLaunchRequestWithTheSameRequestIdNeverCreatesASecondRun() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        Map<String, Object> request = body("objective", "Objective", "requestId", "launch-001");
        JsonNode first = read(postAs(t.owner(), wf(t.org(), "/" + workflow + "/runs"), request).andExpect(status().isAccepted()));
        JsonNode again = read(postAs(t.owner(), wf(t.org(), "/" + workflow + "/runs"), request).andExpect(status().isAccepted()));
        assertThat(again.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(count("SELECT count(*) FROM workflow_runs WHERE organization_id = ?", t.org())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM agent_executions WHERE organization_id = ?", t.org())).isEqualTo(1);
        postAs(t.owner(), wf(t.org(), "/" + workflow + "/runs"), body("objective", "x", "requestId", "bad id!")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ structure and database guarantees

    @Test
    void theJournalIsAppendOnlyAndEachRankHasAtMostOneLiveStep() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        assertThatThrownBy(() -> jdbc.update("UPDATE workflow_messages SET body = 'rewritten' WHERE run_id = ?", run))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM workflow_messages WHERE run_id = ?", run)).hasMessageContaining("append-only");
        // A second live step of the same rank (another execution) is refused by the database itself.
        UUID other = runningExecution(t.owner(), t.org(), t.a());
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO workflow_steps (id, organization_id, run_id, step_rank, attempt, name, agent_id, execution_id, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 1, 2, 'dup', ?, ?, 'RUNNING', now(), now())", UUID.randomUUID(), t.org(), run, t.a(), other))
                .hasMessageContaining("uq_workflow_steps_live_rank");
        // One execution belongs to at most one step.
        UUID taken = child(t.owner(), t.org(), run, 0);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO workflow_steps (id, organization_id, run_id, step_rank, attempt, name, agent_id, execution_id, status, created_at, updated_at, completed_at) "
                        + "VALUES (?, ?, ?, 2, 1, 'dup', ?, ?, 'FAILED', now(), now(), now())", UUID.randomUUID(), t.org(), run, t.a(), taken))
                .hasMessageContaining("uq_workflow_steps_execution");
    }

    @Test
    void workflowStateIsNeverAddedToAgentExecutionsAndAWorkerCannotDelegate() throws Exception {
        Team t = team();
        UUID workflow = workflow(t.owner(), t.org(), Map.of(), t.a(), t.b());
        UUID run = UUID.fromString(start(t.owner(), t.org(), workflow, "Objective").get("id").asString());
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'agent_executions' AND (column_name LIKE '%workflow%' OR column_name LIKE '%run_id%')"))
                .isZero();
        UUID first = child(t.owner(), t.org(), run, 0);
        serviceCall(t.org(), first, "/start", Map.of()).andExpect(status().isOk());
        // There is no delegation action in C3.1: a worker that asks for one is simply refused as an unknown tool.
        JsonNode refused = requestTool(t.org(), first, "toolu_x", "workflow.delegate", Map.of("agent", t.b().toString(), "task", "do it"));
        assertThat(refused.get("decision").asString()).isEqualTo("DENIED");
        assertThat(refused.get("code").asString()).isEqualTo("UNKNOWN_TOOL");
        pass();
        assertThat(steps(t.owner(), t.org(), run)).hasSize(1);
    }
}
