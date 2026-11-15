package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.agent.ToolResultProbe;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Security fix 8.1 against the REAL Core API and PostgreSQL: tool results are encrypted at rest, read back only after authorization,
 * bound to their organization/execution/invocation, and legacy plaintext rows stay readable and are converted by the verified backfill.
 */
@Import(ToolResultProbe.class)
@ExtendWith(OutputCaptureExtension.class)
class ToolResultEncryptionIntegrationTest extends ToolFlows {

    @org.springframework.beans.factory.annotation.Autowired
    private com.aiworkforce.core.support.RecordingOrchestrator.Gateway orchestrator;

    @org.junit.jupiter.api.BeforeEach
    void resetOrchestrator() {
        orchestrator.reset();   // the gateway bean is shared between test classes: start from a reachable orchestrator
    }

    private static final Object[] ECHO = {"diagnostic.echo", "READ", false};
    private static final String CONFIDENTIAL = "CONFIDENTIAL-RESULT-98765 account=4111-1111 é漢";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ToolResultProbe.Probe probe;

    private record Call(UUID org, UUID execution, UUID invocation, TestUser owner) {
    }

    /** A completed tool call whose result is {@code output}. */
    private Call completedCall(String orgName, String output) throws Exception {
        TestUser owner = newUser("owner-" + UUID.randomUUID());
        UUID org = createOrganization(owner, orgName);
        UUID agent = agentWithSecret(owner, org, "Atlas");
        grant(owner, org, agent, ECHO);
        UUID execution = runningExecution(owner, org, agent);
        return complete(owner, org, execution, "toolu_01", output);
    }

