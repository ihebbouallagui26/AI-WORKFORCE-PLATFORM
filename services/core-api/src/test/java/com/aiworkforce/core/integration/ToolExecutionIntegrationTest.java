package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.support.RecordingOrchestrator;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Tool execution and human approval, Core API side: the platform decides every call from the registry and
 * the agent's explicit grant; human approval is bound to ONE exact call; everything is organization scoped,
 * audited and secret-free. The orchestrator is played through its internal API.
 */
class ToolExecutionIntegrationTest extends ToolFlows {

    private static final Object[] ECHO = {"diagnostic.echo", "READ", false};
    private static final Object[] NOTE = {"diagnostic.record_note", "WRITE", false};

    @Autowired
    private RecordingOrchestrator.Gateway orchestrator;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetOrchestrator() {
        orchestrator.reset();
    }

    private static String approvals(UUID org, String suffix) {
        return path(org, "/approvals" + suffix);
    }

    private String hashOf(JsonNode approval) {
        return approval.get("argumentsHash").asString();
    }

    private JsonNode pendingApproval(TestUser owner, UUID org, UUID approvalId) throws Exception {
        return read(getAs(owner, approvals(org, "/" + approvalId)).andExpect(status().isOk()));
    }

    // ------------------------------------------------------------------ what the agent may use

    @Test
    void theExecutionContextAndAvailableToolsExposeOnlyWhatThePlatformWouldAllow() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Tool availability");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        // echo (automatic), record_note (needs a human), a B3 placeholder (policy DENIED), add (not granted).
        grant(owner, org, agent, ECHO, NOTE, new Object[] {"docs.search", "READ", false});

