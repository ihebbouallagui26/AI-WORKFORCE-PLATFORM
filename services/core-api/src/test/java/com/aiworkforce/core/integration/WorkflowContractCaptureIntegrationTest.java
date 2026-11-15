package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.agent.MaintenanceProbe;
import com.aiworkforce.core.agent.WorkflowProbe;
import com.aiworkforce.core.support.RecordingOrchestrator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Captures REAL responses of the workflow endpoints (the Angular models and the workflow UI are checked against them, not against
 * hand-written fixtures). Each response comes from the real controllers, services and PostgreSQL; the orchestrator is played through
 * its internal API with synthetic data only (no credential, no token). The capture is written to
 * {@code target/workflow-contract-capture.json}; the desktop keeps a copy in {@code apps/desktop/ui/src/testing/real-api-c3.ts}.
 */
@Import({WorkflowProbe.class, MaintenanceProbe.class})
@TestPropertySource(properties = {"app.workflows.maintenance-ms=3600000", "app.executions.approval-maintenance-ms=3600000",
        "app.workflows.retry-base-delay=PT30S", "app.workflows.retry-max-delay=PT30S", "app.workflows.worker-token-reservation=1000"})
class WorkflowContractCaptureIntegrationTest extends ToolFlows {

    private static final Object[] ECHO = {"diagnostic.echo", "READ", false};

    @Autowired
    private RecordingOrchestrator.Gateway orchestrator;

    @Autowired
    private WorkflowProbe.Probe probe;

    @Autowired
    private JdbcTemplate jdbc;

    private final Map<String, Object> capture = new LinkedHashMap<>();

    @BeforeEach
    void reset() {
        orchestrator.reset();
    }

    private static String wf(UUID org, String suffix) {
        return path(org, "/workflows" + suffix);
    }

    private static String runs(UUID org, String suffix) {
        return path(org, "/workflow-runs" + suffix);
    }

    private JsonNode get(TestUser owner, String url) throws Exception {
        return read(getAs(owner, url).andExpect(status().isOk()));
    }

    private void pass() {
        probe.newWorker().advanceOpenRuns();
    }

    private UUID execution(TestUser owner, UUID org, UUID run, int rank, int attempt) throws Exception {
        JsonNode content = get(owner, runs(org, "/" + run + "/steps?size=100")).get("content");
        for (JsonNode s : content) {
            if (s.get("rank").asInt() == rank && s.get("attempt").asInt() == attempt && !s.get("executionId").isNull()) {
                return UUID.fromString(s.get("executionId").asString());
            }
        }
        throw new IllegalStateException("no execution for rank " + rank);
    }

    private void begin(UUID org, UUID execution) throws Exception {
        serviceCall(org, execution, "/start", Map.of()).andExpect(status().isOk());
    }

    private void finish(UUID org, UUID execution, String result, int in, int out) throws Exception {
        serviceCall(org, execution, "/complete", body("result", result, "inputTokens", in, "outputTokens", out, "model", "m")).andExpect(status().isOk());
    }

    private UUID parallelWorkflow(TestUser owner, UUID org, Map<String, Object> extra, UUID... agents) throws Exception {
        Map<String, Object> create = new java.util.HashMap<>(extra);
        create.put("name", "European SaaS market analysis");
        create.put("description", "Competitors, pricing and customers in parallel, then a synthesis.");
        create.put("maxParallelWorkers", 3);
        UUID workflow = id(read(postAs(owner, wf(org, ""), create).andExpect(status().isCreated())));
        putAs(owner, wf(org, "/" + workflow + "/participants"), body("agentIds", List.of(agents))).andExpect(status().isOk());
        List<Map<String, Object>> steps = new ArrayList<>();
        String[] names = {"Competitor analysis", "Pricing models", "Customer segments", "Business synthesis"};
        for (int i = 0; i < agents.length; i++) {
            steps.add(body("name", names[i], "agentId", agents[i], "instruction", "Do " + names[i].toLowerCase(), "stage", i == agents.length - 1 ? 2 : 1));
        }
        putAs(owner, wf(org, "/" + workflow + "/steps"), body("steps", steps)).andExpect(status().isOk());
        return workflow;
    }