    private Call complete(TestUser owner, UUID org, UUID execution, String callId, String output) throws Exception {
        JsonNode decision = requestTool(org, execution, callId, "diagnostic.echo", Map.of("text", "x"));
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());
        serviceCall(org, execution, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isOk());
        serviceCall(org, execution, "/tool-calls/" + invocation + "/complete", body("success", true, "output", output)).andExpect(status().isOk());
        return new Call(org, execution, invocation, owner);
    }

    private String timeline(Call c) throws Exception {
        return getAs(c.owner(), path(c.org(), "/executions/" + c.execution() + "/tool-calls")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String column(String sql, UUID id) {
        return jdbc.queryForObject(sql, String.class, id);
    }

    @Test
    void aNewResultIsStoredOnlyAsAnEnvelopeAndNoRowContainsItsPlaintext(CapturedOutput output) throws Exception {
        Call c = completedCall("Encrypted result", CONFIDENTIAL);
        assertThat(jdbc.queryForObject("SELECT result IS NULL FROM tool_invocations WHERE id = ?", Boolean.class, c.invocation())).isTrue();
        byte[] envelope = jdbc.queryForObject("SELECT result_envelope FROM tool_invocations WHERE id = ?", byte[].class, c.invocation());
        assertThat(envelope).isNotNull();
        assertThat(envelope[0]).isEqualTo((byte) 1);                                                      // versioned envelope
        assertThat(new String(envelope, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("CONFIDENTIAL");
        for (String row : jdbc.queryForList("SELECT row_to_json(i)::text FROM tool_invocations i WHERE organization_id = ?", String.class, c.org())) {
            assertThat(row).doesNotContain("CONFIDENTIAL-RESULT").doesNotContain("4111-1111");
        }
        for (String details : jdbc.queryForList("SELECT details::text FROM audit_events WHERE organization_id = ?", String.class, c.org())) {
            assertThat(details).doesNotContain("CONFIDENTIAL-RESULT");
        }
        assertThat(output.getAll()).doesNotContain("CONFIDENTIAL-RESULT");                               // not in any application log either
    }

    @Test
    void theAuthorizedReaderGetsTheDecryptedResultAndNoCiphertextEverReachesTheApi() throws Exception {
        Call c = completedCall("Authorized read", CONFIDENTIAL);
        String body = timeline(c);
        assertThat(body).contains("CONFIDENTIAL-RESULT-98765").doesNotContain("result_envelope").doesNotContain("resultEnvelope");
        getAs(c.owner(), path(c.org(), "/executions/" + c.execution() + "/tool-calls")).andExpect(jsonPath("$.content[0].result").value(CONFIDENTIAL));
    }

    @Test
    void anotherOrganizationCannotReadTheResultThroughAnyPath() throws Exception {
        Call c = completedCall("Owner org", CONFIDENTIAL);
        TestUser stranger = newUser("stranger-" + UUID.randomUUID());
        UUID strangerOrg = createOrganization(stranger, "Stranger org");
        for (UUID org : new UUID[] {c.org(), strangerOrg}) {
            String response = getAs(stranger, path(org, "/executions/" + c.execution() + "/tool-calls")).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("CONFIDENTIAL");
        }
        getAs(stranger, path(c.org(), "/executions/" + c.execution() + "/tool-calls")).andExpect(status().is4xxClientError());
        getAs(stranger, path(strangerOrg, "/executions/" + c.execution() + "/tool-calls")).andExpect(status().isNotFound());
    }

    @Test
    void aCiphertextCopiedToAnotherInvocationOrExecutionIsNotReadable() throws Exception {
        Call a = completedCall("Relocation A", CONFIDENTIAL);
        UUID agentB = agentWithSecret(a.owner(), a.org(), "Second");
        grant(a.owner(), a.org(), agentB, ECHO);
        UUID executionB = runningExecution(a.owner(), a.org(), agentB);
        Call b = complete(a.owner(), a.org(), executionB, "toolu_02", "B-RESULT-INNOCENT");
        // an attacker with database write access copies A's envelope onto B's invocation
        jdbc.update("UPDATE tool_invocations SET result_envelope = (SELECT result_envelope FROM tool_invocations WHERE id = ?) WHERE id = ?", a.invocation(), b.invocation());
        getAs(b.owner(), path(b.org(), "/executions/" + b.execution() + "/tool-calls")).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].result").doesNotExist());
        assertThat(timeline(b)).doesNotContain("CONFIDENTIAL").doesNotContain("B-RESULT-INNOCENT");     // fails closed: neither text
        assertThat(timeline(a)).contains("CONFIDENTIAL-RESULT-98765");                                      // the original is unaffected
    }

    @Test
    void aTamperedEnvelopeYieldsNoResultAndLeaksNothing(CapturedOutput output) throws Exception {
        Call c = completedCall("Tamper", CONFIDENTIAL);
        jdbc.update("UPDATE tool_invocations SET result_envelope = set_byte(result_envelope, 20, get_byte(result_envelope, 20) # 1) WHERE id = ?", c.invocation());
        String body = timeline(c);
        assertThat(body).doesNotContain("CONFIDENTIAL").doesNotContain("Exception");
        getAs(c.owner(), path(c.org(), "/executions/" + c.execution() + "/tool-calls")).andExpect(jsonPath("$.content[0].result").doesNotExist());
        assertThat(output.getAll()).contains("could not be authenticated").doesNotContain("CONFIDENTIAL");
    }

    @Test
    void aRowCanNeverHoldBothAPlaintextAndAnEncryptedResult() throws Exception {
        Call c = completedCall("One form only", CONFIDENTIAL);
        assertThatThrownBy(() -> jdbc.update("UPDATE tool_invocations SET result = 'plaintext copy' WHERE id = ?", c.invocation()))
                .hasMessageContaining("ck_tool_invocations_one_result_form");
    }

    @Test
    void legacyPlaintextRowsStayReadableAlongsideEncryptedOnesAndTheVerifiedBackfillConvertsThem() throws Exception {
        Call encrypted = completedCall("Mixed", CONFIDENTIAL);
        UUID agent2 = agentWithSecret(encrypted.owner(), encrypted.org(), "Legacy agent");
        grant(encrypted.owner(), encrypted.org(), agent2, ECHO);
        UUID legacyExecution = runningExecution(encrypted.owner(), encrypted.org(), agent2);
        Call legacy = complete(encrypted.owner(), encrypted.org(), legacyExecution, "toolu_03", "placeholder");
        // turn it into what a pre-V11 deployment stored: plaintext, no envelope
        jdbc.update("UPDATE tool_invocations SET result = 'LEGACY-PLAINTEXT-RESULT é', result_envelope = NULL WHERE id = ?", legacy.invocation());

        // during the transition both forms are served
        assertThat(timeline(legacy)).contains("LEGACY-PLAINTEXT-RESULT");
        assertThat(timeline(encrypted)).contains("CONFIDENTIAL-RESULT-98765");

        int[] first = probe.backfill();
        assertThat(first[0]).isGreaterThanOrEqualTo(1);
        assertThat(first[1]).isZero();
        assertThat(jdbc.queryForObject("SELECT result IS NULL AND result_envelope IS NOT NULL FROM tool_invocations WHERE id = ?", Boolean.class, legacy.invocation())).isTrue();
        for (String row : jdbc.queryForList("SELECT row_to_json(i)::text FROM tool_invocations i WHERE organization_id = ?", String.class, legacy.org())) {
            assertThat(row).doesNotContain("LEGACY-PLAINTEXT-RESULT");                                  // no second readable copy remains
        }
        assertThat(timeline(legacy)).contains("LEGACY-PLAINTEXT-RESULT");                               // same text, now decrypted
        getAs(legacy.owner(), path(legacy.org(), "/executions/" + legacy.execution() + "/tool-calls"))
                .andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.content[0].result").value("LEGACY-PLAINTEXT-RESULT é"));
        int[] second = probe.backfill();                                                                // idempotent and resumable
        assertThat(second[0]).isZero();
    }

    @Test
    void aLegacyRowIsConvertedByTheBackfillAndStaysReadable() throws Exception {
        Call legacy = completedCall("Backfill edge", "x");
        jdbc.update("UPDATE tool_invocations SET result = 'LEGACY-ONE', result_envelope = NULL WHERE id = ?", legacy.invocation());
        int[] counts = probe.backfill();
        assertThat(counts[1]).isZero();
        assertThat(column("SELECT result FROM tool_invocations WHERE id = ?", legacy.invocation())).isNull();
        assertThat(timeline(legacy)).contains("LEGACY-ONE");
    }

    @Test
    void aResultSurvivesTheEnvelopeBeingRebuiltByAFreshProtectorFromTheSameKey() throws Exception {
        Call c = completedCall("Restart", CONFIDENTIAL);
        byte[] envelope = jdbc.queryForObject("SELECT result_envelope FROM tool_invocations WHERE id = ?", byte[].class, c.invocation());
        // the probe is the same bean as production; opening a stored envelope needs nothing but the key and the identifiers
        assertThat(probe.open(c.org(), c.execution(), c.invocation(), envelope)).isEqualTo(CONFIDENTIAL);
        assertThatThrownBy(() -> probe.open(UUID.randomUUID(), c.execution(), c.invocation(), envelope)).isInstanceOf(IllegalStateException.class);
    }
}
