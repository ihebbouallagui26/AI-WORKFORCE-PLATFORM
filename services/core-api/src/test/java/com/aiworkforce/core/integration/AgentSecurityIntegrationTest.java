package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Authorization (RBAC, custom roles), tenant isolation, schema constraints and concurrency of the agent domain. */
class AgentSecurityIntegrationTest extends AgentFlows {

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------ RBAC

    @Test
    void aPlainMemberReadsAgentsButChangesNothingAndNeverSeesInstructionsOrProviders() throws Exception {
        TestUser owner = newUser("owner");
        TestUser member = newUser("member");
        UUID org = createOrganization(owner, "Member rights");
        join(owner, org, member, "MEMBER");
        UUID agent = agent(owner, org, body("name", "Readable", "systemInstructions", "Hidden prompt"));
        UUID skill = skill(owner, org, "Reading", "Misc");

        getAs(member, agents(org, "")).andExpect(status().isOk());
        getAs(member, agents(org, "/" + agent)).andExpect(status().isOk()).andExpect(jsonPath("$.agent.name").value("Readable"))
                .andExpect(jsonPath("$.systemInstructions").doesNotExist());
        getAs(owner, agents(org, "/" + agent)).andExpect(jsonPath("$.systemInstructions").value("Hidden prompt"));
        getAs(member, path(org, "/skills")).andExpect(status().isOk());
        getAs(member, path(org, "/tools")).andExpect(status().isOk());
        getAs(member, agents(org, "/" + agent + "/history")).andExpect(status().isOk());
        getAs(member, connections(org, "")).andExpect(status().isForbidden());

        postAs(member, agents(org, ""), body("name", "Nope")).andExpect(status().isForbidden());
        patchAs(member, agents(org, "/" + agent), body("title", "Nope")).andExpect(status().isForbidden());
        putAs(member, agents(org, "/" + agent + "/department"), body("departmentId", null)).andExpect(status().isForbidden());
        postAs(member, agents(org, "/" + agent + "/activate"), "{}").andExpect(status().isForbidden());
        postAs(member, agents(org, "/" + agent + "/archive"), "{}").andExpect(status().isForbidden());
        putAs(member, agents(org, "/" + agent + "/skills"), body("skillIds", List.of(skill))).andExpect(status().isForbidden());
        putAs(member, agents(org, "/" + agent + "/tools"), body("grants", List.of())).andExpect(status().isForbidden());
        putAs(member, agents(org, "/" + agent + "/memory-config"), body("enabled", true, "scope", "PROJECT", "accessMode", "AGENT_ONLY"))
                .andExpect(status().isForbidden());
        postAs(member, agents(org, "/" + agent + "/providers"), body("connectionId", UUID.randomUUID(), "modelId", "m-1", "role", "PRIMARY"))
                .andExpect(status().isForbidden());
        postAs(member, path(org, "/skills"), body("name", "Nope skill")).andExpect(status().isForbidden());
        deleteAs(member, path(org, "/skills/" + skill)).andExpect(status().isForbidden());
    }

