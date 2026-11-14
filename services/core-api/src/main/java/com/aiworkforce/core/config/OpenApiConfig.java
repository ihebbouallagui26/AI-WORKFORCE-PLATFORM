package com.aiworkforce.core.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description of the public API. Only {@code /api/**} is documented
 * ({@code springdoc.paths-to-match}); the generated document is published in
 * {@code contracts/openapi/core-api.v1.json}.
 *
 * <p>Every operation requires an OAuth 2.0 bearer access token (JWT issued by Keycloak for the
 * audience {@code ai-workforce-api}), except {@code GET /api/v1/health}.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI coreApiOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        return new OpenAPI()
                .info(new Info()
                        .title("AI Workforce Platform - Core API")
                        .version(build == null ? "unknown" : build.getVersion())
                        .description("""
                                Core backend of the AI Workforce Platform. All business endpoints are versioned \
                                under /api/v1 and require an OAuth 2.0 bearer access token. Organization-scoped \
                                endpoints answer 404 when the caller is not an active member of the organization \
                                and 403 when a permission is missing. Errors use RFC 9457 problem details \
                                (application/problem+json). Timestamps are UTC, ISO 8601."""))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token issued by the identity provider (Authorization Code + PKCE).")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
