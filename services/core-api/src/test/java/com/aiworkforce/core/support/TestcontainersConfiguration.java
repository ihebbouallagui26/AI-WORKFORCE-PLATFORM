package com.aiworkforce.core.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL for integration tests, using the same image as infra/docker/compose.yaml
 * (PostgreSQL 18 + pgvector) and the <strong>same privilege separation</strong>: the container's
 * superuser only runs {@code infra/docker/postgres/init-roles.sh}; Flyway connects as the
 * migration role and the application as the runtime role (no DDL), exactly as in production.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg18").asCompatibleSubstituteFor("postgres");

    public static final String MIGRATION_USER = "aiw_migrator";
    public static final String MIGRATION_PASSWORD = "test-migrator-password";
    public static final String APP_USER = "aiw_app";
    public static final String APP_PASSWORD = "test-app-password";

    private static final Path INIT_ROLES_SCRIPT = Path.of("..", "..", "infra", "docker", "postgres", "init-roles.sh");

    @Bean
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }

    @Bean
    DynamicPropertyRegistrar databaseProperties(PostgreSQLContainer postgres) {
        return registry -> {
            postgres.start();
            createRoles(postgres);
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", () -> APP_USER);
            registry.add("spring.datasource.password", () -> APP_PASSWORD);
            registry.add("spring.flyway.user", () -> MIGRATION_USER);
            registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
            registry.add("spring.flyway.placeholders.app_role", () -> APP_USER);
        };
    }

    /** Runs the production role script inside the container (idempotent). */
    public static void createRoles(PostgreSQLContainer postgres) {
        try {
            postgres.copyFileToContainer(Transferable.of(initRolesScript(), 0755), "/tmp/init-roles.sh");
            ExecResult result = postgres.execInContainer("env",
                    "PGHOST=localhost", "PGUSER=" + postgres.getUsername(), "PGPASSWORD=" + postgres.getPassword(),
                    "APP_DB_NAME=" + postgres.getDatabaseName(),
                    "DB_MIGRATION_USERNAME=" + MIGRATION_USER, "DB_MIGRATION_PASSWORD=" + MIGRATION_PASSWORD,
                    "DB_APP_USERNAME=" + APP_USER, "DB_APP_PASSWORD=" + APP_PASSWORD,
                    "KEYCLOAK_DB_NAME=keycloak", "KEYCLOAK_DB_USERNAME=keycloak",
                    "KEYCLOAK_DB_PASSWORD=test-keycloak-password",
                    "bash", "/tmp/init-roles.sh");
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("init-roles.sh failed: " + result.getStdout() + result.getStderr());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The script is copied with LF line endings whatever the checkout settings of the host. */
    private static byte[] initRolesScript() {
        try {
            return Files.readString(INIT_ROLES_SCRIPT, StandardCharsets.UTF_8).replace("\r\n", "\n")
                    .getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + INIT_ROLES_SCRIPT.toAbsolutePath(), e);
        }
    }
}
