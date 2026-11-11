package com.aiworkforce.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description of the public API. Only {@code /api/**} is documented
 * ({@code springdoc.paths-to-match}); the generated document is published in
 * {@code contracts/openapi/core-api.v1.json}.
 *
 * <p>The bearer security scheme is added in Sprint B1, together with authentication.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI coreApiOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        return new OpenAPI().info(new Info()
                .title("AI Workforce Platform - Core API")
                .version(build == null ? "unknown" : build.getVersion())
                .description("""
                        Core backend of the AI Workforce Platform. All business endpoints are versioned \
                        under /api/v1 and require authentication. Errors use RFC 9457 problem details \
                        (application/problem+json). Timestamps are UTC, ISO 8601."""));
    }
}
