package com.aiworkforce.core.auth;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Refuses to start with an unsafe Core API to orchestrator configuration.
 *
 * <p>The Core API sends its Keycloak service token to the orchestrator and to the token endpoint: over
 * plain HTTP that token (and the client secret posted to Keycloak) would be exposed. The base
 * configuration is production-safe (ADR-0006), so HTTPS is required unless the {@code dev} or
 * {@code test} profile is explicitly active; there plain HTTP stays allowed because the local Docker
 * stack talks over a private network (for example {@code http://ai-orchestrator:8091}).
 *
 * <p>Dispatching is optional: with every setting blank, or (in dev/test only) with a missing secret or
 * URL, the feature is disabled and executions fail safely with {@code ORCHESTRATOR_UNAVAILABLE}. Outside
 * dev/test a partial configuration is an error. Every URL that is present must be well formed.
 */
@Component
class OrchestratorDispatchValidator {

    private final OrchestratorProperties properties;
    private final Environment environment;

    OrchestratorDispatchValidator(OrchestratorProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void validateAtStartup() {
        validate(properties.dispatch(), !environment.acceptsProfiles(Profiles.of("dev", "test")));
    }

    /**
     * @param strict true outside the {@code dev} and {@code test} profiles (production): HTTPS only, no
     *               loopback exception
     * @throws IllegalStateException when the configuration is partial, malformed or insecure
     */
    static void validate(OrchestratorProperties.Dispatch dispatch, boolean strict) {
        // The token URL has a derived default (same realm as OIDC_ISSUER): only the orchestrator URL and the
        // secret express the intention to dispatch.
        boolean requested = notBlank(dispatch.baseUrl()) || notBlank(dispatch.clientSecret());
        if (!requested) {
            return; // dispatching disabled
        }
        if (strict && !dispatch.configured()) {
            throw new IllegalStateException(
                    "app.orchestrator.dispatch is partially configured: base-url, token-url and client-secret are all required");
        }
        if (notBlank(dispatch.baseUrl())) {
            checkUrl("app.orchestrator.dispatch.base-url", dispatch.baseUrl(), strict);
        }
        if (notBlank(dispatch.tokenUrl())) {
            checkUrl("app.orchestrator.dispatch.token-url", dispatch.tokenUrl(), strict);
        }
    }

    private static void checkUrl(String property, String value, boolean strict) {
        URI uri;
        try {
            uri = new URI(value.strip());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(property + " is not a valid URL");
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || scheme == null || !(scheme.equals("https") || scheme.equals("http"))) {
            throw new IllegalStateException(property + " must be an absolute http(s) URL");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalStateException(property + " must not contain credentials");
        }
        if (strict && !scheme.equals("https")) {
            throw new IllegalStateException(property + " must use HTTPS outside the dev and test profiles");
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
