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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Controlled measurements of parallel execution (C3.4). The workers are SIMULATED: each one "works" for a fixed time
 * ({@value #WORK_MS} ms) between its start and its completion, played through the real internal API against the real scheduler and
 * PostgreSQL. What is measured is therefore the orchestration cost and the overlap, NOT model or provider latency. Results are
 * written to {@code target/parallel-measurements.txt} and printed; the assertions only check what must hold (overlap, bounds).
 */
@Import({WorkflowProbe.class, MaintenanceProbe.class})
@TestPropertySource(properties = {"app.workflows.maintenance-ms=3600000", "app.executions.approval-maintenance-ms=3600000",
        "app.workflows.worker-token-reservation=1000"})
class WorkflowParallelMeasurementIntegrationTest extends ToolFlows {

    private static final long WORK_MS = 400;

    @Autowired
    private RecordingOrchestrator.Gateway orchestrator;

    @Autowired
    private WorkflowProbe.Probe probe;

    private final List<String> lines = new ArrayList<>();

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

    private List<JsonNode> steps(TestUser owner, UUID org, UUID run) throws Exception {
        JsonNode content = read(getAs(owner, runs(org, "/" + run + "/steps?size=100")).andExpect(status().isOk())).get("content");
        List<JsonNode> list = new ArrayList<>();
        for (int i = 0; i < content.size(); i++) {
            list.add(content.get(i));
        }
        return list;
    }

    /** One workflow of {@code n} independent workers followed by a synthesis step; {@code parallel} chooses stages or a plain sequence. */
    private long[] measure(int n, boolean parallel) throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Measure " + UUID.randomUUID().toString().substring(0, 8));
        List<UUID> agents = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            agents.add(agentWithSecret(owner, org, "Agent " + i));
        }
        UUID workflow = id(read(postAs(owner, wf(org, ""), body("name", "M", "maxParallelWorkers", parallel ? 3 : 1)).andExpect(status().isCreated())));
        putAs(owner, wf(org, "/" + workflow + "/participants"), body("agentIds", agents)).andExpect(status().isOk());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            Map<String, Object> step = body("name", "S" + i, "agentId", agents.get(i), "instruction", "work " + i);
            if (parallel) {
                step.put("stage", i == n ? 2 : 1);
            }
            steps.add(step);
        }
        putAs(owner, wf(org, "/" + workflow + "/steps"), body("steps", steps)).andExpect(status().isOk());

        long t0 = System.nanoTime();
        UUID run = id(read(postAs(owner, wf(org, "/" + workflow + "/runs"), body("objective", "measure")).andExpect(status().isAccepted())));
        long dispatchedMs = (System.nanoTime() - t0) / 1_000_000;           // run start -> first stage dispatched (child executions created)
        long passNs = 0;
        int passes = 0;
        int stagesDone = 0;
        while (true) {
            List<JsonNode> now = steps(owner, org, run);
            List<UUID> open = new ArrayList<>();
            for (JsonNode s : now) {
                if (!s.get("executionId").isNull() && (s.get("status").asString().equals("RUNNING"))) {
                    open.add(UUID.fromString(s.get("executionId").asString()));
                }
            }
            if (open.isEmpty()) {
                break;
            }
            ExecutorService pool = Executors.newFixedThreadPool(open.size());
            List<Future<Void>> futures = new ArrayList<>();
            for (UUID execution : open) {
                Callable<Void> work = () -> {
                    serviceCall(org, execution, "/start", Map.of()).andExpect(status().isOk());
                    Thread.sleep(WORK_MS);                                    // the simulated agent turn
                    serviceCall(org, execution, "/complete", body("result", "r", "inputTokens", 10, "outputTokens", 10, "model", "m")).andExpect(status().isOk());
                    return null;
                };
                futures.add(pool.submit(work));
            }
            for (Future<Void> f : futures) {
                f.get();
            }
            pool.shutdown();
            long p0 = System.nanoTime();
            probe.newWorker().advanceOpenRuns();
            passNs += System.nanoTime() - p0;
            passes++;
            stagesDone++;
        }
        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        JsonNode summary = read(getAs(owner, runs(org, "/" + run + "/summary")).andExpect(status().isOk()));
        assertThat(read(getAs(owner, runs(org, "/" + run)).andExpect(status().isOk())).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(summary.get("reservedTokens").asInt()).isZero();
        assertThat(summary.get("inputTokens").asLong() + summary.get("outputTokens").asLong()).isEqualTo(20L * (n + 1));   // exactly once
        return new long[] {totalMs, dispatchedMs, passes == 0 ? 0 : passNs / passes / 1_000_000, summary.get("peakConcurrency").asInt(), stagesDone};
    }

    @Test
    void parallelStagesOverlapAndShortenTheWallClockComparedWithTheSameStepsInSequence() throws Exception {
        lines.add("Controlled measurement, simulated workers (" + WORK_MS + " ms of work per worker), real scheduler + PostgreSQL (Testcontainers),");
        lines.add("N independent workers + 1 synthesis step. columns: total wall ms | first-dispatch ms | mean scheduler pass ms | peak concurrency | rounds");
        long[] sequentialThree = null;
        long[] parallelThree = null;
        for (int n : new int[] {1, 2, 3}) {
            long[] seq = measure(n, false);
            long[] par = measure(n, true);
            lines.add("N=" + n + "  sequential: " + seq[0] + " | " + seq[1] + " | " + seq[2] + " | " + seq[3] + " | " + seq[4]);
            lines.add("N=" + n + "  parallel  : " + par[0] + " | " + par[1] + " | " + par[2] + " | " + par[3] + " | " + par[4]);
            if (n == 3) {
                sequentialThree = seq;
                parallelThree = par;
            }
        }
        Files.writeString(Path.of("target", "parallel-measurements.txt"), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        lines.forEach(System.out::println);
        assertThat(parallelThree[3]).isEqualTo(3);                              // three workers overlapped
        assertThat(sequentialThree[3]).isEqualTo(1);
        // Three workers in parallel take clearly less wall time than the same three in sequence (each simulated turn is 400 ms).
        assertThat(parallelThree[0]).isLessThan(sequentialThree[0] - WORK_MS);
    }
}
