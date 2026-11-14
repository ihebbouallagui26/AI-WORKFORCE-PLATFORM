package com.aiworkforce.core.provider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Fixed endpoints of the supported AI providers ({@code app.providers.*}). They are server-side
 * configuration, never user input: no API request can make the server call another URL (SSRF).
 * HTTPS is required; plain HTTP is accepted only towards a loopback address (local fake servers in tests).
 */
@Validated
@ConfigurationProperties("app.providers")
public record ProviderProperties(
        @Valid @NotNull Endpoint anthropic,
        @Valid @NotNull Endpoint openai,
        @Valid @NotNull Endpoint gemini,
        @NotNull Duration timeout) {

    public ProviderProperties {
        if (timeout != null && (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60)) > 0)) {
            throw new IllegalArgumentException("app.providers.timeout must be between 1 ms and 60 s");
        }
    }

    /** Base URL of one provider's API. */
    public record Endpoint(@NotNull URI baseUrl) {

        public Endpoint {
            if (baseUrl != null) {
                String scheme = baseUrl.getScheme();
                String host = baseUrl.getHost();
                boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
                boolean secure = "https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme) && loopback);
                if (!secure || host == null || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
                        || baseUrl.getFragment() != null) {
                    throw new IllegalArgumentException(
                            "A provider base URL must be HTTPS (HTTP only for loopback) without credentials or query");
                }
            }
        }
    }
}