    @Test
    void capturesTheRealWorkflowApi() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Capture " + UUID.randomUUID().toString().substring(0, 8));
        UUID a = agentWithSecret(owner, org, "Market Researcher");
        UUID b = agentWithSecret(owner, org, "Pricing Analyst");
        UUID c = agentWithSecret(owner, org, "Customer Analyst");
        UUID d = agentWithSecret(owner, org, "Synthesis Agent");
        grant(owner, org, b, ECHO);

        // ---- A parallel pipeline with RETRY: definition, run, queued/running/failed/retried attempts, summary, journal.
        UUID occupant = launch(owner, org, d, "ordinary work");                                  // holds one of the 3 organization slots
        UUID workflow = parallelWorkflow(owner, org, Map.of("failurePolicy", "RETRY", "maxRetriesPerStep", 2, "stepTimeoutSeconds", 600), a, b, c, d);
        capture.put("definitionPipeline", get(owner, wf(org, "/" + workflow)));
        capture.put("definitionsPage", get(owner, wf(org, "")));
        UUID run = id(read(postAs(owner, wf(org, "/" + workflow + "/runs"), body("objective", "Analyze the European SaaS market and prepare a report"))
                .andExpect(status().isAccepted())));
        capture.put("runStarted", get(owner, runs(org, "/" + run)));
        capture.put("stepsQueuedAndRunning", get(owner, runs(org, "/" + run + "/steps?size=100")));
        capture.put("summaryQueued", get(owner, runs(org, "/" + run + "/summary")));

        begin(org, occupant);
        finish(org, occupant, "done", 1, 1);
        pass();                                                                                    // the queued worker is dispatched
        UUID w1 = execution(owner, org, run, 1, 1);
        UUID w2 = execution(owner, org, run, 2, 1);
        UUID w3 = execution(owner, org, run, 3, 1);
        begin(org, w1);
        begin(org, w2);
        begin(org, w3);
        capture.put("summaryParallelRunning", get(owner, runs(org, "/" + run + "/summary")));
        finish(org, w1, "COMPETITORS: three relevant players.", 120, 30);
        serviceCall(org, w2, "/fail", body("errorCode", "PROVIDER_UNAVAILABLE", "message", "The provider is unavailable.", "inputTokens", 40, "outputTokens", 0)).andExpect(status().isOk());
        pass();
        capture.put("stepsRetryScheduled", get(owner, runs(org, "/" + run + "/steps?size=100")));
        capture.put("summaryRetryScheduled", get(owner, runs(org, "/" + run + "/summary")));
        jdbc.update("UPDATE workflow_steps SET next_attempt_at = now() - interval '1 second' WHERE run_id = ? AND next_attempt_at IS NOT NULL", run);
        pass();
        UUID w2b = execution(owner, org, run, 2, 2);
        begin(org, w2b);
        finish(org, w2b, "PRICING: freemium wins in SMB.", 100, 25);
        finish(org, w3, "CUSTOMERS: SMBs in DACH.", 90, 20);
        pass();
        UUID synth = execution(owner, org, run, 4, 1);
        begin(org, synth);
        finish(org, synth, "REPORT: enter via SMB freemium.", 300, 120);
        pass();
        capture.put("runSucceeded", get(owner, runs(org, "/" + run)));
        capture.put("stepsWithRetry", get(owner, runs(org, "/" + run + "/steps?size=100")));
        capture.put("messages", get(owner, runs(org, "/" + run + "/messages?size=100")));
        capture.put("summarySucceeded", get(owner, runs(org, "/" + run + "/summary")));
        capture.put("runsPage", get(owner, runs(org, "")));

