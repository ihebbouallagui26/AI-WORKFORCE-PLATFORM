package com.aiworkforce.core.auth;

import com.aiworkforce.core.common.net.TransportPolicy;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Security fix 8.7: the issuer and the JWKS endpoint decide which tokens are accepted; fetched over plain HTTP they could be swapped by anyone
 * on the path. Outside the explicit {@code dev}/{@code test} profiles (the base configuration is production-safe) startup fails unless both
 * are credential-free HTTPS. The default TLS verification of the JVM (chain, expiry, host name) is never weakened by the application.
 */
@Component
class OidcTransportValidator {

    private final OidcProperties oidc;
    private final Environment environment;

    OidcTransportValidator(OidcProperties oidc, Environment environment) {
        this.oidc = oidc;
        this.environment = environment;
    }

    @PostConstruct
    void validateAtStartup() {
        Map<String, String> urls = new LinkedHashMap<>();
        urls.put("app.security.oidc.issuer", oidc.issuer());
        urls.put("app.security.oidc.jwk-set-uri", oidc.jwkSetUri());
        TransportPolicy.validate(urls, !environment.acceptsProfiles(Profiles.of("dev", "test")));
    }
}
