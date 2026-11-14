package com.aiworkforce.core.provider;

import jakarta.validation.constraints.NotBlank;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Master key of the development secret store ({@code app.secrets.master-key}, environment variable
 * {@code SECRETS_MASTER_KEY}): 32 random bytes, base64 encoded. It has no default and is never
 * committed: the application refuses to start without a valid key. Generate one with
 * {@code openssl rand -base64 32}.
 */
@Validated
@ConfigurationProperties("app.secrets")
public record SecretsProperties(@NotBlank String masterKey) {

    public SecretsProperties {
        if (masterKey != null && !masterKey.isBlank()) {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(masterKey.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("app.secrets.master-key must be base64 encoded");
            }
            if (decoded.length != 32) {
                throw new IllegalArgumentException("app.secrets.master-key must decode to exactly 32 bytes (AES-256)");
            }
        }
    }

    /** Never prints the key. */
    @Override
    public String toString() {
        return "SecretsProperties[masterKey=<redacted>]";
    }
}
