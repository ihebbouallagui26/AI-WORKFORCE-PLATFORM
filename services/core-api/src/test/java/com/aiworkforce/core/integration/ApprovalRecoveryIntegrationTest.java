package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.agent.MaintenanceProbe;
import com.aiworkforce.core.support.MutableClock;
import com.aiworkforce.core.support.RecordingOrchestrator;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Recovery of human approvals against the REAL persistence (PostgreSQL, Flyway, the production services):
 * a decision survives a lost notification and a restart, a resume is delivered at least once and processed at
 * most once, approvals of ended executions are closed, expiry is exact, and races have a single winner.
 *
 * <p>"Restart" means a brand-new maintenance worker with no memory of the previous one: everything it needs
 * comes from the database. The clock is frozen at exact instants (no sleeps); the orchestrator is simulated.
 */
@Import({MaintenanceProbe.class, MutableClock.class})
class ApprovalRecoveryIntegrationTest extends ToolFlows {

    private static final Object[] NOTE = {"diagnostic.record_note", "WRITE", false};

    @Autowired
    private RecordingOrchestrator.Gateway orchestrator;

    @Autowired
    private MaintenanceProbe.Probe probe;

    @Autowired
    private MutableClock.Switchable clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        orchestrator.reset();
        clock.release();
    }

    @AfterEach
    void release() {
        clock.release();
        orchestrator.reset();
    }

    private record Pending(TestUser owner, UUID org, UUID agent, UUID execution, UUID invocation, UUID approval, String hash) {
    }

    private Pending pending(String name) throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, name);
        return pendingIn(owner, org, "toolu_" + name.hashCode());
    }

    private Pending pendingIn(TestUser owner, UUID org, String callId) throws Exception {
        UUID agent = agentWithSecret(owner, org, "Atlas " + callId.hashCode());
        grant(owner, org, agent, NOTE);
        UUID execution = runningExecution(owner, org, agent);
        JsonNode decision = requestTool(org, execution, callId, "diagnostic.record_note", Map.of("text", "Remember the demo"));
        UUID approval = UUID.fromString(decision.get("approvalId").asString());
        String hash = read(getAs(owner, path(org, "/approvals/" + approval))).get("argumentsHash").asString();
        return new Pending(owner, org, agent, execution, UUID.fromString(decision.get("invocationId").asString()), approval, hash);
    }

    private org.springframework.test.web.servlet.ResultActions approve(Pending p) throws Exception {
        return postAs(p.owner(), path(p.org(), "/approvals/" + p.approval() + "/approve"), body("argumentsHash", p.hash()));
    }

    /** Resume notifications that reached the orchestrator for THIS execution (the scans are system-wide). */
    private long sent(Pending p) {
        return orchestrator.resumes().stream().filter(r -> r.executionId().equals(p.execution())).count();
    }

    private Map<String, Object> approvalRow(UUID id) {
        return jdbc.queryForMap("SELECT status, decided_at, resume_attempts, last_resume_attempt_at, resume_acknowledged_at FROM tool_approvals WHERE id = ?", id);
    }

    private String executionStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM agent_executions WHERE id = ?", String.class, id);
    }

    private String executionError(UUID id) {
        return jdbc.queryForObject("SELECT error_code FROM agent_executions WHERE id = ?", String.class, id);
    }

    private String invocationStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM tool_invocations WHERE id = ?", String.class, id);
    }

    private int audit(UUID org, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE organization_id = ? AND action = ?", Integer.class, org, action);
    }

    private Instant expiresAt(UUID approval) {
        return jdbc.queryForObject("SELECT expires_at FROM tool_approvals WHERE id = ?", Timestamp.class, approval).toInstant();
    }

    private Instant decidedAt(UUID approval) {
        return jdbc.queryForObject("SELECT decided_at FROM tool_approvals WHERE id = ?", Timestamp.class, approval).toInstant();
    }

    // ------------------------------------------------------------------ A / B: lost notification and restart

    @Test
    void anApprovalSurvivesALostNotificationAndIsResumedOnceAfterRestarts() throws Exception {
        Pending p = pending("lost-notification");
        orchestrator.resumeUnreachable(true);
        approve(p).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        probe.quiesce();

        // The decision is durable and does not depend on the delivery: approved, not delivered, not resumed.
        Map<String, Object> row = approvalRow(p.approval());
        assertThat(row.get("status")).isEqualTo("APPROVED");
        assertThat(row.get("decided_at")).isNotNull();
        assertThat(row.get("resume_acknowledged_at")).isNull();
        assertThat(sent(p)).isZero();
        assertThat(executionStatus(p.execution())).isEqualTo("WAITING_APPROVAL");
        assertThat(invocationStatus(p.invocation())).isEqualTo("APPROVED");

        // The recovery worker finds it and tries; the orchestrator is still down: the attempt is recorded, nothing is lost.
        probe.newWorker().redeliver();
        assertThat(sent(p)).isZero();
        assertThat(approvalRow(p.approval()).get("resume_attempts")).isEqualTo(1);
        assertThat(approvalRow(p.approval()).get("resume_acknowledged_at")).isNull();

        // Core API "restarts" (a worker without memory): the retry window is read from the database, so no hammering.
        probe.newWorker().redeliver();
        assertThat(approvalRow(p.approval()).get("resume_attempts")).isEqualTo(1);

        // The orchestrator is back; once the window passed, the restarted worker delivers the notification.
        orchestrator.resumeUnreachable(false);
        Instant later = Instant.now().plus(Duration.ofSeconds(11));
        clock.freeze(later);
        probe.newWorker().redeliver();
        assertThat(sent(p)).isEqualTo(1);
        // Delivered is NOT resumed: until the orchestrator takes it, the execution still waits and the entry stays open.
        assertThat(approvalRow(p.approval()).get("resume_acknowledged_at")).isNull();
        assertThat(executionStatus(p.execution())).isEqualTo("WAITING_APPROVAL");

        // Same window: a duplicate scan does not send again. A later window may (at-least-once delivery).
        probe.newWorker().redeliver();
        assertThat(sent(p)).isEqualTo(1);
        clock.freeze(later.plus(Duration.ofSeconds(11)));
        probe.newWorker().redeliver();
        assertThat(sent(p)).isEqualTo(2);

        // Processing is idempotent: the orchestrator takes the decision once, the duplicate is refused.
        serviceCall(p.org(), p.execution(), "/resume", Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("APPROVED"));
        serviceCall(p.org(), p.execution(), "/resume", Map.of()).andExpect(status().isConflict());
        assertThat(approvalRow(p.approval()).get("resume_acknowledged_at")).isNotNull();
        assertThat(executionStatus(p.execution())).isEqualTo("RUNNING");
        probe.newWorker().redeliver();                                         // acknowledged: never sent again
        assertThat(sent(p)).isEqualTo(2);

        // The tool call itself starts at most once, whatever the number of notifications.
        serviceCall(p.org(), p.execution(), "/tool-calls/" + p.invocation() + "/begin", Map.of()).andExpect(status().isOk());
        serviceCall(p.org(), p.execution(), "/tool-calls/" + p.invocation() + "/begin", Map.of()).andExpect(status().isConflict());
        assertThat(audit(p.org(), "agent.tool.started")).isEqualTo(1);
        assertThat(audit(p.org(), "agent.execution.resumed")).isEqualTo(1);
        serviceCall(p.org(), p.execution(), "/tool-calls/" + p.invocation() + "/complete", body("success", true, "output", "noted")).andExpect(status().isOk());
        serviceCall(p.org(), p.execution(), "/complete", body("result", "done")).andExpect(status().isOk());
        assertThat(executionStatus(p.execution())).isEqualTo("SUCCEEDED");
    }

    @Test
    void twoRecoveryWorkersNeverSendTheSameNotificationTwiceInAWindow() throws Exception {
        Pending p = pending("two-workers");
        orchestrator.resumeUnreachable(true);
        approve(p).andExpect(status().isOk());
        probe.quiesce();
        orchestrator.resumeUnreachable(false);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Callable<Integer>> workers = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                MaintenanceProbe.Worker worker = probe.newWorker();
                workers.add(() -> {
                    go.await();
                    return worker.redeliver();
                });
            }
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (Callable<Integer> w : workers) {
                futures.add(pool.submit(w));
            }
            go.countDown();
            for (Future<Integer> f : futures) {
                f.get();
            }
            assertThat(sent(p)).isEqualTo(1);
            assertThat(approvalRow(p.approval()).get("resume_attempts")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aDecisionTheOrchestratorNeverTakesEndsTheRunClearlyAndDestroysThePendingAction() throws Exception {
        Pending p = pending("give-up");
        orchestrator.resumeUnreachable(true);
        approve(p).andExpect(status().isOk());
        probe.quiesce();

        clock.freeze(decidedAt(p.approval()).plus(Duration.ofMinutes(15)).plusSeconds(60));
        probe.newWorker().redeliver();
        assertThat(sent(p)).isZero();

        assertThat(executionStatus(p.execution())).isEqualTo("FAILED");
        assertThat(executionError(p.execution())).isEqualTo("RESUME_FAILED");
        assertThat(approvalRow(p.approval()).get("resume_acknowledged_at")).isNotNull();            // outbox closed
        assertThat(jdbc.queryForObject("SELECT arguments_ref FROM tool_invocations WHERE id = ?", UUID.class, p.invocation())).isNull();
        assertThat(audit(p.org(), "agent.approval.resume-failed")).isEqualTo(1);
        // Nothing can run or resume any more, and nothing is retried.
        serviceCall(p.org(), p.execution(), "/resume", Map.of()).andExpect(status().isConflict());
        serviceCall(p.org(), p.execution(), "/tool-calls/" + p.invocation() + "/begin", Map.of()).andExpect(status().isConflict());
        probe.newWorker().redeliver();
        probe.newWorker().closeObsolete();
        assertThat(sent(p)).isZero();
        assertThat(audit(p.org(), "agent.approval.resume-failed")).isEqualTo(1);
        assertThat(audit(p.org(), "agent.tool.started")).isZero();
    }

    // ------------------------------------------------------------------ C: abandoned executions

    @Test
    void anApprovalOfAnExecutionThatEndedIsCancelledAndNeverRunsAnything() throws Exception {
        Pending p = pending("abandoned-pending");
        // The orchestrator gives up (or the run is failed otherwise) while the human has not answered yet.
        serviceCall(p.org(), p.execution(), "/fail", body("errorCode", "PROVIDER_ERROR", "message", "gave up")).andExpect(status().isOk());
        assertThat(approvalRow(p.approval()).get("status")).isEqualTo("PENDING");

        assertThat(probe.newWorker().closeObsolete()).isGreaterThanOrEqualTo(1);
        assertThat(approvalRow(p.approval()).get("status")).isEqualTo("CANCELLED");
        assertThat(invocationStatus(p.invocation())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT arguments_ref FROM tool_invocations WHERE id = ?", UUID.class, p.invocation())).isNull();
        assertThat(audit(p.org(), "agent.approval.cancelled")).isEqualTo(1);
        assertThat(audit(p.org(), "agent.approval.requested")).isEqualTo(1);               // history intact
        probe.newWorker().closeObsolete();                                               // idempotent
        probe.newWorker().expireDue();
        assertThat(audit(p.org(), "agent.approval.cancelled")).isEqualTo(1);
        assertThat(audit(p.org(), "agent.approval.expired")).isZero();

        // No decision, resume or start can bring it back; it frees the open-execution capacity.
        approve(p).andExpect(status().isConflict());
        postAs(p.owner(), path(p.org(), "/approvals/" + p.approval() + "/reject"), body("reason", "x")).andExpect(status().isConflict());
        serviceCall(p.org(), p.execution(), "/resume", Map.of()).andExpect(status().isConflict());
        serviceCall(p.org(), p.execution(), "/tool-calls/" + p.invocation() + "/begin", Map.of()).andExpect(status().isConflict());
        getAs(p.owner(), path(p.org(), "/approvals?status=PENDING")).andExpect(jsonPath("$.totalElements").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_executions WHERE organization_id = ? AND status IN ('PENDING','RUNNING','WAITING_APPROVAL')",
                Integer.class, p.org())).isZero();
        assertThat(audit(p.org(), "agent.tool.started")).isZero();
    }

    @Test
    void anApprovedDecisionOfAnExecutionThatEndedIsClosedInsteadOfBeingRedeliveredForever() throws Exception {
        Pending p = pending("abandoned-approved");
        orchestrator.resumeUnreachable(true);
        approve(p).andExpect(status().isOk());
        probe.quiesce();
        serviceCall(p.org(), p.execution(), "/fail", body("errorCode", "EXECUTION_TIMEOUT", "message", "timeout")).andExpect(status().isOk());
        orchestrator.resumeUnreachable(false);

        // Without the fix this entry stayed unacknowledged and was resent on every scan indefinitely.
        assertThat(probe.newWorker().closeObsolete()).isGreaterThanOrEqualTo(1);
        assertThat(approvalRow(p.approval()).get("resume_acknowledged_at")).isNotNull();
        assertThat(invocationStatus(p.invocation())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT arguments_ref FROM tool_invocations WHERE id = ?", UUID.class, p.invocation())).isNull();
        clock.freeze(Instant.now().plus(Duration.ofMinutes(5)));
        probe.newWorker().redeliver();
        assertThat(sent(p)).isZero();                                                     // an obsolete event restarts nothing
        serviceCall(p.org(), p.execution(), "/resume", Map.of()).andExpect(status().isConflict());
        serviceCall(p.org(), p.execution(), "/tool-calls/" + p.invocation() + "/begin", Map.of()).andExpect(status().isConflict());
        assertThat(executionStatus(p.execution())).isEqualTo("FAILED");
        assertThat(audit(p.org(), "agent.tool.started")).isZero();
    }

    @Test
    void theMaintenanceOfAnotherOrganizationCannotTouchAnApproval() throws Exception {
        Pending p = pending("tenant-a");
        UUID other = UUID.randomUUID();
        jdbc.update("UPDATE tool_approvals SET requested_at = now() - interval '2 hours', expires_at = now() - interval '1 hour' WHERE id = ?", p.approval());
        probe.expireAs(other, p.approval());
        assertThat(approvalRow(p.approval()).get("status")).isEqualTo("PENDING");
        assertThat(executionStatus(p.execution())).isEqualTo("WAITING_APPROVAL");
        // The owning organization's own maintenance does expire it.
        assertThat(probe.newWorker().expireDue()).isGreaterThanOrEqualTo(1);
        assertThat(approvalRow(p.approval()).get("status")).isEqualTo("EXPIRED");
    }

    @Test
    void aCallStillRunningWhenItsExecutionEndsIsClosedWithAnUnknownOutcomeAndNeverRestarted() throws Exception {
        Pending p = pending("running-then-ended");
        approve(p).andExpect(status().isOk());
        serviceCall(p.org(), p.execution(), "/resume", Map.of()).andExpect(status().isOk());
        serviceCall(p.org(), p.execution(), "/tool-calls/" + p.invocation() + "/begin", Map.of()).andExpect(status().isOk());
        assertThat(invocationStatus(p.invocation())).isEqualTo("RUNNING");
        // The orchestrator disappears mid-call (or reports a failure): the execution ends, the call was never completed.
        serviceCall(p.org(), p.execution(), "/fail", body("errorCode", "EXECUTION_TIMEOUT", "message", "timeout")).andExpect(status().isOk());
        assertThat(invocationStatus(p.invocation())).isEqualTo("RUNNING");        // before the maintenance pass

        probe.newWorker().closeObsolete();
        assertThat(invocationStatus(p.invocation())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM tool_invocations WHERE id = ?", String.class, p.invocation())).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(audit(p.org(), "agent.tool.outcome-unknown")).isEqualTo(1);
        probe.newWorker().closeObsolete();                                         // idempotent
        assertThat(audit(p.org(), "agent.tool.outcome-unknown")).isEqualTo(1);
        // It can neither be completed late nor started again.
        serviceCall(p.org(), p.execution(), "/tool-calls/" + p.invocation() + "/begin", Map.of()).andExpect(status().isConflict());
        serviceCall(p.org(), p.execution(), "/tool-calls/" + p.invocation() + "/complete", body("success", true, "output", "late")).andExpect(status().isConflict());
        assertThat(audit(p.org(), "agent.tool.started")).isEqualTo(1);             // the business effect was attempted once, never twice
    }

    // ------------------------------------------------------------------ D: expiration

    @Test
    void approvalsExpireExactlyAtTheirDeadlineAndNeverRunAfterwards() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Expiry boundary");
        Pending justBefore = pendingIn(owner, org, "toolu_before");
        Pending atDeadline = pendingIn(owner, org, "toolu_at");
        Pending byWorker = pendingIn(owner, org, "toolu_worker");

        // Still pending one millisecond before the deadline: not expired, and it can be approved.
        clock.freeze(expiresAt(justBefore.approval()).minusMillis(1));
        probe.newWorker().expireDue();
        assertThat(approvalRow(justBefore.approval()).get("status")).isEqualTo("PENDING");
        approve(justBefore).andExpect(status().isOk());

        // At the exact deadline the approval is expired: it cannot be approved, and the refusal is made durable.
        clock.freeze(expiresAt(atDeadline.approval()));
        approve(atDeadline).andExpect(status().isConflict());
        assertThat(approvalRow(atDeadline.approval()).get("status")).isEqualTo("EXPIRED");
        assertThat(executionStatus(atDeadline.execution())).isEqualTo("FAILED");
        assertThat(executionError(atDeadline.execution())).isEqualTo("APPROVAL_EXPIRED");
        assertThat(invocationStatus(atDeadline.invocation())).isEqualTo("EXPIRED");
        serviceCall(org, atDeadline.execution(), "/tool-calls/" + atDeadline.invocation() + "/begin", Map.of()).andExpect(status().isConflict());

        // A restarted worker expires the one nobody touched; running it twice (or twice at once) is harmless.
        clock.freeze(expiresAt(byWorker.approval()).plusSeconds(1));
        assertThat(probe.newWorker().expireDue()).isGreaterThanOrEqualTo(1);
        probe.newWorker().expireDue();
        probe.expireAs(org, byWorker.approval());
        assertThat(approvalRow(byWorker.approval()).get("status")).isEqualTo("EXPIRED");
        assertThat(executionStatus(byWorker.execution())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT arguments_ref FROM tool_invocations WHERE id = ?", UUID.class, byWorker.invocation())).isNull();

        // Exactly one expiry audit per expired approval, and the approved one was left alone.
        assertThat(audit(org, "agent.approval.expired")).isEqualTo(2);
        assertThat(approvalRow(justBefore.approval()).get("status")).isEqualTo("APPROVED");
        assertThat(audit(org, "agent.tool.started")).isZero();
    }

    // ------------------------------------------------------------------ E: races

    @Test
    void approvalAndExpirationRacingAtTheDeadlineLeaveOneConsistentOutcome() throws Exception {
        Pending p = pending("race-expiry");
        clock.freeze(expiresAt(p.approval()));
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Callable<Object>> tasks = List.of(
                    () -> {
                        go.await();
                        return approve(p).andReturn().getResponse().getStatus();
                    },
                    () -> {
                        go.await();
                        return probe.newWorker().expireDue();
                    },
                    () -> {
                        go.await();
                        return approve(p).andReturn().getResponse().getStatus();
                    });
            List<Future<Object>> futures = new java.util.ArrayList<>();
            for (Callable<Object> t : tasks) {
                futures.add(pool.submit(t));
            }
            go.countDown();
            for (Future<Object> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(approvalRow(p.approval()).get("status")).isEqualTo("EXPIRED");
        assertThat(executionStatus(p.execution())).isEqualTo("FAILED");
        assertThat(audit(p.org(), "agent.approval.expired")).isEqualTo(1);
        assertThat(audit(p.org(), "agent.approval.approved")).isZero();
        assertThat(approvalRow(p.approval()).get("decided_at")).isNull();
    }

    @Test
    void approvalAndTheEndOfTheExecutionRacingNeverLetTheActionRun() throws Exception {
        for (int round = 0; round < 3; round++) {
            Pending p = pending("race-fail-" + round);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch go = new CountDownLatch(1);
                Future<Integer> decided = pool.submit(() -> {
                    go.await();
                    return approve(p).andReturn().getResponse().getStatus();
                });
                Future<Integer> failed = pool.submit(() -> {
                    go.await();
                    return serviceCall(p.org(), p.execution(), "/fail", body("errorCode", "EXECUTION_FAILED", "message", "stop"))
                            .andReturn().getResponse().getStatus();
                });
                go.countDown();
                assertThat(failed.get()).isEqualTo(200);
                assertThat(decided.get()).isIn(200, 409);                       // either order is valid; both are consistent
            } finally {
                pool.shutdownNow();
            }
            probe.newWorker().closeObsolete();
            assertThat(executionStatus(p.execution())).isEqualTo("FAILED");
            assertThat(invocationStatus(p.invocation())).isEqualTo("FAILED");
            assertThat(approvalRow(p.approval()).get("status")).isIn("CANCELLED", "APPROVED");
            serviceCall(p.org(), p.execution(), "/resume", Map.of()).andExpect(status().isConflict());
            serviceCall(p.org(), p.execution(), "/tool-calls/" + p.invocation() + "/begin", Map.of()).andExpect(status().isConflict());
            assertThat(audit(p.org(), "agent.tool.started")).isZero();
        }
    }
}