        // Placeholders and anything without an execution backend are not offered to the model.
        asService(get("/api/v1/internal/organizations/" + org + "/agents/" + agent + "/execution-context"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tools", hasSize(2)))
                .andExpect(jsonPath("$.tools[?(@.toolCode=='diagnostic.echo')].executionPolicy").value("READ_ONLY"))
                .andExpect(jsonPath("$.tools[?(@.toolCode=='diagnostic.echo')].approvalRequired").value(false))
                .andExpect(jsonPath("$.tools[?(@.toolCode=='diagnostic.echo')].inputSchema.type").value("object"))
                .andExpect(jsonPath("$.tools[?(@.toolCode=='diagnostic.record_note')].approvalRequired").value(true))
                .andExpect(jsonPath("$.tools[?(@.toolCode=='diagnostic.record_note')].requiredLevel").value("WRITE"))
                .andExpect(jsonPath("$.tools[?(@.toolCode=='docs.search')]").isEmpty());

        getAs(owner, agents(org, "/" + agent + "/available-tools")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[?(@.toolCode=='diagnostic.echo')].decision").value("AUTO"))
                .andExpect(jsonPath("$[?(@.toolCode=='diagnostic.record_note')].decision").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$[?(@.toolCode=='docs.search')].decision").value("DENIED"))
                .andExpect(jsonPath("$[?(@.toolCode=='docs.search')].reasonCode").value("TOOL_DENIED"));
    }

    @Test
    void aReadGrantOnAWriteToolIsInsufficientAndGrantApprovalFlagForcesAHuman() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Levels");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        grant(owner, org, agent, new Object[] {"diagnostic.record_note", "READ", false}, new Object[] {"diagnostic.echo", "READ", true});
        getAs(owner, agents(org, "/" + agent + "/available-tools"))
                .andExpect(jsonPath("$[?(@.toolCode=='diagnostic.record_note')].decision").value("DENIED"))
                .andExpect(jsonPath("$[?(@.toolCode=='diagnostic.record_note')].reasonCode").value("INSUFFICIENT_LEVEL"))
                // A grant that asks for approval makes even a READ_ONLY tool wait for a person.
                .andExpect(jsonPath("$[?(@.toolCode=='diagnostic.echo')].decision").value("APPROVAL_REQUIRED"));
    }

    // ------------------------------------------------------------------ automatic (READ_ONLY) calls

    @Test
    void aReadOnlyToolRunsAutomaticallyOnceAndItsRecordIsSecretFreeAndAudited() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Auto tool");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        grant(owner, org, agent, ECHO);
        UUID execution = runningExecution(owner, org, agent);

        JsonNode decision = requestTool(org, execution, "toolu_01", "diagnostic.echo",
                Map.of("text", "hello", "api_key", "sk-supersecret-0123456789abcdef"));
        assertThat(decision.get("decision").asString()).isEqualTo("AUTO");
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());

        // begin: revalidated now, arguments returned once, never cached.
        String begun = serviceCall(org, execution, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.mcpServer").value("diagnostic")).andExpect(jsonPath("$.mcpTool").value("echo"))
                .andExpect(jsonPath("$.arguments.text").value("hello")).andExpect(jsonPath("$.timeoutSeconds").value(10))
                .andReturn().getResponse().getContentAsString();
        assertThat(begun).contains("sk-supersecret"); // the service needs the real arguments
        // At most once: a second begin is refused.
        serviceCall(org, execution, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isConflict());

        serviceCall(org, execution, "/tool-calls/" + invocation + "/complete", body("success", true, "output", "hello"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        // A final call cannot be completed or restarted again.
        serviceCall(org, execution, "/tool-calls/" + invocation + "/complete", body("success", true, "output", "again"))
                .andExpect(status().isConflict());

        // The timeline shows it, with the REDACTED arguments only.
        String timeline = getAs(owner, path(org, "/executions/" + execution + "/tool-calls")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.content[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.content[0].toolName").value("Echo text"))
                .andExpect(jsonPath("$.content[0].result").value("hello"))
                .andReturn().getResponse().getContentAsString();
        assertThat(timeline).doesNotContain("sk-supersecret").contains("[REDACTED]");

        // No raw secret anywhere in the persisted rows or the audit trail; the arguments copy is gone.
        for (String row : jdbc.queryForList("SELECT row_to_json(i)::text FROM tool_invocations i WHERE organization_id = ?", String.class, org)) {
            assertThat(row).doesNotContain("sk-supersecret");
        }
        assertThat(jdbc.queryForObject("SELECT arguments_ref FROM tool_invocations WHERE id = ?", UUID.class, invocation)).isNull();
        List<String> audit = jdbc.queryForList("SELECT action FROM audit_events WHERE organization_id = ? AND action LIKE 'agent.tool.%' ORDER BY occurred_at",
                String.class, org);
        assertThat(audit).containsExactly("agent.tool.requested", "agent.tool.started", "agent.tool.succeeded");
        for (String details : jdbc.queryForList("SELECT details::text FROM audit_events WHERE organization_id = ?", String.class, org)) {
            assertThat(details).doesNotContain("sk-supersecret").doesNotContain(SECRET);
        }
    }

    // ------------------------------------------------------------------ denials

    @Test
    void everythingThatIsNotExplicitlyAllowedIsDeniedAndRecorded() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Denials");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        grant(owner, org, agent, ECHO, new Object[] {"docs.search", "READ", false}, new Object[] {"diagnostic.record_note", "READ", false});
        UUID execution = runningExecution(owner, org, agent);

        // No grant for add; the B3 placeholder has policy DENIED; a READ grant cannot cover a WRITE tool.
        assertThat(requestTool(org, execution, "c1", "diagnostic.add", Map.of("a", 1, "b", 2)).get("code").asString()).isEqualTo("NOT_GRANTED");
        assertThat(requestTool(org, execution, "c2", "docs.search", Map.of("q", "x")).get("code").asString()).isEqualTo("TOOL_DENIED");
        assertThat(requestTool(org, execution, "c3", "diagnostic.record_note", text("n")).get("code").asString()).isEqualTo("INSUFFICIENT_LEVEL");
        // Unknown tools are always denied (and leave no invocation row, only an audit record).
        JsonNode unknown = requestTool(org, execution, "c4", "shell.run", Map.of("cmd", "rm -rf /"));
        assertThat(unknown.get("decision").asString()).isEqualTo("DENIED");
        assertThat(unknown.get("code").asString()).isEqualTo("UNKNOWN_TOOL");
        assertThat(unknown.get("invocationId").isNull()).isTrue();
        // Non-object arguments and oversized arguments are denied.
        assertThat(requestTool(org, execution, "c5", "diagnostic.echo", List.of(1, 2)).get("code").asString()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(requestTool(org, execution, "c6", "diagnostic.echo", text("x".repeat(9000))).get("code").asString()).isEqualTo("ARGUMENTS_TOO_LARGE");

        getAs(owner, path(org, "/executions/" + execution + "/tool-calls")).andExpect(jsonPath("$.content", hasSize(5)))
                .andExpect(jsonPath("$.content[*].status").value(everyItem(is("DENIED"))));
        // Denied calls can never be started, and nothing was sealed for them.
        UUID denied = UUID.fromString(read(getAs(owner, path(org, "/executions/" + execution + "/tool-calls"))).get("content").get(0).get("id").asString());
        serviceCall(org, execution, "/tool-calls/" + denied + "/begin", Map.of()).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tool_invocations WHERE organization_id = ? AND arguments_ref IS NOT NULL",
                Integer.class, org)).isZero();
        assertThat(jdbc.queryForList("SELECT details::text FROM audit_events WHERE action = 'agent.tool.denied' AND organization_id = ?",
                String.class, org)).hasSize(6).noneMatch(d -> d.contains("rm -rf"));
    }

    @Test
    void theToolCallLimitAndReplaysAreEnforced() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Limits");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        grant(owner, org, agent, ECHO);
        UUID execution = runningExecution(owner, org, agent);

        JsonNode first = requestTool(org, execution, "call-1", "diagnostic.echo", text("a"));
        // The same provider call id is idempotent: no second invocation.
        assertThat(requestTool(org, execution, "call-1", "diagnostic.echo", text("a")).get("invocationId").asString())
                .isEqualTo(first.get("invocationId").asString());
        // The same id with other arguments or another tool is refused (an attempt to reuse a decision).
        serviceCall(org, execution, "/tool-calls", body("toolCallId", "call-1", "toolCode", "diagnostic.echo", "arguments", text("CHANGED")))
                .andExpect(status().isConflict());
        // A malformed call id is refused.
        serviceCall(org, execution, "/tool-calls", body("toolCallId", "bad id!", "toolCode", "diagnostic.echo", "arguments", text("a")))
                .andExpect(status().isConflict());

        for (int i = 2; i <= 10; i++) {
            assertThat(requestTool(org, execution, "call-" + i, "diagnostic.echo", text("a")).get("decision").asString()).isEqualTo("AUTO");
        }
        assertThat(requestTool(org, execution, "call-11", "diagnostic.echo", text("a")).get("code").asString()).isEqualTo("TOOL_CALL_LIMIT");
    }

    @Test
    void aToolCanOnlyBeRequestedWhileTheExecutionIsRunning() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "States");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        grant(owner, org, agent, ECHO);
        UUID pending = launch(owner, org, agent, "not started");
        serviceCall(org, pending, "/tool-calls", body("toolCallId", "c", "toolCode", "diagnostic.echo", "arguments", text("a")))
                .andExpect(status().isConflict());
        UUID done = runningExecution(owner, org, agent);
        serviceCall(org, done, "/complete", body("result", "done")).andExpect(status().isOk());
        serviceCall(org, done, "/tool-calls", body("toolCallId", "c", "toolCode", "diagnostic.echo", "arguments", text("a")))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ human approval

    @Test
    void anApprovalRequiredToolPausesTheExecutionUntilAPersonApprovesThatExactCall() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Approval flow");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        grant(owner, org, agent, NOTE);
        UUID execution = runningExecution(owner, org, agent);

        JsonNode decision = requestTool(org, execution, "toolu_note", "diagnostic.record_note",
                Map.of("text", "Remember the demo", "password", "hunter2-secret"));
        assertThat(decision.get("decision").asString()).isEqualTo("APPROVAL_REQUIRED");
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());
        UUID approval = UUID.fromString(decision.get("approvalId").asString());

