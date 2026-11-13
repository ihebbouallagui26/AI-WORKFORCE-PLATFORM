package com.aiworkforce.core.auth;

import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * OpenID Connect resource-server settings ({@code app.security.oidc.*}).
 *
 * @param issuer     exact {@code iss} value of accepted tokens (public issuer URL of the realm)
 * @param jwkSetUri  JWKS endpoint used to verify signatures (may be an internal URL)
 * @param audience   value that must be present in the {@code aud} claim (the API itself)
 */
@Validated
@ConfigurationProperties(prefix = "app.security.oidc")
public record OidcProperties(@NotBlank String issuer, @NotBlank String jwkSetUri, @NotBlank String audience) {

    public OidcProperties {
        requireHttpUrl("issuer", issuer);
        requireHttpUrl("jwk-set-uri", jwkSetUri);
    }

    private static void requireHttpUrl(String name, String value) {
        if (value == null || value.isBlank()) {
            return; // reported by @NotBlank
        }
        URI uri = URI.create(value);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalArgumentException("app.security.oidc." + name + " must be an absolute http(s) URL");
        }
    }
}
