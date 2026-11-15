package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.support.TestcontainersConfiguration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Database-level guarantees: least-privilege runtime role, permission catalog in sync with the
 * code, tenant-consistent foreign keys and uniqueness, and the B0 → B1 migration path.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class DatabaseSecurityIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theApplicationConnectsWithTheRuntimeRoleWhichCannotChangeTheSchema() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class))
                .isEqualTo(TestcontainersConfiguration.APP_USER);
        assertThat(jdbc.queryForObject("SELECT rolsuper OR rolcreaterole OR rolcreatedb FROM pg_roles "
                + "WHERE rolname = current_user", Boolean.class)).isFalse();
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE intruder (id int)")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("ALTER TABLE organizations ADD COLUMN x int"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("DROP TABLE audit_events")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Long.class))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void auditEventsAreAppendOnlyAndThePermissionCatalogIsReadOnlyForTheApplication() {
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_events SET action = 'tampered'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO permissions (code, description) VALUES ('god.mode', 'x')"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void thePermissionCatalogMatchesTheCode() {
        List<String> inDatabase = jdbc.queryForList("SELECT code FROM permissions ORDER BY code", String.class);
        List<String> inCode = Arrays.stream(Permission.values()).map(Permission::code).sorted().toList();
        assertThat(inDatabase).isEqualTo(inCode);
    }

    @Test
    void foreignKeysKeepRoleAssignmentsInsideOneOrganization() {
        UUID user = insertUser();
        UUID orgA = insertOrganization(user);
        UUID orgB = insertOrganization(user);
        UUID membershipA = insertMembership(orgA, user);
        UUID roleB = insertRole(orgB, "Role of B");

        // A role of B cannot be attached to a membership of A, even by writing organization_id = A.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO membership_roles (membership_id, role_id, organization_id, "
                + "assigned_at) VALUES (?, ?, ?, now())", membershipA, roleB, orgA))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO membership_roles (membership_id, role_id, organization_id, "
                + "assigned_at) VALUES (?, ?, ?, now())", membershipA, roleB, orgB))
                .isInstanceOf(DataIntegrityViolationException.class);
        // An invitation cannot offer a role of another organization.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO organization_invitations (id, organization_id, email, role_id, "
                + "token_hash, status, expires_at, invited_by, created_at) VALUES (?, ?, 'x@example.test', ?, ?, "
                + "'PENDING', now() + interval '1 day', ?, now())",
                UUID.randomUUID(), orgA, roleB, UUID.randomUUID().toString(), user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneMembershipPerUserAndOrganizationButManyOrganizationsPerUser() {
        UUID user = insertUser();
        UUID orgA = insertOrganization(user);
        UUID orgB = insertOrganization(user);
        insertMembership(orgA, user);
        insertMembership(orgB, user);
        assertThatThrownBy(() -> insertMembership(orgA, user)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO external_identities (id, user_id, issuer, subject, created_at, "
                + "last_login_at) SELECT gen_random_uuid(), ?, issuer, subject, now(), now() FROM external_identities "
                + "LIMIT 1", user)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migratesAnExistingB0DatabaseToTheCurrentSchema() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE)) {
            postgres.start();
            // B0 state: V1 applied by the bootstrap superuser, as Sprint B0 did.
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("1").skipDefaultCallbacks(true)
                    .placeholders(java.util.Map.of("app_role", postgres.getUsername()))
                    .load().migrate();
            // B1: roles are introduced on the existing database, then the migration role continues.
            TestcontainersConfiguration.createRoles(postgres);
            var result = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), TestcontainersConfiguration.MIGRATION_USER,
                            TestcontainersConfiguration.MIGRATION_PASSWORD)
                    .locations("classpath:db/migration")
                    .placeholders(java.util.Map.of("app_role", TestcontainersConfiguration.APP_USER))
                    .load().migrate();
            assertThat(result.success).isTrue();
            assertThat(result.targetSchemaVersion).isEqualTo("8");
        }
    }

    /**
     * A B1 database (V1 + V2, with organizations whose built-in roles already exist) upgrades to V3:
     * the new permissions are granted to the existing OWNER/ADMIN/MEMBER roles, applied
     * migrations stay valid, and the runtime role keeps data access only.
     */
    @Test
    void upgradesAnExistingB1DatabaseAndBackfillsBuiltInRolePermissions() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE)) {
            postgres.start();
            TestcontainersConfiguration.createRoles(postgres);
            java.util.function.Function<String, Flyway> flyway = target -> Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), TestcontainersConfiguration.MIGRATION_USER,
                            TestcontainersConfiguration.MIGRATION_PASSWORD)
                    .locations("classpath:db/migration").target(target)
                    .placeholders(java.util.Map.of("app_role", TestcontainersConfiguration.APP_USER))
                    .load();
            flyway.apply("2").migrate();

            UUID user = UUID.randomUUID();
            UUID organization = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID admin = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID custom = UUID.randomUUID();
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
                    java.sql.Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO user_accounts (id, email, email_verified, display_name, status, created_at, "
                        + "updated_at) VALUES ('" + user + "', 'b1@example.test', true, 'B1', 'ACTIVE', now(), now())");
                statement.execute("INSERT INTO organizations (id, name, created_by, created_at, updated_at) VALUES ('"
                        + organization + "', 'Existing B1 organization', '" + user + "', now(), now())");
                String roles = "INSERT INTO roles (id, organization_id, code, name, built_in, created_at, updated_at) VALUES ";
                statement.execute(roles + "('" + owner + "', '" + organization + "', 'OWNER', 'Owner', true, now(), now()), ('"
                        + admin + "', '" + organization + "', 'ADMIN', 'Administrator', true, now(), now()), ('"
                        + member + "', '" + organization + "', 'MEMBER', 'Member', true, now(), now())");
                statement.execute("INSERT INTO roles (id, organization_id, name, built_in, created_at, updated_at) VALUES ('"
                        + custom + "', '" + organization + "', 'Custom auditor', false, now(), now())");
                // B1 permissions of the built-in roles (and one custom role).
                for (UUID role : new UUID[] {owner, admin, member}) {
                    statement.execute("INSERT INTO role_permissions (role_id, organization_id, permission_code) VALUES ('"
                            + role + "', '" + organization + "', 'organization.read')");
                }
                statement.execute("INSERT INTO role_permissions (role_id, organization_id, permission_code) VALUES ('"
                        + custom + "', '" + organization + "', 'audit.read')");
            }

            // V1 and V2 stay valid and V3 applies on top of the existing data.
            var result = flyway.apply("3").migrate();
            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted).isEqualTo(1);
            assertThat(result.targetSchemaVersion).isEqualTo("3");

            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword())) {
                assertThat(countPermissions(connection, owner, "(employee|department|position|team)[.]")).isEqualTo(10);
                assertThat(countPermissions(connection, admin, "(employee|department|position|team)[.]")).isEqualTo(10);
                // MEMBER only gets the read permissions.
                assertThat(countPermissions(connection, member, "(employee|department|position|team)[.]")).isEqualTo(4);
                assertThat(countPermissions(connection, member, "[.](create|manage|deactivate)$")).isZero();
                // Custom roles are never widened by a migration.
                assertThat(countPermissions(connection, custom, ".")).isEqualTo(1);
            }
            // Validation (checksums of V1..V3) still passes, and the runtime role has data access only.
            flyway.apply("3").validate();
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    TestcontainersConfiguration.APP_USER, TestcontainersConfiguration.APP_PASSWORD);
                    java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SELECT count(*) FROM employee_profiles");
                statement.execute("INSERT INTO teams (id, organization_id, name, created_at, updated_at) VALUES ('"
                        + UUID.randomUUID() + "', '" + organization + "', 'Runtime team', now(), now())");
                assertThatThrownBy(() -> statement.execute("CREATE TABLE intruder (id int)"))
                        .isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> statement.execute("ALTER TABLE departments ADD COLUMN x int"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
        }
    }

    /**
     * A B2 database (V1..V3 with organizations whose built-in roles hold the workforce permissions)
     * upgrades to V4: agent permissions are granted to the built-in roles (OWNER/ADMIN all twelve,
     * MEMBER the two reads), custom roles are untouched, the tool registry is seeded and read-only
     * for the runtime role, and applied migrations stay valid.
     */
    @Test
    void upgradesAnExistingB2DatabaseAndBackfillsAgentPermissions() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE)) {
            postgres.start();
            TestcontainersConfiguration.createRoles(postgres);
            java.util.function.Function<String, Flyway> flyway = target -> Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), TestcontainersConfiguration.MIGRATION_USER,
                            TestcontainersConfiguration.MIGRATION_PASSWORD)
                    .locations("classpath:db/migration").target(target)
                    .placeholders(java.util.Map.of("app_role", TestcontainersConfiguration.APP_USER))
                    .load();
            flyway.apply("3").migrate();

            UUID user = UUID.randomUUID();
            UUID organization = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID admin = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID custom = UUID.randomUUID();
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
                    java.sql.Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO user_accounts (id, email, email_verified, display_name, status, created_at, "
                        + "updated_at) VALUES ('" + user + "', 'b2@example.test', true, 'B2', 'ACTIVE', now(), now())");
                statement.execute("INSERT INTO organizations (id, name, created_by, created_at, updated_at) VALUES ('"
                        + organization + "', 'Existing B2 organization', '" + user + "', now(), now())");
                String roles = "INSERT INTO roles (id, organization_id, code, name, built_in, created_at, updated_at) VALUES ";
                statement.execute(roles + "('" + owner + "', '" + organization + "', 'OWNER', 'Owner', true, now(), now()), ('"
                        + admin + "', '" + organization + "', 'ADMIN', 'Administrator', true, now(), now()), ('"
                        + member + "', '" + organization + "', 'MEMBER', 'Member', true, now(), now())");
                statement.execute("INSERT INTO roles (id, organization_id, name, built_in, created_at, updated_at) VALUES ('"
                        + custom + "', '" + organization + "', 'HR assistant', false, now(), now())");
                for (UUID role : new UUID[] {owner, admin, member, custom}) {
                    statement.execute("INSERT INTO role_permissions (role_id, organization_id, permission_code) VALUES ('"
                            + role + "', '" + organization + "', 'employee.read')");
                }
            }

            var result = flyway.apply("4").migrate();
            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted).isEqualTo(1);
            assertThat(result.targetSchemaVersion).isEqualTo("4");

            String agentPermissions = "^(agent|provider|skill)[.]";
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword())) {
                assertThat(countPermissions(connection, owner, agentPermissions)).isEqualTo(12);
                assertThat(countPermissions(connection, admin, agentPermissions)).isEqualTo(12);
                assertThat(countPermissions(connection, member, agentPermissions)).isEqualTo(2);
                assertThat(countPermissions(connection, member, "^(agent|skill)[.]read$")).isEqualTo(2);
                // Plain members never see provider connections; custom roles are never widened.
                assertThat(countPermissions(connection, member, "^provider[.]")).isZero();
                assertThat(countPermissions(connection, custom, ".")).isEqualTo(1);
                try (java.sql.Statement statement = connection.createStatement();
                        java.sql.ResultSet rows = statement.executeQuery("SELECT count(*) FROM tool_definitions")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(6);
                }
            }
            flyway.apply("4").validate();
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    TestcontainersConfiguration.APP_USER, TestcontainersConfiguration.APP_PASSWORD);
                    java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SELECT count(*) FROM agents");
                statement.execute("SELECT count(*) FROM secret_entries");
                // The tool registry belongs to migrations; the runtime role cannot change it or the schema.
                assertThatThrownBy(() -> statement.execute("UPDATE tool_definitions SET enabled = false"))
                        .isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> statement.execute("INSERT INTO tool_definitions (code, name, description, category, "
                        + "risk, max_level) VALUES ('rogue.tool', 'x', 'x', 'x', 'LOW', 'READ')"))
                        .isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> statement.execute("ALTER TABLE agents ADD COLUMN x int"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
        }
    }

    /**
     * A B3 database (V1..V4, built-in roles already holding the agent permissions) upgrades to V5:
     * the two execution permissions go to OWNER and ADMIN only (members and custom roles are never
     * widened), the execution table exists with its state-machine constraints and tenant keys, and
     * the runtime role can use it but not change the schema.
     */
    @Test
    void upgradesAnExistingB3DatabaseAndAddsExecutionPermissions() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE)) {
            postgres.start();
            TestcontainersConfiguration.createRoles(postgres);
            java.util.function.Function<String, Flyway> flyway = target -> Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), TestcontainersConfiguration.MIGRATION_USER,
                            TestcontainersConfiguration.MIGRATION_PASSWORD)
                    .locations("classpath:db/migration").target(target)
                    .placeholders(java.util.Map.of("app_role", TestcontainersConfiguration.APP_USER))
                    .load();
            flyway.apply("4").migrate();

            UUID user = UUID.randomUUID();
            UUID organization = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID admin = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID custom = UUID.randomUUID();
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
                    java.sql.Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO user_accounts (id, email, email_verified, display_name, status, created_at, "
                        + "updated_at) VALUES ('" + user + "', 'b3@example.test', true, 'B3', 'ACTIVE', now(), now())");
                statement.execute("INSERT INTO organizations (id, name, created_by, created_at, updated_at) VALUES ('"
                        + organization + "', 'Existing B3 organization', '" + user + "', now(), now())");
                String roles = "INSERT INTO roles (id, organization_id, code, name, built_in, created_at, updated_at) VALUES ";
                statement.execute(roles + "('" + owner + "', '" + organization + "', 'OWNER', 'Owner', true, now(), now()), ('"
                        + admin + "', '" + organization + "', 'ADMIN', 'Administrator', true, now(), now()), ('"
                        + member + "', '" + organization + "', 'MEMBER', 'Member', true, now(), now())");
                statement.execute("INSERT INTO roles (id, organization_id, name, built_in, created_at, updated_at) VALUES ('"
                        + custom + "', '" + organization + "', 'Agent reader', false, now(), now())");
                for (UUID role : new UUID[] {owner, admin, member, custom}) {
                    statement.execute("INSERT INTO role_permissions (role_id, organization_id, permission_code) VALUES ('"
                            + role + "', '" + organization + "', 'agent.read')");
                }
            }

            var result = flyway.apply("5").migrate();
            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted).isEqualTo(1);
            assertThat(result.targetSchemaVersion).isEqualTo("5");

            String execution = "^agent[.]execut";
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword())) {
                assertThat(countPermissions(connection, owner, execution)).isEqualTo(2);
                assertThat(countPermissions(connection, admin, execution)).isEqualTo(2);
                assertThat(countPermissions(connection, member, execution)).isZero();
                assertThat(countPermissions(connection, custom, execution)).isZero();
                assertThat(countPermissions(connection, custom, ".")).isEqualTo(1);
            }
            flyway.apply("5").validate();
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    TestcontainersConfiguration.APP_USER, TestcontainersConfiguration.APP_PASSWORD);
                    java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SELECT count(*) FROM agent_executions");
                assertThatThrownBy(() -> statement.execute("ALTER TABLE agent_executions ADD COLUMN x int"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
        }
    }

    /**
     * A C1 database (V1..V5, with an organization whose built-in roles exist and one execution already
     * RUNNING) upgrades to V6: tool registry columns with a DENIED default, the three reference tools,
     * the approval permissions for OWNER and ADMIN only, the WAITING_APPROVAL status, the invocation and
     * approval tables with their database-level guarantees, and the runtime role keeps DML but no DDL.
     */
    @Test
    void upgradesAnExistingC1DatabaseAndAddsToolExecutionAndApprovals() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE)) {
            postgres.start();
            TestcontainersConfiguration.createRoles(postgres);
            java.util.function.Function<String, Flyway> flyway = target -> Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), TestcontainersConfiguration.MIGRATION_USER,
                            TestcontainersConfiguration.MIGRATION_PASSWORD)
                    .locations("classpath:db/migration").target(target)
                    .placeholders(java.util.Map.of("app_role", TestcontainersConfiguration.APP_USER))
                    .load();
            flyway.apply("5").migrate();

            UUID user = UUID.randomUUID();
            UUID organization = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID admin = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID custom = UUID.randomUUID();
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
                    java.sql.Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO user_accounts (id, email, email_verified, display_name, status, created_at, "
                        + "updated_at) VALUES ('" + user + "', 'c1@example.test', true, 'C1', 'ACTIVE', now(), now())");
                statement.execute("INSERT INTO organizations (id, name, created_by, created_at, updated_at) VALUES ('"
                        + organization + "', 'Existing C1 organization', '" + user + "', now(), now())");
                String roles = "INSERT INTO roles (id, organization_id, code, name, built_in, created_at, updated_at) VALUES ";
                statement.execute(roles + "('" + owner + "', '" + organization + "', 'OWNER', 'Owner', true, now(), now()), ('"
                        + admin + "', '" + organization + "', 'ADMIN', 'Administrator', true, now(), now()), ('"
                        + member + "', '" + organization + "', 'MEMBER', 'Member', true, now(), now())");
                statement.execute("INSERT INTO roles (id, organization_id, name, built_in, created_at, updated_at) VALUES ('"
                        + custom + "', '" + organization + "', 'Runner', false, now(), now())");
                for (UUID role : new UUID[] {owner, admin, member, custom}) {
                    statement.execute("INSERT INTO role_permissions (role_id, organization_id, permission_code) VALUES ('"
                            + role + "', '" + organization + "', 'agent.execute')");
                }
            }

            var result = flyway.apply("6").migrate();
            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted).isEqualTo(1);
            assertThat(result.targetSchemaVersion).isEqualTo("6");

            String approvals = "^approval[.]";
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
                    java.sql.Statement statement = connection.createStatement()) {
                assertThat(countPermissions(connection, owner, approvals)).isEqualTo(2);
                assertThat(countPermissions(connection, admin, approvals)).isEqualTo(2);
                assertThat(countPermissions(connection, member, approvals)).isZero();
                assertThat(countPermissions(connection, custom, approvals)).isZero();
                assertThat(countPermissions(connection, custom, ".")).isEqualTo(1);
                // Every B3 placeholder defaults to DENIED; only the three reference tools can run.
                try (java.sql.ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FILTER (WHERE execution_policy = 'DENIED'), count(*) FILTER (WHERE execution_policy <> 'DENIED') FROM tool_definitions")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(6);
                    assertThat(rows.getInt(2)).isEqualTo(3);
                }
                // A READ_ONLY policy can never cover a write level or a HIGH risk tool.
                assertThatThrownBy(() -> statement.execute("INSERT INTO tool_definitions (code, name, description, category, risk, max_level, "
                        + "required_level, execution_policy, mcp_server, mcp_tool) VALUES ('rogue.write', 'x', 'x', 'x', 'LOW', 'WRITE', 'WRITE', "
                        + "'READ_ONLY', 's', 't')")).isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> statement.execute("INSERT INTO tool_definitions (code, name, description, category, risk, max_level, "
                        + "required_level, execution_policy, mcp_server, mcp_tool) VALUES ('rogue.high', 'x', 'x', 'x', 'HIGH', 'READ', 'READ', "
                        + "'READ_ONLY', 's', 't')")).isInstanceOf(java.sql.SQLException.class);
                // The execution table accepts WAITING_APPROVAL and still refuses unknown states.
                assertThat(statement.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_agent_executions_status'")
                        .next()).isTrue();
            }
            flyway.apply("6").validate();
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    TestcontainersConfiguration.APP_USER, TestcontainersConfiguration.APP_PASSWORD);
                    java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SELECT count(*) FROM tool_invocations");
                statement.execute("SELECT count(*) FROM tool_approvals");
                assertThatThrownBy(() -> statement.execute("UPDATE tool_definitions SET execution_policy = 'READ_ONLY'"))
                        .isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> statement.execute("ALTER TABLE tool_approvals ADD COLUMN x int"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
        }
    }

    /**
     * A C2 database (V1..V6, an organization with its built-in roles and an agent execution already recorded) upgrades to
     * V7: the workflow permissions for OWNER and ADMIN only, the five workflow tables with their database-level guarantees
     * (append-only journal, one live step per rank, one step per execution), agent_executions unchanged, and a runtime
     * role that keeps DML but no DDL.
     */
    @Test
    void upgradesAnExistingC2DatabaseAndAddsMultiAgentWorkflows() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE)) {
            postgres.start();
            TestcontainersConfiguration.createRoles(postgres);
            java.util.function.Function<String, Flyway> flyway = target -> Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), TestcontainersConfiguration.MIGRATION_USER,
                            TestcontainersConfiguration.MIGRATION_PASSWORD)
                    .locations("classpath:db/migration").target(target)
                    .placeholders(java.util.Map.of("app_role", TestcontainersConfiguration.APP_USER))
                    .load();
            flyway.apply("6").migrate();

            UUID user = UUID.randomUUID();
            UUID organization = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID admin = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID custom = UUID.randomUUID();
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
                    java.sql.Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO user_accounts (id, email, email_verified, display_name, status, created_at, "
                        + "updated_at) VALUES ('" + user + "', 'c2@example.test', true, 'C2', 'ACTIVE', now(), now())");
                statement.execute("INSERT INTO organizations (id, name, created_by, created_at, updated_at) VALUES ('"
                        + organization + "', 'Existing C2 organization', '" + user + "', now(), now())");
                String roles = "INSERT INTO roles (id, organization_id, code, name, built_in, created_at, updated_at) VALUES ";
                statement.execute(roles + "('" + owner + "', '" + organization + "', 'OWNER', 'Owner', true, now(), now()), ('"
                        + admin + "', '" + organization + "', 'ADMIN', 'Administrator', true, now(), now()), ('"
                        + member + "', '" + organization + "', 'MEMBER', 'Member', true, now(), now())");
                statement.execute("INSERT INTO roles (id, organization_id, name, built_in, created_at, updated_at) VALUES ('"
                        + custom + "', '" + organization + "', 'Runner', false, now(), now())");
                for (UUID role : new UUID[] {owner, admin, member, custom}) {
                    statement.execute("INSERT INTO role_permissions (role_id, organization_id, permission_code) VALUES ('"
                            + role + "', '" + organization + "', 'agent.execute')");
                }
            }

            var result = flyway.apply("7").migrate();
            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted).isEqualTo(1);
            assertThat(result.targetSchemaVersion).isEqualTo("7");

            String workflows = "^workflow[.]";
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
                    java.sql.Statement statement = connection.createStatement()) {
                assertThat(countPermissions(connection, owner, workflows)).isEqualTo(3);
                assertThat(countPermissions(connection, admin, workflows)).isEqualTo(3);
                assertThat(countPermissions(connection, member, workflows)).isZero();
                assertThat(countPermissions(connection, custom, workflows)).isZero();
                assertThat(countPermissions(connection, custom, ".")).isEqualTo(1);
                // agent_executions carries no workflow state (the link lives in workflow_steps).
                try (java.sql.ResultSet columns = statement.executeQuery("SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'agent_executions' AND column_name ~ 'workflow|run_id'")) {
                    columns.next();
                    assertThat(columns.getInt(1)).isZero();
                }
                // Hard limits live in the database: at most 8 steps per definition and run.
                assertThatThrownBy(() -> statement.execute("INSERT INTO workflow_definitions (id, organization_id, name, max_steps, created_by, "
                        + "created_at, updated_at) VALUES ('" + UUID.randomUUID() + "', '" + organization + "', 'x', 9, '" + user + "', now(), now())"))
                        .isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> statement.execute("INSERT INTO workflow_definitions (id, organization_id, name, token_budget, created_by, "
                        + "created_at, updated_at) VALUES ('" + UUID.randomUUID() + "', '" + organization + "', 'x', 0, '" + user + "', now(), now())"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
            flyway.apply("7").validate();
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    TestcontainersConfiguration.APP_USER, TestcontainersConfiguration.APP_PASSWORD);
                    java.sql.Statement statement = connection.createStatement()) {
                for (String table : new String[] {"workflow_definitions", "workflow_participants", "workflow_runs", "workflow_steps", "workflow_messages"}) {
                    statement.execute("SELECT count(*) FROM " + table);
                }
                assertThatThrownBy(() -> statement.execute("ALTER TABLE workflow_runs ADD COLUMN x int")).isInstanceOf(java.sql.SQLException.class);
                // The journal is append-only even for the role that may write to it.
                statement.execute("INSERT INTO workflow_definitions (id, organization_id, name, created_by, created_at, updated_at) VALUES ('"
                        + "00000000-0000-0000-0000-0000000000d1', '" + organization + "', 'wf', '" + user + "', now(), now())");
                statement.execute("INSERT INTO workflow_runs (id, organization_id, definition_id, definition_version, snapshot, requested_by, objective, "
                        + "status, total_steps, token_budget, deadline_at, created_at, updated_at) VALUES ('00000000-0000-0000-0000-0000000000e1', '"
                        + organization + "', '00000000-0000-0000-0000-0000000000d1', 1, '{}', '" + user + "', 'o', 'PENDING', 1, 1000, now(), now(), now())");
                statement.execute("INSERT INTO workflow_messages (id, organization_id, run_id, sequence, kind, body, created_at) VALUES ('"
                        + UUID.randomUUID() + "', '" + organization + "', '00000000-0000-0000-0000-0000000000e1', 1, 'OBJECTIVE', 'o', now())");
                assertThatThrownBy(() -> statement.execute("UPDATE workflow_messages SET body = 'changed'")).isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> statement.execute("DELETE FROM workflow_messages")).isInstanceOf(java.sql.SQLException.class);
            }
        }
    }

    /**
     * A C3.1 database (V1..V7 with a pipeline definition and a run already recorded) upgrades to V8: existing rows become
     * PIPELINE, the coordinator is only allowed on a COORDINATED definition, the action table enforces idempotency and one
     * accepted action per coordinator turn, and the runtime role keeps DML without DDL.
     */
    @Test
    void upgradesAnExistingC31DatabaseAndAddsTheCoordinator() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE)) {
            postgres.start();
            TestcontainersConfiguration.createRoles(postgres);
            java.util.function.Function<String, Flyway> flyway = target -> Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), TestcontainersConfiguration.MIGRATION_USER,
                            TestcontainersConfiguration.MIGRATION_PASSWORD)
                    .locations("classpath:db/migration").target(target)
                    .placeholders(java.util.Map.of("app_role", TestcontainersConfiguration.APP_USER))
                    .load();
            flyway.apply("7").migrate();
            UUID user = UUID.randomUUID();
            UUID organization = UUID.randomUUID();
            UUID definition = UUID.randomUUID();
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
                    java.sql.Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO user_accounts (id, email, email_verified, display_name, status, created_at, "
                        + "updated_at) VALUES ('" + user + "', 'c31@example.test', true, 'C31', 'ACTIVE', now(), now())");
                statement.execute("INSERT INTO organizations (id, name, created_by, created_at, updated_at) VALUES ('"
                        + organization + "', 'Existing C31 organization', '" + user + "', now(), now())");
                statement.execute("INSERT INTO workflow_definitions (id, organization_id, name, created_by, created_at, updated_at) VALUES ('"
                        + definition + "', '" + organization + "', 'pipeline', '" + user + "', now(), now())");
                statement.execute("INSERT INTO workflow_runs (id, organization_id, definition_id, definition_version, snapshot, requested_by, "
                        + "objective, status, total_steps, token_budget, deadline_at, created_at, updated_at) VALUES ('"
                        + UUID.randomUUID() + "', '" + organization + "', '" + definition + "', 1, '{}', '" + user
                        + "', 'o', 'PENDING', 1, 1000, now(), now(), now())");
            }
            var result = flyway.apply("latest").migrate();
            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted).isEqualTo(1);
            assertThat(result.targetSchemaVersion).isEqualTo("8");
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
                    java.sql.Statement statement = connection.createStatement()) {
                try (java.sql.ResultSet rows = statement.executeQuery("SELECT (SELECT count(*) FROM workflow_definitions WHERE mode = 'PIPELINE' "
                        + "AND coordinator_agent_id IS NULL), (SELECT count(*) FROM workflow_runs WHERE mode = 'PIPELINE')")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                    assertThat(rows.getInt(2)).isEqualTo(1);
                }
                // A pipeline never has a coordinator; the mode is one of the two known values.
                assertThatThrownBy(() -> statement.execute("INSERT INTO workflow_definitions (id, organization_id, name, mode, created_by, "
                        + "created_at, updated_at) VALUES ('" + UUID.randomUUID() + "', '" + organization + "', 'x', 'OTHER', '" + user
                        + "', now(), now())")).isInstanceOf(java.sql.SQLException.class);
                // The run counters allow a coordinated run (up to 8 workers plus 9 coordinator turns).
                try (java.sql.ResultSet rows = statement.executeQuery("SELECT count(*) FROM pg_constraint WHERE conname IN "
                        + "('uq_workflow_actions_call', 'ck_workflow_actions_action', 'ck_workflow_steps_role')")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(3);
                }
                try (java.sql.ResultSet rows = statement.executeQuery("SELECT count(*) FROM pg_indexes WHERE indexname IN "
                        + "('uq_workflow_actions_one_per_turn', 'uq_workflow_actions_no_repeat')")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(2);
                }
            }
            flyway.apply("latest").validate();
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    TestcontainersConfiguration.APP_USER, TestcontainersConfiguration.APP_PASSWORD);
                    java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SELECT count(*) FROM workflow_actions");
                assertThatThrownBy(() -> statement.execute("ALTER TABLE workflow_actions ADD COLUMN x int")).isInstanceOf(java.sql.SQLException.class);
            }
        }
    }

    private static int countPermissions(java.sql.Connection connection, UUID role, String regex) throws Exception {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM role_permissions WHERE role_id = ? AND permission_code ~ ?")) {
            statement.setObject(1, role);
            statement.setString(2, regex);
            try (java.sql.ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id, email, email_verified, display_name, status, created_at, updated_at) "
                + "VALUES (?, ?, true, 'Test', 'ACTIVE', now(), now())", id, id + "@example.test");
        jdbc.update("INSERT INTO external_identities (id, user_id, issuer, subject, created_at, last_login_at) "
                + "VALUES (?, ?, 'http://issuer.test', ?, now(), now())", UUID.randomUUID(), id, id.toString());
        return id;
    }

    private UUID insertOrganization(UUID creator) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, created_by, created_at, updated_at) VALUES (?, 'Org', ?, now(), "
                + "now())", id, creator);
        return id;
    }

    private UUID insertMembership(UUID organizationId, UUID userId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO memberships (id, organization_id, user_id, status, joined_at, status_changed_at, "
                + "created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', now(), now(), now(), now())",
                id, organizationId, userId);
        return id;
    }

    private UUID insertRole(UUID organizationId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO roles (id, organization_id, name, description, built_in, created_at, updated_at) "
                + "VALUES (?, ?, ?, '', false, now(), now())", id, organizationId, name);
        return id;
    }
}
