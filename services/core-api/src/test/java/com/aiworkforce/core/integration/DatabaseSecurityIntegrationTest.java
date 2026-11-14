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
            assertThat(result.targetSchemaVersion).isEqualTo("3");
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
            var result = flyway.apply("latest").migrate();
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
            flyway.apply("latest").validate();
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
