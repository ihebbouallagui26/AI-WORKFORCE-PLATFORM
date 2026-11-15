package com.aiworkforce.core.common.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Security fix 8.7, the pure rule (unit, no Docker). */
class TransportPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"http://idp.example.com/x", "http://localhost:8082/x", "http://127.0.0.1:8082/x", "http://keycloak:8080/x", "ftp://idp.example.com/x"})
    void strictModeRefusesEverythingButHttpsEvenOnLoopback(String url) {
        assertThatThrownBy(() -> TransportPolicy.validate(Map.of("app.x", url), true)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.x");
    }

    @Test
    void strictModeAcceptsHttpsAndLenientModeKeepsTheLocalStackWorking() {
        assertThatCode(() -> TransportPolicy.validate(Map.of("app.x", "https://idp.example.com/realms/x"), true)).doesNotThrowAnyException();
        assertThatCode(() -> TransportPolicy.validate(Map.of("app.x", "http://localhost:8082/x"), false)).doesNotThrowAnyException();
    }

    @Test
    void credentialsInAUrlAreRefusedInEveryModeAndNeverEchoed() {
        for (boolean strict : new boolean[] {true, false}) {
            assertThatThrownBy(() -> TransportPolicy.validate(Map.of("app.x", "https://user:pw@idp.example.com/certs"), strict))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("credentials")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("pw"));
        }
    }

    @Test
    void relativeAndMalformedUrlsAreRefused() {
        assertThatThrownBy(() -> TransportPolicy.validate(Map.of("app.x", "/relative"), false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TransportPolicy.validate(Map.of("app.x", "http://bad host"), false)).isInstanceOf(IllegalStateException.class);
    }
}
