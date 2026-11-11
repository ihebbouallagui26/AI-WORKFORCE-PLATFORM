package com.aiworkforce.core.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application-specific configuration, bound from the {@code app.*} namespace.
 * Invalid values fail the application startup instead of silently weakening security.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@Valid @NotNull Cors cors) {

    /**
     * Browser origins allowed to call the API.
     *
     * @param allowedOrigins exact origins ({@code scheme://host[:port]}), never wildcards
     */
    public record Cors(@NotEmpty List<String> allowedOrigins) {

        /** Schemes accepted for an origin: web development servers and the Electron app scheme. */
        private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "app");

        public Cors {
            allowedOrigins = allowedOrigins == null
                    ? List.of()
                    : allowedOrigins.stream().map(String::trim).filter(origin -> !origin.isEmpty()).toList();
            allowedOrigins.forEach(Cors::requireExactOrigin);
        }

        private static void requireExactOrigin(String origin) {
            if (origin.contains("*")) {
                throw new IllegalArgumentException("Wildcard CORS origins are not allowed: " + origin);
            }
            final URI uri;
            try {
                uri = new URI(origin);
            } catch (URISyntaxException ex) {
                throw new IllegalArgumentException("Invalid CORS origin: " + origin, ex);
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean hasPath = uri.getRawPath() != null && !uri.getRawPath().isEmpty();
            if (!ALLOWED_SCHEMES.contains(scheme)
                    || uri.getHost() == null
                    || hasPath
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException(
                        "CORS origins must be exact origins such as https://app.example.com (got: " + origin + ")");
            }
        }
    }
}