        // ---- CONTINUE with a failed worker whose external outcome is unknown: partial failure, truthful category.
        UUID partial = parallelWorkflow(owner, org, Map.of("failurePolicy", "CONTINUE"), a, b, c, d);
        UUID run2 = id(read(postAs(owner, wf(org, "/" + partial + "/runs"), body("objective", "Partial outcome")).andExpect(status().isAccepted())));
        UUID p1 = execution(owner, org, run2, 1, 1);
        UUID p2 = execution(owner, org, run2, 2, 1);
        UUID p3 = execution(owner, org, run2, 3, 1);
        begin(org, p1);
        begin(org, p2);
        begin(org, p3);
        JsonNode decision = requestTool(org, p2, "toolu_1", "diagnostic.echo", text("effect"));
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());
        serviceCall(org, p2, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isOk());
        serviceCall(org, p2, "/fail", body("errorCode", "PROVIDER_UNAVAILABLE", "message", "lost")).andExpect(status().isOk());
        finish(org, p1, "R1", 10, 10);
        finish(org, p3, "R3", 10, 10);
        pass();
        UUID ps = execution(owner, org, run2, 4, 1);
        begin(org, ps);
        finish(org, ps, "S", 10, 10);
        pass();
        capture.put("runPartialFailure", get(owner, runs(org, "/" + run2)));
        capture.put("stepsOutcomeUnknown", get(owner, runs(org, "/" + run2 + "/steps?size=100")));
        capture.put("summaryOutcomeUnknown", get(owner, runs(org, "/" + run2 + "/summary")));

        // ---- Sequential workflow with manual recovery: WAITING_RECOVERY.
        UUID recovery = id(read(postAs(owner, wf(org, ""), body("name", "Recoverable pipeline", "recoveryMode", "MANUAL")).andExpect(status().isCreated())));
        putAs(owner, wf(org, "/" + recovery + "/participants"), body("agentIds", List.of(a, b))).andExpect(status().isOk());
        putAs(owner, wf(org, "/" + recovery + "/steps"), body("steps", List.of(
                body("name", "Gather", "agentId", a, "instruction", "gather"), body("name", "Write", "agentId", b, "instruction", "write")))).andExpect(status().isOk());
        UUID run3 = id(read(postAs(owner, wf(org, "/" + recovery + "/runs"), body("objective", "Needs a person")).andExpect(status().isAccepted())));
        UUID r1 = execution(owner, org, run3, 1, 1);
        begin(org, r1);
        serviceCall(org, r1, "/fail", body("errorCode", "PROVIDER_AUTHENTICATION", "message", "rejected credential")).andExpect(status().isOk());
        pass();
        capture.put("runWaitingRecovery", get(owner, runs(org, "/" + run3)));
        capture.put("stepsWaitingRecovery", get(owner, runs(org, "/" + run3 + "/steps?size=100")));
        capture.put("summaryWaitingRecovery", get(owner, runs(org, "/" + run3 + "/summary")));

        // ---- Coordinated workflow: definition, delegations (accepted and rejected), actions page.
        UUID coordinated = id(read(postAs(owner, wf(org, ""), body("name", "Coordinated research", "mode", "COORDINATED", "maxParallelWorkers", 2)).andExpect(status().isCreated())));
        putAs(owner, wf(org, "/" + coordinated + "/participants"), body("agentIds", List.of(d, a, b), "coordinatorAgentId", d)).andExpect(status().isOk());
        capture.put("definitionCoordinated", get(owner, wf(org, "/" + coordinated)));
        UUID run4 = id(read(postAs(owner, wf(org, "/" + coordinated + "/runs"), body("objective", "Research two angles")).andExpect(status().isAccepted())));
        UUID turn = execution(owner, org, run4, 1, 1);
        begin(org, turn);
        serviceCall(org, turn, "/workflow-actions", body("toolCallId", "c1", "action", "workflow.delegate", "arguments", Map.of("participantRef", "worker_1", "task", "Angle one"))).andExpect(status().isOk());
        serviceCall(org, turn, "/workflow-actions", body("toolCallId", "c2", "action", "workflow.delegate", "arguments", Map.of("participantRef", "worker_9", "task", "Nobody"))).andExpect(status().isOk());
        serviceCall(org, turn, "/workflow-actions", body("toolCallId", "c3", "action", "workflow.delegate", "arguments", Map.of("participantRef", "worker_2", "task", "Angle two"))).andExpect(status().isOk());
        finish(org, turn, "delegating", 50, 20);
        pass();
        capture.put("runCoordinated", get(owner, runs(org, "/" + run4)));
        capture.put("actionsPage", get(owner, runs(org, "/" + run4 + "/actions")));
        capture.put("stepsCoordinated", get(owner, runs(org, "/" + run4 + "/steps?size=100")));
        capture.put("summaryCoordinated", get(owner, runs(org, "/" + run4 + "/summary")));

        assertThat(capture).hasSizeGreaterThan(20);
        ObjectMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        Files.writeString(Path.of("target", "workflow-contract-capture.json"), mapper.writeValueAsString(capture) + "\n", StandardCharsets.UTF_8);
    }
}
