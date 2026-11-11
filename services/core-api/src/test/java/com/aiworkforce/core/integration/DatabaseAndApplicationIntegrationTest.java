package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.support.TestcontainersConfiguration;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full application against a real PostgreSQL 18 + pgvector container.
 * Skipped (not failed) when Docker is unavailable: see the test report for the distinction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class DatabaseAndApplicationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ApplicationContext context;

    @Test
    void connectsToPostgresql18() {
        Integer major = jdbc.queryForObject("SELECT current_setting('server_version_num')::int / 10000", Integer.class);
        assertThat(major).isEqualTo(18);
        assertThat(jdbc.queryForObject("SHOW TIME ZONE", String.class)).isEqualTo("UTC");
    }

    @Test
    void appliesFlywayMigrations() {
        Map<String, Object> v1 = jdbc.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE version = '1'");
        assertThat(v1).containsEntry("version", "1").containsEntry("success", true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Long.class))
                .isZero();
    }

    @Test
    void enablesAWorkingPgvectorExtension() {
        assertThat(jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class))
                .isNotBlank();
        Double distance = jdbc.queryForObject("SELECT '[1,2,3]'::vector <-> '[1,2,4]'::vector", Double.class);
        assertThat(distance).isEqualTo(1.0);
    }

    @Test
    void validatesTheSchemaWithHibernateInsteadOfGeneratingIt() {
        assertThat(entityManagerFactory.getProperties()).containsEntry("hibernate.hbm2ddl.auto", "validate");
    }

    @Test
    void doesNotRegisterAnyUserAccount() {
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    }

    @Test
    void exposesPublicHealthBackedByTheDatabase() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("core-api"))
                .andExpect(jsonPath("$.version").isNotEmpty())
                .andExpect(jsonPath("$.timestamp", endsWith("Z")));
    }

    @Test
    void exposesActuatorHealthAndProbesWithoutDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.build.version").isNotEmpty());
    }

    @Test
    void keepsSensitiveActuatorEndpointsAndApiDocsClosed() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/configprops")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/heapdump")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }
}
