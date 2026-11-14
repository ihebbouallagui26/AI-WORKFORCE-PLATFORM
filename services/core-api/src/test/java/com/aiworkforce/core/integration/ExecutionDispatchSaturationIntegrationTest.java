package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.support.RecordingOrchestrator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Saturation of the hand-over pool (one thread, no queue): a rejected hand-over must leave a truthful,
 * persisted FAILED execution with a safe code, an API answer that carries the execution id, no
 * orchestration call on the request thread, and no impact on the hand-over already in progress.
 */
@TestPropertySource(properties = {
        "app.executions.dispatch-max-threads=1",
        "app.executions.dispatch-queue-capacity=0",
        "app.executions.max-concurrent-per-organization=10"})
class ExecutionDispatchSaturationIntegrationTest extends AgentFlows {

    @Autowired
    private RecordingOrchestrator.Gateway orchestrator;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetOrchestrator() {
        orchestrator.reset();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).as("condition within 5 s").isTrue();
    }

    @Test
    void aRejectedHandOverFailsTheExecutionTruthfullyAndNeverRunsOnTheRequestThread() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Saturation");
        UUID agent = activeAgent(owner, org, "Atlas");

        // Hand-over #1 occupies the only thread of the pool.
        CountDownLatch gate = new CountDownLatch(1);
        orchestrator.gate(gate);
        UUID first = id(read(postAs(owner, agents(org, "/" + agent + "/executions"), body("task", "first"))
                .andExpect(status().isAccepted())));
        await(() -> orchestrator.attempts() == 1);

        // #2 is rejected by the pool: still a 202 with the execution id, and the state is already truthful.
        JsonNode second = read(postAs(owner, agents(org, "/" + agent + "/executions"), body("task", "second"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("DISPATCH_CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.errorMessage").value("The system is busy. Try again in a moment."))
                .andExpect(jsonPath("$.completedAt").exists()));
        UUID secondId = id(second);

        // The record was persisted and committed (readable by id, listed, one row, audited).
        getAs(owner, path(org, "/executions/" + secondId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.task").value("second"));
        getAs(owner, path(org, "/executions")).andExpect(jsonPath("$.totalElements").value(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_executions WHERE id = ?", Integer.class, secondId)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT details::text FROM audit_events WHERE action = 'agent.execution.failed' AND organization_id = ?",
                String.class, org)).anyMatch(details -> details.contains("DISPATCH_CAPACITY_EXCEEDED"));

        // The rejected hand-over never reached the orchestrator, and no hand-over ran on a request thread.
        assertThat(orchestrator.attempts()).isEqualTo(1);
        assertThat(orchestrator.threads()).allMatch(name -> name.startsWith("exec-dispatch-"));

        // The hand-over in progress is unaffected, and capacity is available again afterwards.
        gate.countDown();
        await(() -> orchestrator.dispatched().size() == 1);
        assertThat(orchestrator.dispatched().getFirst().executionId()).isEqualTo(first);
        getAs(owner, path(org, "/executions/" + first)).andExpect(jsonPath("$.status").value("PENDING"));
        UUID third = id(read(postAs(owner, agents(org, "/" + agent + "/executions"), body("task", "third"))
                .andExpect(status().isAccepted())));
        await(() -> orchestrator.dispatched().size() == 2);
        assertThat(orchestrator.dispatched()).extracting(RecordingOrchestrator.Dispatched::executionId).containsExactly(first, third);
        assertThat(orchestrator.threads()).allMatch(name -> name.startsWith("exec-dispatch-"));
        assertThat(List.of(orchestrator.threads().size())).containsExactly(2);
    }
}
