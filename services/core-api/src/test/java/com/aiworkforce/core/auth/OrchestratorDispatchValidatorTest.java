package com.aiworkforce.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiworkforce.core.auth.OrchestratorProperties.Dispatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/** The Core API refuses to start with an unsafe orchestrator configuration (no Docker needed). */
class OrchestratorDispatchValidatorTest {

    private static Dispatch dispatch(String baseUrl, String tokenUrl, String secret) {
        return new Dispatch(baseUrl, tokenUrl, null, secret, null);
    }

    private static void validate(Dispatch dispatch, String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        new OrchestratorDispatchValidator(new OrchestratorProperties("ai-orchestrator", dispatch), environment)
                .validateAtStartup();
    }

    @Test
    void httpsIsAcceptedInProduction() {
        assertThatCode(() -> validate(dispatch("https://orchestrator.internal.example", "https://idp.example/token", "s3cret"), "prod"))
                .doesNotThrowAnyException();
        // No profile at all is production: the base configuration is production-safe.
        assertThatCode(() -> validate(dispatch("https://orchestrator.internal.example", "https://idp.example/token", "s3cret")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://orchestrator.internal.example", "http://localhost:8091", "http://127.0.0.1:8091", "http://ai-orchestrator:8091"})
    void httpIsRejectedInProductionEvenOnLoopback(String baseUrl) {
        assertThatThrownBy(() -> validate(dispatch(baseUrl, "https://idp.example/token", "s3cret"), "prod"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("base-url").hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> validate(dispatch(baseUrl, "https://idp.example/token", "s3cret")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anInsecureTokenEndpointIsRejectedInProduction() {
        assertThatThrownBy(() -> validate(dispatch("https://orchestrator.example", "http://idp.example/token", "s3cret"), "prod"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("token-url");
    }

    @Test
    void httpIsAllowedInDevelopmentAndTestIncludingTheLocalComposeStack() {
        for (String profile : new String[] {"dev", "test"}) {
            assertThatCode(() -> validate(dispatch("http://127.0.0.1:8091", "http://localhost:8082/token", "s3cret"), profile))
                    .doesNotThrowAnyException();
            // The local Docker stack (private network, not loopback) keeps working.
            assertThatCode(() -> validate(dispatch("http://ai-orchestrator:8091", "http://keycloak:8080/token", "s3cret"), profile))
                    .doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a url", "ftp://orchestrator.example", "//orchestrator.example", "https://", "orchestrator.example:8091",
            "https://user:pw@orchestrator.example", "file:///etc/passwd"})
    void invalidOrUnsafeUrlsAreRejectedInEveryProfile(String baseUrl) {
        for (String profile : new String[] {"prod", "dev", "test"}) {
            assertThatThrownBy(() -> validate(dispatch(baseUrl, "https://idp.example/token", "s3cret"), profile))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("base-url");
        }
    }

    @Test
    void aPartialConfigurationIsAnErrorInProductionAndSafelyDisabledInDevelopment() {
        assertThatThrownBy(() -> validate(dispatch("https://orchestrator.example", "https://idp.example/token", ""), "prod"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("partially configured");
        assertThatThrownBy(() -> validate(dispatch("", "https://idp.example/token", "s3cret")))
                .isInstanceOf(IllegalStateException.class);
        // Local development keeps starting (dispatching is just unavailable until the secret is provisioned).
        for (String profile : new String[] {"dev", "test"}) {
            assertThatCode(() -> validate(dispatch("http://localhost:8091", "http://localhost:8082/token", ""), profile)).doesNotThrowAnyException();
            assertThatCode(() -> validate(dispatch("http://localhost:8091", null, "s3cret"), profile)).doesNotThrowAnyException();
        }
        assertThat(dispatch("http://localhost:8091", null, "s3cret").configured()).isFalse();
    }

    @Test
    void aDerivedTokenUrlAloneDoesNotCountAsAConfigurationAttempt() {
        // application.yml derives the token URL from OIDC_ISSUER: production without an orchestrator must still start.
        assertThatCode(() -> validate(dispatch(null, "https://idp.example/realms/x/protocol/openid-connect/token", null), "prod"))
                .doesNotThrowAnyException();
    }

    @Test
    void anAbsentConfigurationDisablesDispatchingWithoutError() {
        assertThatCode(() -> validate(dispatch(null, null, null), "prod")).doesNotThrowAnyException();
        assertThatCode(() -> validate(dispatch("", " ", ""), "dev")).doesNotThrowAnyException();
    }

    @Test
    void theSecretNeverAppearsInTheDescriptionOfTheConfiguration() {
        assertThat(dispatch("https://o.example", "https://i.example/t", "very-secret-value").toString())
                .doesNotContain("very-secret-value");
    }
}
