package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Security/UX fix 8.5 against the REAL Core API and PostgreSQL: the approval history names the person who decided (display name only,
 * resolved among the members of this organization), with a neutral fallback, never an invented name and never data of another organization.
 */
class ApproverIdentityIntegrationTest extends ToolFlows {

    @org.springframework.beans.factory.annotation.Autowired
    private com.aiworkforce.core.support.RecordingOrchestrator.Gateway orchestrator;

    @org.junit.jupiter.api.BeforeEach
    void resetOrchestrator() {
        orchestrator.reset();   // the gateway bean is shared between test classes: start from a reachable orchestrator
    }

    private static final Object[] NOTE_WITH_APPROVAL = {"diagnostic.record_note", "WRITE", true};
    private static final String FALLBACK = "Organization member";

    @Autowired
    private JdbcTemplate jdbc;

    private record Decided(TestUser owner, UUID org, UUID execution, UUID approval, UUID userId) {
    }

    private Decided decidedApproval(String ownerName) throws Exception {
        TestUser owner = newUser(ownerName);
        UUID org = createOrganization(owner, "Approver identity " + UUID.randomUUID());
        UUID agent = agentWithSecret(owner, org, "Atlas");
        grant(owner, org, agent, NOTE_WITH_APPROVAL);
        UUID execution = runningExecution(owner, org, agent);
        JsonNode decision = requestTool(org, execution, "toolu_01", "diagnostic.record_note", Map.of("text", "x"));
        UUID approval = UUID.fromString(decision.get("approvalId").asString());
        String hash = read(getAs(owner, path(org, "/approvals/" + approval)).andExpect(status().isOk())).get("argumentsHash").asString();
        postAs(owner, path(org, "/approvals/" + approval + "/approve"), Map.of("argumentsHash", hash)).andExpect(status().isOk());
        UUID userId = jdbc.queryForObject("SELECT decided_by FROM tool_approvals WHERE id = ?", UUID.class, approval);
        return new Decided(owner, org, execution, approval, userId);
    }

    private JsonNode card(Decided d) throws Exception {
        return read(getAs(d.owner(), path(d.org(), "/approvals/" + d.approval())).andExpect(status().isOk()));
    }

    @Test
    void aPendingApprovalNamesNobodyAndADecidedOneNamesTheMemberWhoDecided() throws Exception {
        TestUser owner = newUser("Ada");
        UUID org = createOrganization(owner, "Pending then decided");
        UUID agent = agentWithSecret(owner, org, "Atlas");
        grant(owner, org, agent, NOTE_WITH_APPROVAL);
        UUID execution = runningExecution(owner, org, agent);
        UUID approval = UUID.fromString(requestTool(org, execution, "toolu_01", "diagnostic.record_note", Map.of("text", "x")).get("approvalId").asString());
        getAs(owner, path(org, "/approvals/" + approval)).andExpect(jsonPath("$.decidedBy").doesNotExist()).andExpect(jsonPath("$.decidedByName").doesNotExist());

        Decided decided = decidedApproval("Grace");
        assertThat(card(decided).get("decidedByName").asString()).isEqualTo("Grace");
        // the same name in the list of approvals and in the execution history
        getAs(decided.owner(), path(decided.org(), "/approvals?status=APPROVED")).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].decidedByName").value("Grace"));
        getAs(decided.owner(), path(decided.org(), "/executions/" + decided.execution() + "/tool-calls")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].approval.decidedByName").value("Grace"));
    }

    @Test
    void aMemberWithoutAUsableDisplayNameGetsTheNeutralFallbackAndNothingIsInvented() throws Exception {
        Decided d = decidedApproval("Anon");
        jdbc.update("UPDATE user_accounts SET display_name = '   ' WHERE id = ?", d.userId());
        String body = getAs(d.owner(), path(d.org(), "/approvals/" + d.approval())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(card(d).get("decidedByName").asString()).isEqualTo(FALLBACK);
        assertThat(body).doesNotContain(d.owner().email()).doesNotContain("Anon");
    }

    @Test
    void aSuspendedMembersPastDecisionKeepsTheirName() throws Exception {
        Decided d = decidedApproval("Hopper");
        jdbc.update("UPDATE memberships SET status = 'SUSPENDED' WHERE organization_id = ? AND user_id = ?", d.org(), d.userId());
        // the decision is history: the name stays (the approval list of a suspended caller is not the subject here, the data is read directly)
        assertThat(jdbc.queryForObject("SELECT status FROM memberships WHERE organization_id = ? AND user_id = ?", String.class, d.org(), d.userId()))
                .isEqualTo("SUSPENDED");
        jdbc.update("UPDATE memberships SET status = 'ACTIVE' WHERE organization_id = ? AND user_id = ?", d.org(), d.userId());
        assertThat(card(d).get("decidedByName").asString()).isEqualTo("Hopper");
    }

    @Test
    void aPersonWhoIsNotAMemberOfThisOrganizationNeverLeaksAForeignName() throws Exception {
        Decided d = decidedApproval("Lovelace");
        // someone who is a member of ANOTHER organization only
        TestUser outsider = newUser("Outsider-Secret-Name");
        UUID otherOrg = createOrganization(outsider, "Other organization");
        UUID outsiderId = jdbc.queryForObject("SELECT user_id FROM memberships WHERE organization_id = ? LIMIT 1", UUID.class, otherOrg);
        jdbc.update("UPDATE tool_approvals SET decided_by = ? WHERE id = ?", outsiderId, d.approval());
        String body = getAs(d.owner(), path(d.org(), "/approvals/" + d.approval())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(card(d).get("decidedByName").asString()).isEqualTo(FALLBACK);
        assertThat(body).doesNotContain("Outsider-Secret-Name");
        // (an id that is no account at all cannot exist: decided_by is a foreign key to user_accounts)
    }

    @Test
    void anotherOrganizationAndAnUnauthorizedCallerCannotReadTheApprovalHistory() throws Exception {
        Decided d = decidedApproval("Turing");
        TestUser stranger = newUser("Stranger");
        UUID strangerOrg = createOrganization(stranger, "Stranger org");
        getAs(stranger, path(d.org(), "/approvals/" + d.approval())).andExpect(status().is4xxClientError());
        getAs(stranger, path(strangerOrg, "/approvals/" + d.approval())).andExpect(status().isNotFound());
        String list = getAs(stranger, path(strangerOrg, "/approvals")).andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("Turing");
    }
}
