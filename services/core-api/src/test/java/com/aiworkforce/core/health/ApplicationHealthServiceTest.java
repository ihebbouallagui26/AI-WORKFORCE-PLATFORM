package com.aiworkforce.core.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;

class ApplicationHealthServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:15:30Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void reportsUpWithVersionAndUtcTimestamp() {
        ApplicationHealthService service = service(Status.UP, build("0.1.0"));

        HealthResponse health = service.currentHealth();

        assertThat(health).isEqualTo(new HealthResponse("UP", "core-api", "0.1.0", NOW));
        assertThat(new HealthController(service).health().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void reportsDownAsServiceUnavailable() {
        ApplicationHealthService service = service(Status.DOWN, build("0.1.0"));

        assertThat(service.currentHealth().status()).isEqualTo("DOWN");
        assertThat(new HealthController(service).health().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void reportsAnUnknownVersionWhenBuildInformationIsMissing() {
        assertThat(service(Status.UP, null).currentHealth().version()).isEqualTo("unknown");
    }

    @SuppressWarnings("unchecked")
    private ApplicationHealthService service(Status status, BuildProperties build) {
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(build);
        return new ApplicationHealthService(status::getCode, provider, clock, "core-api");
    }

    private static BuildProperties build(String version) {
        Properties properties = new Properties();
        properties.setProperty("version", version);
        return new BuildProperties(properties);
    }
}
