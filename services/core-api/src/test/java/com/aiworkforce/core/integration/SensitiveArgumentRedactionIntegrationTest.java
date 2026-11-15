package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Security fix 8.2 against the REAL Core API and PostgreSQL: what people see (approval card, history, persisted display column, audit,
 * logs) is redacted at any depth; what is executed and what the approval is bound to is the ORIGINAL arguments, untouched.
 */
@ExtendWith(OutputCaptureExtension.class)
class SensitiveArgumentRedactionIntegrationTest extends ToolFlows {

    @org.springframework.beans.factory.annotation.Autowired
    private com.aiworkforce.core.support.RecordingOrchestrator.Gateway orchestrator;

    @org.junit.jupiter.api.BeforeEach
    void resetOrchestrator() {
        orchestrator.reset();   // the gateway bean is shared between test classes: start from a reachable orchestrator
    }

    private static final Object[] NOTE_WITH_APPROVAL = {"diagnostic.record_note", "WRITE", true};
    private static final String PEM = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7zzPEMBODY\n-----END PRIVATE KEY-----";
    private static final String[] SECRETS = {"S3cretDbPass", "abcdefghijklmnop1234", "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7zzPEMBODY",
            "tok123456789", "Sup3rApiKeyValue", "ghp_abcdefghijklmnopqrstuvwxyz0123456789"};

    @Autowired
    private JdbcTemplate jdbc;

    private Map<String, Object> sensitiveArguments(String apiKey) {
        return Map.of("text", "deploy the release",
                "config", Map.of(
                        "db", "postgresql://admin:S3cretDbPass@db.internal/app",
                        "headers", List.of(Map.of("Authorization", "Bearer abcdefghijklmnop1234", "X-Trace", "t-1")),
                        "pem", PEM,
                        "link", "https://x.example/f?access_token=tok123456789&q=1",
                        "api_key", apiKey,
                        "note", "a github token ghp_abcdefghijklmnopqrstuvwxyz0123456789 pasted by mistake"));
    }

    @Test
    void whatPeopleSeeIsRedactedAtAnyDepthWhileTheExecutedArgumentsAndTheApprovalHashStayOriginal(CapturedOutput output) throws Exception {
        TestUser owner = newUser("owner-" + UUID.randomUUID());
        UUID org = createOrganization(owner, "Redaction");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        grant(owner, org, agent, NOTE_WITH_APPROVAL);
        UUID execution = runningExecution(owner, org, agent);

        JsonNode decision = requestTool(org, execution, "toolu_01", "diagnostic.record_note", sensitiveArguments("Sup3rApiKeyValue"));
        assertThat(decision.get("decision").asString()).isEqualTo("APPROVAL_REQUIRED");
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());
        UUID approval = UUID.fromString(decision.get("approvalId").asString());

