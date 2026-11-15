package com.aiworkforce.core.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/** Security fix 8.7: the OIDC issuer and JWKS must be HTTPS unless the profile is explicitly dev/test (unit, no Docker). */
class OidcTransportValidatorTest {

    private static final String OK = "https://idp.example.com/realms/x";

    private static void start(String issuer, String jwks, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        new OidcTransportValidator(new OidcProperties(issuer, jwks, "ai-workforce-api"), environment).validateAtStartup();
    }

    @Test
    void httpsIsAcceptedInProductionAndWithoutAnyProfile() {
        assertThatCode(() -> start(OK, OK + "/certs", "prod")).doesNotThrowAnyException();
        assertThatCode(() -> start(OK, OK + "/certs")).doesNotThrowAnyException();   // the base configuration is production-safe
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://idp.example.com/realms/x", "http://localhost:8082/realms/x", "http://127.0.0.1:8082/realms/x", "http://keycloak:8080/realms/x"})
    void aPlainHttpJwksEndpointIsRefusedEvenOnLoopback(String jwks) {
        assertThatThrownBy(() -> start(OK, jwks, "prod")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwk-set-uri").hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> start(OK, jwks)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPlainHttpIssuerIsRefusedInProduction() {
        assertThatThrownBy(() -> start("http://idp.example.com/realms/x", OK + "/certs", "prod")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer");
    }

    @Test
    void developmentAndTestProfilesKeepTheLocalHttpStackWorking() {
        for (String profile : new String[] {"dev", "test"}) {
            assertThatCode(() -> start("http://localhost:8082/realms/x", "http://keycloak:8080/realms/x/certs", profile)).doesNotThrowAnyException();
        }
    }
}
