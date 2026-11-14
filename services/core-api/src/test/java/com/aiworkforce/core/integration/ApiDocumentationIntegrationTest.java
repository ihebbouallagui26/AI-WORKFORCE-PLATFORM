package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.support.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * API documentation as enabled in the development profile. Also writes the generated
 * OpenAPI document to {@code target/openapi/core-api.v1.json}, the source of
 * {@code contracts/openapi/core-api.v1.json}.
 */
@SpringBootTest(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ApiDocumentationIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void publishesTheOpenApiDocumentForTheVersionedApiOnly() throws Exception {
        String json = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("AI Workforce Platform - Core API"))
                .andExpect(jsonPath("$.paths['/api/v1/health'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/health'].get.security").isEmpty())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/members/{membershipId}/roles'].put")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/roles'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/invitations'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/invitations/accept'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/permissions'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/agents'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/agents/{agentId}/activate'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/agents/{agentId}/providers'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/agents/{agentId}/tools'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/agents/{agentId}/memory-config'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/providers/connections'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/providers/connections/{connectionId}/test'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/providers/connections/{connectionId}/credential'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/skills'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/tools'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/teams/{teamId}/agents'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/employees'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/employees'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/employees/{employeeId}'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/employees/{employeeId}/manager'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/employees/{employeeId}/status'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/employees/{employeeId}/teams'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/departments/tree'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/departments/{departmentId}/parent'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/positions'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/positions/{positionId}'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/teams'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/teams/{teamId}/members'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/organizations/{organizationId}/teams/{teamId}/members/{employeeId}'].delete").exists())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode document = mapper.readTree(json);
        document.get("paths").propertyNames().forEach(path -> assertThat(path).startsWith("/api/"));
        // Credentials are write-only: they appear in requests only, never in a response schema.
        JsonNode schemas = document.get("components").get("schemas");
        assertThat(schemas.get("ProviderConnection").get("properties").propertyNames())
                .noneMatch(name -> name.toLowerCase().contains("credential") || name.toLowerCase().contains("secret")
                        || name.toLowerCase().contains("apikey") || name.toLowerCase().contains("token"));
        assertThat(schemas.get("CreateProviderConnectionRequest").get("properties").get("credential").get("writeOnly").asBoolean())
                .isTrue();
        // No schema exposes invitation secrets.
        assertThat(document.get("components").get("schemas").toString()).doesNotContain("tokenHash");
        assertThat(document.get("components").get("schemas").get("Invitation").get("properties").has("token"))
                .isFalse();

        // Stable output for the published contract: drop the server URL (depends on the test port).
        ((tools.jackson.databind.node.ObjectNode) document).remove("servers");
        Path output = Path.of("target", "openapi", "core-api.v1.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n");
    }

    @Test
    void servesSwaggerUiWhenEnabled() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