        // 1. the approval card: nested secrets, array elements, PEM, URL credentials and query secrets are hidden; the context is kept
        String card = getAs(owner, path(org, "/approvals/" + approval)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (String secret : SECRETS) {
            assertThat(card).doesNotContain(secret);
        }
        assertThat(card).contains("db.internal/app").contains("X-Trace").contains("deploy the release").contains("[REDACTED]");
        String hash = read(getAs(owner, path(org, "/approvals/" + approval)).andExpect(status().isOk())).get("argumentsHash").asString();
        assertThat(hash).matches("[0-9a-f]{64}");

        // 2. the list of approvals and the execution history
        String list = getAs(owner, path(org, "/approvals?status=PENDING")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String history = getAs(owner, path(org, "/executions/" + execution + "/tool-calls")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (String shown : new String[] {list, history}) {
            for (String secret : SECRETS) {
                assertThat(shown).doesNotContain(secret);
            }
        }

        // 3. the persisted display column, the audit trail and the application log
        for (String row : jdbc.queryForList("SELECT row_to_json(i)::text FROM tool_invocations i WHERE organization_id = ?", String.class, org)) {
            for (String secret : SECRETS) {
                assertThat(row).doesNotContain(secret);
            }
        }
        for (String details : jdbc.queryForList("SELECT details::text FROM audit_events WHERE organization_id = ?", String.class, org)) {
            for (String secret : SECRETS) {
                assertThat(details).doesNotContain(secret);
            }
        }
        for (String secret : SECRETS) {
            assertThat(output.getAll()).doesNotContain(secret);
        }

        // 4. the approval is bound to the ORIGINAL arguments, and what the service executes is the ORIGINAL, unredacted arguments
        postAs(owner, path(org, "/approvals/" + approval + "/approve"), Map.of("argumentsHash", hash)).andExpect(status().isOk());
        serviceCall(org, execution, "/resume", Map.of()).andExpect(status().isOk());                 // the orchestrator resumes the paused run
        String begun = serviceCall(org, execution, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (String secret : SECRETS) {
            assertThat(begun).contains(secret);                                                   // the MCP server receives the real values
        }
        assertThat(begun).doesNotContain("[REDACTED]");
    }

    @Test
    void theHashCoversTheRealArgumentsEvenThoughTheDisplayHidesThem() throws Exception {
        TestUser owner = newUser("owner-" + UUID.randomUUID());
        UUID org = createOrganization(owner, "Hash binding");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        grant(owner, org, agent, NOTE_WITH_APPROVAL);
        UUID execA = runningExecution(owner, org, agent);
        UUID execB = runningExecution(owner, org, agent);
        JsonNode a = requestTool(org, execA, "toolu_a", "diagnostic.record_note", sensitiveArguments("Sup3rApiKeyValue"));
        JsonNode b = requestTool(org, execB, "toolu_b", "diagnostic.record_note", sensitiveArguments("An0therApiKeyValue"));
        JsonNode cardA = read(getAs(owner, path(org, "/approvals/" + a.get("approvalId").asString())).andExpect(status().isOk()));
        JsonNode cardB = read(getAs(owner, path(org, "/approvals/" + b.get("approvalId").asString())).andExpect(status().isOk()));
        assertThat(cardA.get("argumentsRedacted").asString()).isEqualTo(cardB.get("argumentsRedacted").asString());   // same display...
        assertThat(cardA.get("argumentsHash").asString()).isNotEqualTo(cardB.get("argumentsHash").asString());       // ...different real arguments
        // a hash computed from anything else cannot approve the call
        postAs(owner, path(org, "/approvals/" + a.get("approvalId").asString() + "/approve"), Map.of("argumentsHash", "0".repeat(64)))
                .andExpect(status().isConflict());
    }

    @Test
    void aToolResultAndTheFinalAnswerAreRedactedWhenShownButStoredAndSentUnchanged() throws Exception {
        TestUser owner = newUser("owner-" + UUID.randomUUID());
        UUID org = createOrganization(owner, "Result display");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        grant(owner, org, agent, new Object[] {"diagnostic.echo", "READ", false});
        UUID execution = runningExecution(owner, org, agent);
        JsonNode decision = requestTool(org, execution, "toolu_r", "diagnostic.echo", Map.of("text", "x"));
        UUID invocation = UUID.fromString(decision.get("invocationId").asString());
        serviceCall(org, execution, "/tool-calls/" + invocation + "/begin", Map.of()).andExpect(status().isOk());
        serviceCall(org, execution, "/tool-calls/" + invocation + "/complete",
                body("success", true, "output", "connection postgresql://svc:Sup3rS3cret@db/x done")).andExpect(status().isOk());
        getAs(owner, path(org, "/executions/" + execution + "/tool-calls")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].result").value("connection postgresql://[REDACTED]@db/x done"));
        serviceCall(org, execution, "/complete", body("result", "The token is Bearer abcdefghijklmnop1234 ok")).andExpect(status().isOk());
        getAs(owner, path(org, "/executions/" + execution)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("The token is [REDACTED] ok"));
    }
}