    @Test
    void everyAgentCapabilityIsItsOwnPermission() throws Exception {
        TestUser owner = newUser("owner");
        TestUser creator = newUser("creator");
        TestUser manager = newUser("manager");
        TestUser activator = newUser("activator");
        TestUser archiver = newUser("archiver");
        TestUser toolAdmin = newUser("tooladmin");
        UUID org = createOrganization(owner, "Granular rights");
        giveCustomRole(owner, org, join(owner, org, creator, "MEMBER"), "Creator", List.of("agent.read", "agent.create"));
        giveCustomRole(owner, org, join(owner, org, manager, "MEMBER"), "Manager",
                List.of("agent.read", "agent.manage", "skill.read", "provider.read"));
        giveCustomRole(owner, org, join(owner, org, activator, "MEMBER"), "Activator", List.of("agent.read", "agent.activate"));
        giveCustomRole(owner, org, join(owner, org, archiver, "MEMBER"), "Archiver", List.of("agent.read", "agent.archive"));
        giveCustomRole(owner, org, join(owner, org, toolAdmin, "MEMBER"), "Tool admin", List.of("agent.read", "agent.tool.manage"));
        UUID readyAgent = activeAgent(owner, org, "Ready");
        postAs(owner, agents(org, "/" + readyAgent + "/pause"), "{}").andExpect(status().isOk());
        UUID connection = validConnection(owner, org, "Shared connection");
        UUID skill = skill(owner, org, "Shared skill", "Misc");

        // agent.create: creates drafts, nothing else.
        UUID created = agent(creator, org, "Made by creator");
        patchAs(creator, agents(org, "/" + created), body("title", "Nope")).andExpect(status().isForbidden());
        postAs(creator, agents(org, "/" + created + "/activate"), "{}").andExpect(status().isForbidden());
        getAs(creator, agents(org, "/" + created)).andExpect(jsonPath("$.systemInstructions").doesNotExist());

        // agent.manage (+ provider.read, skill.read): edits and configures, but cannot activate, archive or grant tools.
        patchAs(manager, agents(org, "/" + created), body("title", "Managed")).andExpect(status().isOk());
        bind(manager, org, created, connection, "claude-test-1", "PRIMARY");
        putAs(manager, agents(org, "/" + created + "/skills"), body("skillIds", List.of(skill))).andExpect(status().isOk());
        getAs(manager, agents(org, "/" + created)).andExpect(jsonPath("$.systemInstructions").exists());
        postAs(manager, agents(org, "/" + created + "/activate"), "{}").andExpect(status().isForbidden());
        postAs(manager, agents(org, "/" + created + "/archive"), "{}").andExpect(status().isForbidden());
        putAs(manager, agents(org, "/" + created + "/tools"), body("grants", List.of(
                body("toolCode", "code.analyze", "level", "READ", "approvalRequired", true)))).andExpect(status().isForbidden());
        postAs(manager, path(org, "/skills"), body("name", "Nope")).andExpect(status().isForbidden());

        // agent.activate: lifecycle only; cannot edit, and cannot archive.
        postAs(activator, agents(org, "/" + created + "/activate"), "{}").andExpect(status().isOk());
        postAs(activator, agents(org, "/" + created + "/pause"), "{}").andExpect(status().isOk());
        patchAs(activator, agents(org, "/" + created), body("title", "Nope")).andExpect(status().isForbidden());
        postAs(activator, agents(org, "/" + created + "/archive"), "{}").andExpect(status().isForbidden());

        // agent.tool.manage: grants only; cannot edit the agent.
        putAs(toolAdmin, agents(org, "/" + created + "/tools"), body("grants", List.of(
                body("toolCode", "code.analyze", "level", "READ", "approvalRequired", true)))).andExpect(status().isOk());
        patchAs(toolAdmin, agents(org, "/" + created), body("title", "Nope")).andExpect(status().isForbidden());

        // agent.archive: archives only.
        postAs(archiver, agents(org, "/" + created + "/archive"), "{}").andExpect(status().isOk());
        postAs(archiver, agents(org, "/" + readyAgent + "/activate"), "{}").andExpect(status().isForbidden());
    }