        // The execution is suspended and visible as such.
        getAs(owner, path(org, "/executions/" + execution)).andExpect(jsonPath("$.status").value("WAITING_APPROVAL"));
        // Nothing may start before the decision.
        serviceCall(org, execution, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isConflict());
        serviceCall(org, execution, "/resume", Map.of()).andExpect(status().isConflict());

        // The approval card: exact scope, masked arguments, no secret.
        String card = getAs(owner, approvals(org, "/" + approval)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.toolCode").value("diagnostic.record_note"))
                .andExpect(jsonPath("$.toolCallId").value("toolu_note")).andExpect(jsonPath("$.risk").value("MEDIUM"))
                .andExpect(jsonPath("$.agentName").value("Atlas")).andExpect(jsonPath("$.reason").value("Because the task needs it"))
                .andExpect(jsonPath("$.executionStatus").value("WAITING_APPROVAL"))
                .andReturn().getResponse().getContentAsString();
        assertThat(card).doesNotContain("hunter2").contains("[REDACTED]");
        String hash = hashOf(read(getAs(owner, approvals(org, "/" + approval))));
        getAs(owner, approvals(org, "?status=PENDING")).andExpect(jsonPath("$.totalElements").value(1));

        // A stale or altered review is refused; a missing hash is a validation error.
        postAs(owner, approvals(org, "/" + approval + "/approve"), body("argumentsHash", "0".repeat(64))).andExpect(status().isConflict());
        postAs(owner, approvals(org, "/" + approval + "/approve"), body("argumentsHash", "nope")).andExpect(status().isBadRequest());
        postAs(owner, approvals(org, "/" + approval + "/approve"), body("argumentsHash", hash)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.decidedBy").exists())
                .andExpect(jsonPath("$.decidedAt").exists());
        // The decision is final: no replay, no contradicting decision.
        postAs(owner, approvals(org, "/" + approval + "/approve"), body("argumentsHash", hash)).andExpect(status().isConflict());
        postAs(owner, approvals(org, "/" + approval + "/reject"), body("reason", "changed my mind")).andExpect(status().isConflict());

        // The orchestrator is told (durable outbox), takes the decision, and only then may the call start.
        await(() -> !orchestrator.resumes().isEmpty());
        serviceCall(org, execution, "/resume", Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("APPROVED"))
                .andExpect(jsonPath("$.invocationId").value(invocation.toString()));
        serviceCall(org, execution, "/resume", Map.of()).andExpect(status().isConflict());     // exactly once per pause
        getAs(owner, path(org, "/executions/" + execution)).andExpect(jsonPath("$.status").value("RUNNING"));

        serviceCall(org, execution, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.arguments.text").value("Remember the demo"));
        serviceCall(org, execution, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isConflict());
        serviceCall(org, execution, "/tool-calls/" + invocation + "/complete", body("success", true, "output", "noted")).andExpect(status().isOk());

        // The approval authorized that call only: a second call of the same tool needs a new approval.
        JsonNode second = requestTool(org, execution, "toolu_note_2", "diagnostic.record_note", text("Another note"));
        assertThat(second.get("decision").asString()).isEqualTo("APPROVAL_REQUIRED");
        assertThat(second.get("approvalId").asString()).isNotEqualTo(approval.toString());

        List<String> audit = jdbc.queryForList("SELECT action FROM audit_events WHERE organization_id = ? AND action LIKE 'agent.approval.%' ORDER BY occurred_at",
                String.class, org);
        assertThat(audit).contains("agent.approval.requested", "agent.approval.approved");
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM audit_events WHERE action = 'agent.approval.approved' AND organization_id = ?",
                UUID.class, org)).isNotNull();
    }

