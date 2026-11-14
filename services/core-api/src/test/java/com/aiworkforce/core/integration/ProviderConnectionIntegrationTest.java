package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aiworkforce.core.support.FakeProviders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Provider connections: credentials are write-only and encrypted, a saved credential is not a valid
 * connection until tested, adapters use the providers' real authentication scheme against local
 * fake servers, and nothing secret reaches a response, the database in clear, or the logs.
 */
class ProviderConnectionIntegrationTest extends AgentFlows {

    @Autowired
    private JdbcTemplate jdbc;

    private ListAppender<ILoggingEvent> logs;
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        FakeProviders.reset();
        logs = new ListAppender<>();
        logs.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        previousLevel = root.getLevel();
        root.setLevel(Level.DEBUG);
        root.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(logs);
        root.setLevel(previousLevel);
    }

    private void assertNeverLogged(String secret) {
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).noneMatch(m -> m.contains(secret));
    }

    @Test
    void creatingAConnectionStoresTheCredentialEncryptedAndReturnsOnlySafeMetadata() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Secrets");
        String secret = "sk-secret-value-" + UUID.randomUUID();

        String response = postAs(owner, connections(org, ""), body("providerType", "ANTHROPIC", "displayName", "Claude main",
                "accountIdentifier", "acct-42", "credential", secret))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.providerName").value("Anthropic Claude"))
                .andExpect(jsonPath("$.accountIdentifier").value("acct-42"))
                .andExpect(jsonPath("$.lastTestedAt").doesNotExist())
                .andExpect(jsonPath("$.credential").doesNotExist())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID connection = UUID.fromString(JSON.readTree(response).get("id").asString());
        // Saving a credential never validated it: no provider was called.
        assertThat(FakeProviders.received()).isEmpty();

        // Not in any response (get, list)...
        for (String body : List.of(response,
                getAs(owner, connections(org, "/" + connection)).andReturn().getResponse().getContentAsString(),
                getAs(owner, connections(org, "")).andReturn().getResponse().getContentAsString())) {
            assertThat(body).doesNotContain(secret).doesNotContainIgnoringCase("credential");
        }
        // ...not in clear in the database: the ciphertext does not contain it, and no other column does.
        List<byte[]> ciphertexts = jdbc.query("SELECT ciphertext FROM secret_entries WHERE organization_id = ?",
                (rs, row) -> rs.getBytes(1), org);
        assertThat(ciphertexts).hasSize(1);
        assertThat(new String(ciphertexts.getFirst(), StandardCharsets.ISO_8859_1)).doesNotContain(secret);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_connections WHERE organization_id = ? AND "
                + "(display_name || coalesce(account_identifier,'') || coalesce(status_detail,'')) LIKE ?", Long.class,
                org, "%" + secret + "%")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE organization_id = ? AND details LIKE ?",
                Long.class, org, "%" + secret + "%")).isZero();
        assertNeverLogged(secret);
    }

    @Test
    void credentialsAreValidatedAtTheBoundary() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Boundary");
        connect(owner, org, "OPENAI", "Existing", "valid-key-1234");
        String leaky = "short";
        postAs(owner, connections(org, ""), body("providerType", "OPENAI", "displayName", "Short", "credential", leaky))
                .andExpect(status().isBadRequest());
        postAs(owner, connections(org, ""), body("providerType", "OPENAI", "displayName", "Spaces", "credential", "has space inside"))
                .andExpect(status().isBadRequest());
        postAs(owner, connections(org, ""), body("providerType", "OPENAI", "displayName", "Long", "credential", "a".repeat(513)))
                .andExpect(status().isBadRequest());
        postAs(owner, connections(org, ""), body("providerType", "MISTRAL", "displayName", "Unknown", "credential", "valid-key-1234"))
                .andExpect(status().isBadRequest());
        postAs(owner, connections(org, ""), body("providerType", "OPENAI", "displayName", "existing", "credential", "valid-key-1234"))
                .andExpect(status().isConflict());
        postAs(owner, connections(org, ""), body("displayName", "No type", "credential", "valid-key-1234"))
                .andExpect(status().isBadRequest());
        getAs(owner, connections(org, "")).andExpect(jsonPath("$.totalElements").value(1));
        getAs(owner, "/api/v1/providers/types").andExpect(jsonPath("$[*].type", hasItem("GEMINI")));
    }

    @Test
    void eachAdapterAuthenticatesTheWayItsProviderDoesAndOnlyReadsModels() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Adapters");
        for (String type : List.of("ANTHROPIC", "OPENAI", "GEMINI")) {
            UUID connection = connect(owner, org, type, type + " connection", "valid-key-" + type.toLowerCase());
            postAs(owner, connections(org, "/" + connection + "/test"), "{}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("VALID"))
                    .andExpect(jsonPath("$.connection.status").value("VALID"))
                    .andExpect(jsonPath("$.connection.lastTestedAt").isNotEmpty());
        }
        List<FakeProviders.Received> requests = FakeProviders.received();
        FakeProviders.Received anthropic = requests.stream().filter(r -> r.provider().equals("anthropic")).findFirst().orElseThrow();
        FakeProviders.Received openai = requests.stream().filter(r -> r.provider().equals("openai")).findFirst().orElseThrow();
        FakeProviders.Received gemini = requests.stream().filter(r -> r.provider().equals("gemini")).findFirst().orElseThrow();

        assertThat(anthropic.headers()).containsEntry("x-api-key", "valid-key-anthropic").containsKey("anthropic-version");
        assertThat(anthropic.headers()).doesNotContainKey("authorization");
        assertThat(anthropic.path()).isEqualTo("/v1/models?limit=1");
        assertThat(openai.headers()).containsEntry("authorization", "Bearer valid-key-openai");
        assertThat(openai.path()).isEqualTo("/v1/models");
        assertThat(gemini.headers()).containsEntry("x-goog-api-key", "valid-key-gemini");
        // The credential travels in a header, never in the URL (it would end up in logs).
        assertThat(gemini.path()).doesNotContain("valid-key-gemini").doesNotContain("key=");
        assertThat(gemini.path()).isEqualTo("/v1beta/models?pageSize=1");
        // Only model listing: no completion, no billable call.
        assertThat(requests).allSatisfy(r -> assertThat(r.path()).containsAnyOf("/v1/models", "/v1beta/models"));
        assertThat(requests).noneMatch(r -> r.path().contains("messages") || r.path().contains("completions")
                || r.path().contains("generate"));
    }

    @Test
    void rejectedCredentialsBecomeInvalidAndUnreachableProvidersDoNotChangeTheStatus() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Outcomes");
        for (String type : List.of("ANTHROPIC", "OPENAI", "GEMINI")) {
            String secret = "bad-credential-" + type.toLowerCase();
            UUID rejected = connect(owner, org, type, type + " rejected", secret);
            String body = postAs(owner, connections(org, "/" + rejected + "/test"), "{}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("INVALID"))
                    .andExpect(jsonPath("$.connection.status").value("INVALID"))
                    .andReturn().getResponse().getContentAsString();
            // The fake provider echoes the credential in its error body: the API must never relay it.
            assertThat(body).doesNotContain(secret);
            getAs(owner, connections(org, "/" + rejected)).andExpect(jsonPath("$.status").value("INVALID"));
            assertNeverLogged(secret);
        }
        for (String prefix : List.of("down", "rate")) {
            String secret = prefix + "-credential-1234";
            UUID flaky = connect(owner, org, "OPENAI", "Flaky " + prefix, secret);
            String body = postAs(owner, connections(org, "/" + flaky + "/test"), "{}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("UNAVAILABLE"))
                    .andExpect(jsonPath("$.connection.status").value("PENDING"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(secret);
            assertNeverLogged(secret);
        }
    }

    @Test
    void modelsAreDiscoveredFromTheProviderNeverHardcoded() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Models");
        UUID paged = connect(owner, org, "ANTHROPIC", "Paged", "valid-paged");
        getAs(owner, connections(org, "/" + paged + "/models")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value("claude-test-1"))
                .andExpect(jsonPath("$[1].id").value("claude-test-2"));
        assertThat(FakeProviders.received()).anyMatch(r -> r.path().contains("after_id=claude-test-1"));

        UUID gemini = connect(owner, org, "GEMINI", "Gemini", "valid-gemini-key");
        getAs(owner, connections(org, "/" + gemini + "/models")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("gemini-test-1"));

        UUID bad = connect(owner, org, "OPENAI", "Bad", "bad-key-models");
        getAs(owner, connections(org, "/" + bad + "/models")).andExpect(status().isConflict());
        UUID down = connect(owner, org, "OPENAI", "Down", "down-key-models");
        getAs(owner, connections(org, "/" + down + "/models")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void replacingTheCredentialResetsTheConnectionAndDeletesTheOldSecret() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Rotation");
        UUID connection = connect(owner, org, "OPENAI", "Rotating", "valid-key-first");
        postAs(owner, connections(org, "/" + connection + "/test"), "{}").andExpect(jsonPath("$.outcome").value("VALID"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM secret_entries WHERE organization_id = ?", Long.class, org)).isOne();

        String second = "valid-key-second";
        String response = putAs(owner, connections(org, "/" + connection + "/credential"), body("credential", second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.lastTestedAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(second);
        // The old secret is gone; exactly one remains.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM secret_entries WHERE organization_id = ?", Long.class, org)).isOne();
        FakeProviders.reset();
        postAs(owner, connections(org, "/" + connection + "/test"), "{}").andExpect(jsonPath("$.outcome").value("VALID"));
        assertThat(FakeProviders.received().getFirst().headers()).containsEntry("authorization", "Bearer " + second);
        putAs(owner, connections(org, "/" + connection + "/credential"), body("credential", "x"))
                .andExpect(status().isBadRequest());
        getAs(owner, path(org, "/audit-events?size=100")).andExpect(jsonPath("$.content[*].action", hasItem("provider.credential.replaced")));
        String auditTrail = getAs(owner, path(org, "/audit-events?size=100")).andReturn().getResponse().getContentAsString();
        assertThat(auditTrail).doesNotContain(second).doesNotContain("valid-key-first");
        assertNeverLogged(second);
        assertNeverLogged("valid-key-first");
    }

    @Test
    void revokingDeletesTheCredentialForGoodAndIsRefusedWhileAnActiveAgentDependsOnIt() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Revocation");
        UUID connection = validConnection(owner, org, "Primary provider");
        UUID agent = agent(owner, org, "Dependent");
        bind(owner, org, agent, connection, "claude-test-1", "PRIMARY");
        postAs(owner, agents(org, "/" + agent + "/activate"), "{}").andExpect(status().isOk());

        deleteAs(owner, connections(org, "/" + connection)).andExpect(status().isConflict());
        postAs(owner, agents(org, "/" + agent + "/pause"), "{}").andExpect(status().isOk());
        deleteAs(owner, connections(org, "/" + connection)).andExpect(status().isNoContent());

        getAs(owner, connections(org, "/" + connection)).andExpect(jsonPath("$.status").value("REVOKED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM secret_entries WHERE organization_id = ?", Long.class, org)).isZero();
        assertThat(jdbc.queryForObject("SELECT credential_ref IS NULL FROM provider_connections WHERE id = ?", Boolean.class,
                connection)).isTrue();
        // A revoked connection is terminal.
        postAs(owner, connections(org, "/" + connection + "/test"), "{}").andExpect(status().isConflict());
        getAs(owner, connections(org, "/" + connection + "/models")).andExpect(status().isConflict());
        putAs(owner, connections(org, "/" + connection + "/credential"), body("credential", "valid-key-again")).andExpect(status().isConflict());
        patchAs(owner, connections(org, "/" + connection), body("displayName", "Renamed")).andExpect(status().isConflict());
        deleteAs(owner, connections(org, "/" + connection)).andExpect(status().isConflict());
        UUID second = agent(owner, org, "Second");
        postAs(owner, agents(org, "/" + second + "/providers"),
                body("connectionId", connection, "modelId", "claude-test-1", "role", "PRIMARY")).andExpect(status().isConflict());
    }

    @Test
    void connectionsBelongToTheirOrganizationOnly() throws Exception {
        TestUser alice = newUser("alice");
        TestUser bob = newUser("bob");
        UUID orgA = createOrganization(alice, "Org A");
        UUID orgB = createOrganization(bob, "Org B");
        UUID connectionB = validConnection(bob, orgB, "Bob provider");
        UUID agentA = agent(alice, orgA, "Alice agent");

        getAs(alice, connections(orgB, "")).andExpect(status().isNotFound());
        getAs(alice, connections(orgA, "/" + connectionB)).andExpect(status().isNotFound());
        postAs(alice, connections(orgA, "/" + connectionB + "/test"), "{}").andExpect(status().isNotFound());
        getAs(alice, connections(orgA, "/" + connectionB + "/models")).andExpect(status().isNotFound());
        putAs(alice, connections(orgA, "/" + connectionB + "/credential"), body("credential", "valid-stolen-key")).andExpect(status().isNotFound());
        patchAs(alice, connections(orgA, "/" + connectionB), body("displayName", "Pwned")).andExpect(status().isNotFound());
        deleteAs(alice, connections(orgA, "/" + connectionB)).andExpect(status().isNotFound());
        postAs(alice, connections(orgB, "/" + connectionB + "/test"), "{}").andExpect(status().isNotFound());
        // An agent cannot be bound to another organization's provider connection.
        postAs(alice, agents(orgA, "/" + agentA + "/providers"),
                body("connectionId", connectionB, "modelId", "claude-test-1", "role", "PRIMARY")).andExpect(status().isNotFound());
        getAs(alice, connections(orgA, "")).andExpect(jsonPath("$.totalElements").value(0));
        getAs(bob, connections(orgB, "/" + connectionB)).andExpect(jsonPath("$.status").value("VALID"));
    }

    @Test
    void providerPermissionsAreSeparate() throws Exception {
        TestUser owner = newUser("owner");
        TestUser member = newUser("member");
        TestUser connector = newUser("connector");
        TestUser tester = newUser("tester");
        UUID org = createOrganization(owner, "Provider rights");
        UUID plainMember = join(owner, org, member, "MEMBER");
        UUID connectorMembership = join(owner, org, connector, "MEMBER");
        UUID testerMembership = join(owner, org, tester, "MEMBER");
        UUID connection = connect(owner, org, "OPENAI", "Shared", "valid-key-shared");
        giveCustomRole(owner, org, connectorMembership, "Connector", List.of("provider.connect"));
        giveCustomRole(owner, org, testerMembership, "Tester", List.of("provider.read", "provider.test"));
        assertThat(plainMember).isNotNull();

        // A plain member does not even see providers.
        getAs(member, connections(org, "")).andExpect(status().isForbidden());
        postAs(member, connections(org, ""), body("providerType", "OPENAI", "displayName", "Nope", "credential", "valid-key-nope"))
                .andExpect(status().isForbidden());
        // provider.connect creates but cannot read, test, edit or revoke.
        connect(connector, org, "OPENAI", "By connector", "valid-key-connector");
        getAs(connector, connections(org, "")).andExpect(status().isForbidden());
        postAs(connector, connections(org, "/" + connection + "/test"), "{}").andExpect(status().isForbidden());
        patchAs(connector, connections(org, "/" + connection), body("displayName", "Edit")).andExpect(status().isForbidden());
        deleteAs(connector, connections(org, "/" + connection)).andExpect(status().isForbidden());
        // provider.read + provider.test reads and tests but cannot create, replace or revoke.
        getAs(tester, connections(org, "")).andExpect(status().isOk());
        postAs(tester, connections(org, "/" + connection + "/test"), "{}").andExpect(status().isOk());
        getAs(tester, connections(org, "/" + connection + "/models")).andExpect(status().isOk());
        postAs(tester, connections(org, ""), body("providerType", "OPENAI", "displayName", "Nope", "credential", "valid-key-nope"))
                .andExpect(status().isForbidden());
        putAs(tester, connections(org, "/" + connection + "/credential"), body("credential", "valid-key-nope2")).andExpect(status().isForbidden());
        deleteAs(tester, connections(org, "/" + connection)).andExpect(status().isForbidden());
        getAs(owner, connections(org, "/" + connection)).andExpect(status().isOk());
    }

    @Test
    void auditRecordsConnectionChangesWithoutSecrets() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Provider audit");
        UUID connection = connect(owner, org, "GEMINI", "Audited", "valid-key-audited");
        postAs(owner, connections(org, "/" + connection + "/test"), "{}").andExpect(status().isOk());
        patchAs(owner, connections(org, "/" + connection), body("displayName", "Audited 2")).andExpect(status().isOk());
        deleteAs(owner, connections(org, "/" + connection)).andExpect(status().isNoContent());

        JsonNode events = read(getAs(owner, path(org, "/audit-events?size=100")).andExpect(status().isOk()));
        List<String> actions = events.get("content").valueStream().map(e -> e.get("action").asString()).toList();
        assertThat(actions).contains("provider.connection.created", "provider.connection.tested",
                "provider.connection.updated", "provider.connection.revoked");
        assertThat(events.toString()).doesNotContain("valid-key-audited");
    }
}
