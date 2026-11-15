package com.aiworkforce.core.provider;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/** Security fix 8.7: provider endpoints (they receive the decrypted credential) must be HTTPS unless the profile is dev/test (unit). */
class ProviderTransportValidatorTest {

    private static ProviderProperties providers(String anthropic) {
        return new ProviderProperties(new ProviderProperties.Endpoint(URI.create(anthropic)),
                new ProviderProperties.Endpoint(URI.create("https://api.openai.com")),
                new ProviderProperties.Endpoint(URI.create("https://generativelanguage.googleapis.com")), Duration.ofSeconds(10));
    }

    private static void start(ProviderProperties providers, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        new ProviderTransportValidator(providers, environment).validateAtStartup();
    }

    @Test
    void httpsEndpointsAreAcceptedInProductionAndWithoutAnyProfile() {
        assertThatCode(() -> start(providers("https://api.anthropic.com"), "prod")).doesNotThrowAnyException();
        assertThatCode(() -> start(providers("https://api.anthropic.com"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:9911", "http://127.0.0.1:9911"})
    void aLoopbackEndpointInPlainHttpIsRefusedInProduction(String endpoint) {
        assertThatThrownBy(() -> start(providers(endpoint), "prod")).isInstanceOf(IllegalStateException.class).hasMessageContaining("anthropic");
    }

    @Test
    void aNonLoopbackPlainHttpEndpointCannotEvenBeBound() {
        assertThatThrownBy(() -> providers("http://api.anthropic.com")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void developmentAndTestProfilesKeepTheLoopbackFakeWorking() {
        for (String profile : new String[] {"dev", "test"}) {
            assertThatCode(() -> start(providers("http://127.0.0.1:9911"), profile)).doesNotThrowAnyException();
        }
    }
}