    @Test
    void aRejectedActionIsNeverExecutedAndTheAgentIsTold() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Rejection");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        grant(owner, org, agent, NOTE);
        UUID execution = runningExecution(owner, org, agent);
        JsonNode decision = requestTool(org, execution, "toolu_r", "diagnostic.record_note", text("Do it"));
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());
        UUID approval = UUID.fromString(decision.get("approvalId").asString());

        postAs(owner, approvals(org, "/" + approval + "/reject"), body("reason", "Not now")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED")).andExpect(jsonPath("$.rejectionReason").value("Not now"))
                .andExpect(jsonPath("$.invocationStatus").value("REJECTED"));
        serviceCall(org, execution, "/resume", Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Not now"));
        // It can never start, whatever the orchestrator does.
        serviceCall(org, execution, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT arguments_ref FROM tool_invocations WHERE id = ?", UUID.class, invocation)).isNull();
        postAs(owner, approvals(org, "/" + approval + "/approve"), body("argumentsHash", "0".repeat(64))).andExpect(status().isConflict());
    }

    @Test
    void anExpiredApprovalCannotBeApprovedAndFailsTheExecution() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Expiry");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        grant(owner, org, agent, NOTE);
        UUID execution = runningExecution(owner, org, agent);
        JsonNode decision = requestTool(org, execution, "toolu_e", "diagnostic.record_note", text("Late"));
        UUID approval = UUID.fromString(decision.get("approvalId").asString());
        String hash = hashOf(pendingApproval(owner, org, approval));

        jdbc.update("UPDATE tool_approvals SET requested_at = now() - interval '2 hours', expires_at = now() - interval '1 hour' WHERE id = ?", approval);
        postAs(owner, approvals(org, "/" + approval + "/approve"), body("argumentsHash", hash)).andExpect(status().isConflict());

        // The refusal made the expiry durable: approval EXPIRED, call EXPIRED, execution failed with a clear code.
        getAs(owner, approvals(org, "/" + approval)).andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.invocationStatus").value("EXPIRED")).andExpect(jsonPath("$.decidedBy").doesNotExist());
        getAs(owner, path(org, "/executions/" + execution)).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_EXPIRED"));
        postAs(owner, approvals(org, "/" + approval + "/reject"), body("reason", "x")).andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ permissions revoked, tenancy, RBAC

    @Test
    void aValidApprovalNeverReplacesAMissingOrRevokedGrant() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Revocation");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        grant(owner, org, agent, NOTE);

        // (1) The grant is revoked while the request waits: approving is refused.
        UUID first = runningExecution(owner, org, agent);
        JsonNode waiting = requestTool(org, first, "toolu_a", "diagnostic.record_note", text("one"));
        UUID approvalOne = UUID.fromString(waiting.get("approvalId").asString());
        String hashOne = hashOf(pendingApproval(owner, org, approvalOne));
        grant(owner, org, agent);                                     // no tool at all any more
        postAs(owner, approvals(org, "/" + approvalOne + "/approve"), body("argumentsHash", hashOne)).andExpect(status().isConflict());

        // (2) The approval exists, then the grant is revoked before the call starts: begin refuses and closes the call.
        grant(owner, org, agent, NOTE);
        UUID second = runningExecution(owner, org, agent);
        JsonNode second_ = requestTool(org, second, "toolu_b", "diagnostic.record_note", text("two"));
        UUID invocation = UUID.fromString(second_.get("invocationId").asString());
        UUID approvalTwo = UUID.fromString(second_.get("approvalId").asString());
        postAs(owner, approvals(org, "/" + approvalTwo + "/approve"), body("argumentsHash", hashOf(pendingApproval(owner, org, approvalTwo))))
                .andExpect(status().isOk());
        serviceCall(org, second, "/resume", Map.of()).andExpect(status().isOk());
        grant(owner, org, agent);                                     // revoked after the human said yes
        serviceCall(org, second, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isConflict());
        getAs(owner, path(org, "/executions/" + second + "/tool-calls")).andExpect(jsonPath("$.content[0].status").value("FAILED"))
                .andExpect(jsonPath("$.content[0].errorCode").value("PERMISSION_REVOKED"));
        assertThat(jdbc.queryForObject("SELECT arguments_ref FROM tool_invocations WHERE id = ?", UUID.class, invocation)).isNull();
    }

    @Test
    void anAgentThatIsPausedAfterTheApprovalCannotRunTheTool() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Agent state");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        grant(owner, org, agent, NOTE);
        UUID execution = runningExecution(owner, org, agent);
        JsonNode decision = requestTool(org, execution, "toolu_p", "diagnostic.record_note", text("x"));
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());
        UUID approval = UUID.fromString(decision.get("approvalId").asString());
        postAs(owner, approvals(org, "/" + approval + "/approve"), body("argumentsHash", hashOf(pendingApproval(owner, org, approval)))).andExpect(status().isOk());
        serviceCall(org, execution, "/resume", Map.of()).andExpect(status().isOk());
        postAs(owner, agents(org, "/" + agent + "/pause"), "{}").andExpect(status().isOk());
        serviceCall(org, execution, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isConflict());
    }

    @Test
    void approvalsAreProtectedByRbacAndOrganizationIsolation() throws Exception {
        TestUser alice = newUser("alice");
        TestUser bob = newUser("bob");
        UUID orgA = createOrganization(alice, "Org A");
        UUID orgB = createOrganization(bob, "Org B");
        UUID agent = agentWithSecret(alice, orgA, "Atlas");
        grant(alice, orgA, agent, NOTE);
        UUID execution = runningExecution(alice, orgA, agent);
        JsonNode decision = requestTool(orgA, execution, "toolu_x", "diagnostic.record_note", text("secret plan"));
        UUID approval = UUID.fromString(decision.get("approvalId").asString());
        String hash = hashOf(pendingApproval(alice, orgA, approval));

        // A plain member holds neither approval.read nor approval.decide; a read-only role cannot decide.
        TestUser member = newUser("member");
        join(alice, orgA, member, "MEMBER");
        getAs(member, approvals(orgA, "")).andExpect(status().isForbidden());
        postAs(member, approvals(orgA, "/" + approval + "/approve"), body("argumentsHash", hash)).andExpect(status().isForbidden());
        TestUser reader = newUser("reader");
        giveCustomRole(alice, orgA, join(alice, orgA, reader, "MEMBER"), "Approval reader", List.of("approval.read", "agent.read"));
        getAs(reader, approvals(orgA, "/" + approval)).andExpect(status().isOk());
        postAs(reader, approvals(orgA, "/" + approval + "/approve"), body("argumentsHash", hash)).andExpect(status().isForbidden());
        postAs(reader, approvals(orgA, "/" + approval + "/reject"), body("reason", "x")).andExpect(status().isForbidden());
        // Tool timeline needs agent.execution.read.
        getAs(reader, path(orgA, "/executions/" + execution + "/tool-calls")).andExpect(status().isForbidden());

        // Another organization sees and decides nothing, through either path; its list is empty.
        getAs(bob, approvals(orgA, "/" + approval)).andExpect(status().isNotFound());
        postAs(bob, approvals(orgA, "/" + approval + "/approve"), body("argumentsHash", hash)).andExpect(status().isNotFound());
        getAs(bob, approvals(orgB, "/" + approval)).andExpect(status().isNotFound());
        postAs(bob, approvals(orgB, "/" + approval + "/approve"), body("argumentsHash", hash)).andExpect(status().isNotFound());
        getAs(bob, approvals(orgB, "")).andExpect(jsonPath("$.totalElements").value(0));
        getAs(bob, path(orgA, "/executions/" + execution + "/tool-calls")).andExpect(status().isNotFound());
        getAs(bob, path(orgB, "/executions/" + execution + "/tool-calls")).andExpect(status().isNotFound());
        getAs(bob, agents(orgB, "/" + agent + "/available-tools")).andExpect(status().isNotFound());
        // The service account re-validates the pair (organization, execution) too.
        serviceCall(orgB, execution, "/tool-calls", body("toolCallId", "z", "toolCode", "diagnostic.echo", "arguments", text("a"))).andExpect(status().isNotFound());
        serviceCall(orgB, execution, "/resume", Map.of()).andExpect(status().isNotFound());
        getAs(alice, approvals(orgA, "/" + approval)).andExpect(jsonPath("$.status").value("PENDING"));

        // A suspended member can no longer review or decide.
        TestUser leaver = newUser("leaver");
        UUID leaverMembership = join(alice, orgA, leaver, "ADMIN");
        getAs(leaver, approvals(orgA, "")).andExpect(status().isOk());
        postAs(alice, path(orgA, "/members/" + leaverMembership + "/suspend"), "{}").andExpect(status().isOk());
        postAs(leaver, approvals(orgA, "/" + approval + "/approve"), body("argumentsHash", hash)).andExpect(status().isNotFound());
    }

    @Test
    void userTokensAndServiceTokensStayInTheirOwnWorlds() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Worlds");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        grant(owner, org, agent, NOTE);
        UUID execution = runningExecution(owner, org, agent);
        JsonNode decision = requestTool(org, execution, "toolu_w", "diagnostic.record_note", text("x"));
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());
        UUID approval = UUID.fromString(decision.get("approvalId").asString());

        // A user (even the owner) cannot reach the internal tool endpoints.
        postAs(owner, run(org, execution, "/tool-calls"), body("toolCallId", "u", "toolCode", "diagnostic.echo", "arguments", text("a"))).andExpect(status().isForbidden());
        postAs(owner, run(org, execution, "/tool-calls/" + invocation + "/begin"), Map.of()).andExpect(status().isForbidden());
        postAs(owner, run(org, execution, "/resume"), Map.of()).andExpect(status().isForbidden());
        mvc.perform(post(run(org, execution, "/resume"))).andExpect(status().isUnauthorized());
        // The service token cannot decide approvals or read the user API.
        asService(post(approvals(org, "/" + approval + "/approve")).contentType(MediaType.APPLICATION_JSON).content("{\"argumentsHash\":\"" + "0".repeat(64) + "\"}"))
                .andExpect(status().isUnauthorized());
        asService(get(approvals(org, ""))).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void concurrentDecisionsAndConcurrentStartsHaveASingleWinner() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Concurrency");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        grant(owner, org, agent, NOTE);
        UUID execution = runningExecution(owner, org, agent);
        JsonNode decision = requestTool(org, execution, "toolu_c", "diagnostic.record_note", text("race"));
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());
        UUID approval = UUID.fromString(decision.get("approvalId").asString());
        String hash = hashOf(pendingApproval(owner, org, approval));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            // Approve and reject at the same time: exactly one decision is recorded.
            List<Callable<Integer>> decisions = List.of(
                    () -> postAs(owner, approvals(org, "/" + approval + "/approve"), body("argumentsHash", hash)).andReturn().getResponse().getStatus(),
                    () -> postAs(owner, approvals(org, "/" + approval + "/reject"), body("reason", "no")).andReturn().getResponse().getStatus(),
                    () -> postAs(owner, approvals(org, "/" + approval + "/approve"), body("argumentsHash", hash)).andReturn().getResponse().getStatus());
            List<Integer> codes = new java.util.ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(decisions)) {
                codes.add(f.get());
            }
            assertThat(codes.stream().filter(c -> c == 200).count()).isEqualTo(1);
            assertThat(codes.stream().filter(c -> c == 409).count()).isEqualTo(2);
            String finalStatus = read(getAs(owner, approvals(org, "/" + approval))).get("status").asString();
            if (!finalStatus.equals("APPROVED")) {
                return; // rejected: nothing can start, which the dedicated test already covers
            }

            serviceCall(org, execution, "/resume", Map.of()).andExpect(status().isOk());
            // Several begin calls at once: the call starts exactly once.
            List<Callable<Integer>> begins = List.of(
                    () -> serviceCall(org, execution, "/tool-calls/" + invocation + "/begin", Map.of()).andReturn().getResponse().getStatus(),
                    () -> serviceCall(org, execution, "/tool-calls/" + invocation + "/begin", Map.of()).andReturn().getResponse().getStatus(),
                    () -> serviceCall(org, execution, "/tool-calls/" + invocation + "/begin", Map.of()).andReturn().getResponse().getStatus(),
                    () -> serviceCall(org, execution, "/tool-calls/" + invocation + "/begin", Map.of()).andReturn().getResponse().getStatus());
            List<Integer> startCodes = new java.util.ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(begins)) {
                startCodes.add(f.get());
            }
            assertThat(startCodes.stream().filter(c -> c == 200).count()).isEqualTo(1);
            assertThat(startCodes.stream().filter(c -> c == 409).count()).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'agent.tool.started' AND organization_id = ?",
                    Integer.class, org)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