    @Test
    void usingAProviderRequiresBeingAllowedToSeeProviders() throws Exception {
        TestUser owner = newUser("owner");
        TestUser blind = newUser("blind");
        UUID org = createOrganization(owner, "Provider visibility");
        giveCustomRole(owner, org, join(owner, org, blind, "MEMBER"), "Manager without provider.read",
                List.of("agent.read", "agent.manage", "skill.read"));
        UUID agent = agent(owner, org, "Target");
        UUID connection = validConnection(owner, org, "Hidden connection");
        postAs(blind, agents(org, "/" + agent + "/providers"), body("connectionId", connection, "modelId", "claude-test-1", "role", "PRIMARY"))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentsInTeamsNeedBothTeamManagementAndAgentVisibility() throws Exception {
        TestUser owner = newUser("owner");
        TestUser teamLead = newUser("teamlead");
        TestUser agentReader = newUser("agentreader");
        UUID org = createOrganization(owner, "Team rights");
        UUID squad = team(owner, org, "Squad", null, null);
        UUID agent = agent(owner, org, "Squad bot");
        giveCustomRole(owner, org, join(owner, org, teamLead, "MEMBER"), "Team lead", List.of("team.read", "team.manage"));
        giveCustomRole(owner, org, join(owner, org, agentReader, "MEMBER"), "Agent reader", List.of("agent.read", "team.read"));

        postAs(teamLead, path(org, "/teams/" + squad + "/agents"), body("agentId", agent)).andExpect(status().isForbidden());
        getAs(teamLead, path(org, "/teams/" + squad + "/agents")).andExpect(status().isForbidden());
        postAs(agentReader, path(org, "/teams/" + squad + "/agents"), body("agentId", agent)).andExpect(status().isForbidden());
        addAgentToTeam(owner, org, squad, agent);
        getAs(agentReader, path(org, "/teams/" + squad + "/agents")).andExpect(status().isOk());
        deleteAs(agentReader, path(org, "/teams/" + squad + "/agents/" + agent)).andExpect(status().isForbidden());
        deleteAs(teamLead, path(org, "/teams/" + squad + "/agents/" + agent)).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------ tenant isolation

    @Test
    void aMemberOfOrganizationACannotReachTheAgentsOfOrganizationB() throws Exception {
        TestUser alice = newUser("alice");
        TestUser bob = newUser("bob");
        UUID orgA = createOrganization(alice, "Org A");
        UUID orgB = createOrganization(bob, "Org B");
        UUID agentB = activeAgent(bob, orgB, "Bob agent");
        UUID skillB = skill(bob, orgB, "Bob skill", "Misc");
        UUID deptB = department(bob, orgB, "Bob dept", "BD", null);
        UUID teamB = team(bob, orgB, "Bob team", null, null);
        UUID agentA = agent(alice, orgA, "Alice agent");
        UUID teamA = team(alice, orgA, "Alice team", null, null);

        // Collections and items of B are not found, through B's path or through A's.
        for (String path : List.of(agents(orgB, ""), path(orgB, "/skills"), path(orgB, "/tools"), connections(orgB, ""),
                agents(orgB, "/" + agentB), agents(orgB, "/" + agentB + "/history"), agents(orgB, "/" + agentB + "/providers"),
                agents(orgB, "/" + agentB + "/skills"), agents(orgB, "/" + agentB + "/tools"),
                agents(orgB, "/" + agentB + "/memory-config"), path(orgB, "/teams/" + teamB + "/agents"),
                agents(orgA, "/" + agentB), agents(orgA, "/" + agentB + "/providers"), agents(orgA, "/" + agentB + "/memory-config"))) {
            getAs(alice, path).andExpect(status().isNotFound());
        }
        postAs(alice, agents(orgB, "/" + agentB + "/pause"), "{}").andExpect(status().isNotFound());
        postAs(alice, agents(orgA, "/" + agentB + "/pause"), "{}").andExpect(status().isNotFound());
        postAs(alice, agents(orgA, "/" + agentB + "/archive"), "{}").andExpect(status().isNotFound());
        patchAs(alice, agents(orgA, "/" + agentB), body("title", "Pwned")).andExpect(status().isNotFound());
        putAs(alice, agents(orgA, "/" + agentB + "/memory-config"), body("enabled", true, "scope", "ORGANIZATION", "accessMode", "ORGANIZATION_MEMBERS"))
                .andExpect(status().isNotFound());
        putAs(alice, agents(orgA, "/" + agentB + "/tools"), body("grants", List.of())).andExpect(status().isNotFound());
        postAs(alice, agents(orgB, ""), body("name", "Intruder")).andExpect(status().isNotFound());

        // References to B's resources from A's requests: they do not exist in A.
        putAs(alice, agents(orgA, "/" + agentA + "/department"), body("departmentId", deptB)).andExpect(status().isNotFound());
        postAs(alice, agents(orgA, ""), body("name", "Cross", "departmentId", deptB)).andExpect(status().isNotFound());
        putAs(alice, agents(orgA, "/" + agentA + "/skills"), body("skillIds", List.of(skillB))).andExpect(status().isNotFound());
        patchAs(alice, path(orgA, "/skills/" + skillB), body("name", "Pwned")).andExpect(status().isNotFound());
        deleteAs(alice, path(orgA, "/skills/" + skillB)).andExpect(status().isNotFound());
        postAs(alice, path(orgA, "/teams/" + teamB + "/agents"), body("agentId", agentA)).andExpect(status().isNotFound());
        postAs(alice, path(orgA, "/teams/" + teamA + "/agents"), body("agentId", agentB)).andExpect(status().isNotFound());
        deleteAs(alice, path(orgA, "/teams/" + teamB + "/agents/" + agentB)).andExpect(status().isNotFound());

        // Lists in A do not leak B; B is untouched.
        getAs(alice, agents(orgA, "")).andExpect(jsonPath("$.totalElements").value(1));
        getAs(alice, path(orgA, "/skills")).andExpect(jsonPath("$.totalElements").value(0));
        getAs(bob, agents(orgB, "/" + agentB)).andExpect(jsonPath("$.agent.status").value("ACTIVE"))
                .andExpect(jsonPath("$.agent.title").value(""));
        getAs(bob, path(orgB, "/skills")).andExpect(jsonPath("$.totalElements").value(1));
    }

    // ------------------------------------------------------------------ schema constraints

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id, email, email_verified, display_name, status, created_at, updated_at) "
                + "VALUES (?, ?, true, 'T', 'ACTIVE', now(), now())", id, id + "@example.test");
        return id;
    }

    private UUID organization(UUID creator) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, created_by, created_at, updated_at) VALUES (?, 'Org', ?, now(), now())",
                id, creator);
        return id;
    }

    private UUID department(UUID org, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, organization_id, name, code, created_at, updated_at) VALUES (?, ?, 'Dept', ?, now(), now())",
                id, org, code);
        return id;
    }

    private UUID agentRow(UUID org, UUID creator, String handle, UUID departmentId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, organization_id, handle, name, department_id, created_by, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'Agent', ?, ?, now(), now())", id, org, handle, departmentId, creator);
        return id;
    }

    private UUID secretRow(UUID org) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO secret_entries (id, organization_id, ciphertext, nonce, created_at) VALUES (?, ?, ?, ?, now())",
                id, org, new byte[] {1, 2, 3}, new byte[12]);
        return id;
    }

    private UUID connectionRow(UUID org, UUID creator, String name, UUID secret) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO provider_connections (id, organization_id, provider_type, display_name, status, credential_ref, "
                + "created_by, created_at, updated_at) VALUES (?, ?, 'OPENAI', ?, 'PENDING', ?, ?, now(), now())",
                id, org, name, secret, creator);
        return id;
    }

    private UUID skillRow(UUID org, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO skills (id, organization_id, name, created_at, updated_at) VALUES (?, ?, ?, now(), now())", id, org, name);
        return id;
    }

    private UUID teamRow(UUID org, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO teams (id, organization_id, name, created_at, updated_at) VALUES (?, ?, ?, now(), now())", id, org, name);
        return id;
    }

    private UUID bindingRow(UUID org, UUID agent, UUID connection, String model, String role, int priority) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO agent_provider_bindings (id, organization_id, agent_id, connection_id, model_id, role, priority, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())", id, org, agent, connection, model, role, priority);
        return id;
    }

    private void assertRejected(Runnable statement) {
        assertThatThrownBy(statement::run).isInstanceOf(DataAccessException.class);
    }

    @Test
    void compositeForeignKeysRefuseCrossOrganizationReferencesEvenWhenWrittenDirectly() {
        UUID creator = user();
        UUID orgA = organization(creator);
        UUID orgB = organization(creator);
        UUID deptB = department(orgB, "DB");
        UUID agentA = agentRow(orgA, creator, "agent-a", null);
        UUID agentB = agentRow(orgB, creator, "agent-b", null);
        UUID connectionA = connectionRow(orgA, creator, "Conn A", secretRow(orgA));
        UUID connectionB = connectionRow(orgB, creator, "Conn B", secretRow(orgB));
        UUID skillB = skillRow(orgB, "Skill B");
        UUID teamB = teamRow(orgB, "Team B");
        UUID secretB = secretRow(orgB);

        assertRejected(() -> agentRow(orgA, creator, "agent-x", deptB));
        assertRejected(() -> bindingRow(orgA, agentA, connectionB, "m-1", "PRIMARY", 0));
        assertRejected(() -> bindingRow(orgA, agentB, connectionA, "m-1", "PRIMARY", 0));
        assertRejected(() -> bindingRow(orgB, agentA, connectionB, "m-1", "PRIMARY", 0));
        assertRejected(() -> connectionRow(orgA, creator, "Stolen secret", secretB));
        assertRejected(() -> jdbc.update("INSERT INTO agent_skills (agent_id, skill_id, organization_id, assigned_at) VALUES (?, ?, ?, now())",
                agentA, skillB, orgA));
        assertRejected(() -> jdbc.update("INSERT INTO agent_skills (agent_id, skill_id, organization_id, assigned_at) VALUES (?, ?, ?, now())",
                agentB, skillB, orgA));
        assertRejected(() -> jdbc.update("INSERT INTO agent_tool_grants (agent_id, tool_code, organization_id, level, granted_at) "
                + "VALUES (?, 'code.analyze', ?, 'READ', now())", agentB, orgA));
        assertRejected(() -> jdbc.update("INSERT INTO agent_memory_configs (agent_id, organization_id, updated_at) VALUES (?, ?, now())",
                agentB, orgA));
        assertRejected(() -> jdbc.update("INSERT INTO team_agent_members (id, organization_id, team_id, agent_id, joined_at) "
                + "VALUES (?, ?, ?, ?, now())", UUID.randomUUID(), orgA, teamB, agentA));
        assertRejected(() -> jdbc.update("INSERT INTO team_agent_members (id, organization_id, team_id, agent_id, joined_at) "
                + "VALUES (?, ?, ?, ?, now())", UUID.randomUUID(), orgA, teamRow(orgA, "Team A"), agentB));
        // The same rows are accepted inside one organization.
        bindingRow(orgA, agentA, connectionA, "m-1", "PRIMARY", 0);
        jdbc.update("INSERT INTO agent_tool_grants (agent_id, tool_code, organization_id, level, granted_at) "
                + "VALUES (?, 'code.analyze', ?, 'READ', now())", agentA, orgA);
    }

    @Test
    void uniquenessAndCheckConstraintsProtectTheDomainInvariants() {
        UUID creator = user();
        UUID org = organization(creator);
        UUID other = organization(creator);
        UUID agent = agentRow(org, creator, "same-handle", null);
        agentRow(other, creator, "same-handle", null);
        assertRejected(() -> agentRow(org, creator, "same-handle", null));
        assertRejected(() -> agentRow(org, creator, "Bad Handle", null));
        assertRejected(() -> agentRow(org, creator, "x", null));
        assertRejected(() -> jdbc.update("UPDATE agents SET status = 'RUNNING' WHERE id = ?", agent));
        assertRejected(() -> jdbc.update("UPDATE agents SET config_version = 0 WHERE id = ?", agent));
        assertRejected(() -> jdbc.update("UPDATE agents SET avatar_tone = 9 WHERE id = ?", agent));
        assertRejected(() -> jdbc.update("UPDATE agents SET responsibilities = array_fill('x'::text, ARRAY[21]) WHERE id = ?",
                agent));

        skillRow(org, "Skill");
        skillRow(other, "Skill");
        assertRejected(() -> skillRow(org, "skill"));

        UUID connection = connectionRow(org, creator, "Conn", secretRow(org));
        assertRejected(() -> connectionRow(org, creator, "conn", secretRow(org)));
        // A connection holds a credential unless revoked, and only then.
        assertRejected(() -> jdbc.update("UPDATE provider_connections SET credential_ref = NULL WHERE id = ?", connection));
        assertRejected(() -> jdbc.update("UPDATE provider_connections SET status = 'REVOKED' WHERE id = ?", connection));
        assertRejected(() -> jdbc.update("UPDATE provider_connections SET provider_type = 'MISTRAL' WHERE id = ?", connection));

        UUID second = connectionRow(org, creator, "Conn 2", secretRow(org));
        bindingRow(org, agent, connection, "model-1", "PRIMARY", 0);
        assertRejected(() -> bindingRow(org, agent, second, "model-2", "PRIMARY", 0));       // one primary
        assertRejected(() -> bindingRow(org, agent, second, "model-2", "FALLBACK", 0));      // fallback priority >= 1
        assertRejected(() -> bindingRow(org, agent, second, "model-2", "PRIMARY", 3));       // primary priority = 0
        bindingRow(org, agent, second, "model-2", "FALLBACK", 1);
        assertRejected(() -> bindingRow(org, agent, connection, "model-3", "FALLBACK", 1));  // unique priority
        assertRejected(() -> bindingRow(org, agent, second, "model-2", "FALLBACK", 2));      // unique target
        assertRejected(() -> bindingRow(org, agent, second, "bad model", "FALLBACK", 2));    // model id format

        // Tool grants: levels are checked and per-project scope stays empty until projects exist.
        assertRejected(() -> jdbc.update("INSERT INTO agent_tool_grants (agent_id, tool_code, organization_id, level, granted_at) "
                + "VALUES (?, 'code.analyze', ?, 'ROOT', now())", agent, org));
        assertRejected(() -> jdbc.update("INSERT INTO agent_tool_grants (agent_id, tool_code, organization_id, level, project_id, "
                + "granted_at) VALUES (?, 'code.analyze', ?, 'READ', ?, now())", agent, org, UUID.randomUUID()));
        assertRejected(() -> jdbc.update("INSERT INTO agent_tool_grants (agent_id, tool_code, organization_id, level, granted_at) "
                + "VALUES (?, 'no.such.tool', ?, 'READ', now())", agent, org));

        // Memory: scope, access mode and retention are bounded.
        jdbc.update("INSERT INTO agent_memory_configs (agent_id, organization_id, updated_at) VALUES (?, ?, now())", agent, org);
        assertRejected(() -> jdbc.update("UPDATE agent_memory_configs SET scope = 'GLOBAL' WHERE agent_id = ?", agent));
        assertRejected(() -> jdbc.update("UPDATE agent_memory_configs SET access_mode = 'PUBLIC' WHERE agent_id = ?", agent));
        assertRejected(() -> jdbc.update("UPDATE agent_memory_configs SET retention_days = 0 WHERE agent_id = ?", agent));
        assertThat(jdbc.queryForObject("SELECT enabled FROM agent_memory_configs WHERE agent_id = ?", Boolean.class, agent)).isFalse();

        // One current team membership per agent and team; history is allowed.
        UUID team = teamRow(org, "Team");
        jdbc.update("INSERT INTO team_agent_members (id, organization_id, team_id, agent_id, joined_at) VALUES (?, ?, ?, ?, now())",
                UUID.randomUUID(), org, team, agent);
        assertRejected(() -> jdbc.update("INSERT INTO team_agent_members (id, organization_id, team_id, agent_id, joined_at) "
                + "VALUES (?, ?, ?, ?, now())", UUID.randomUUID(), org, team, agent));
        jdbc.update("UPDATE team_agent_members SET left_at = now() WHERE team_id = ?", team);
        jdbc.update("INSERT INTO team_agent_members (id, organization_id, team_id, agent_id, joined_at) VALUES (?, ?, ?, ?, now())",
                UUID.randomUUID(), org, team, agent);
        // Agents are a separate relationship: team_members has no agent column.
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'team_members'", String.class))
                .doesNotContain("agent_id", "member_type", "member_id");
    }

    @Test
    void agentTablesNeverStoreIdentityOrCredentialData() {
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_name IN ('agents', 'agent_provider_bindings', 'agent_memory_configs', 'team_agent_members', "
                + "'agent_skills', 'agent_tool_grants')", String.class);
        // An agent is not a user or an employee: no link to an account, membership or employee profile.
        assertThat(columns).doesNotContain("user_id", "membership_id", "employee_id", "external_identity_id", "email",
                "password", "api_key", "secret", "credential", "token");
        List<String> connectionColumns = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = 'provider_connections'", String.class);
        assertThat(connectionColumns).doesNotContain("api_key", "secret", "credential", "token", "password");
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void concurrentDuplicateTeamAssignmentsOfAnAgentKeepASingleCurrentMembership() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Concurrent agents");
        UUID agent = agent(owner, org, "Racer");
        UUID squad = team(owner, org, "Squad", null, null);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Callable<Integer> call = () -> {
                    start.await();
                    return postAs(owner, path(org, "/teams/" + squad + "/agents"), body("agentId", agent))
                            .andReturn().getResponse().getStatus();
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM team_agent_members WHERE team_id = ? AND left_at IS NULL",
                Long.class, squad)).isOne();
    }
}
