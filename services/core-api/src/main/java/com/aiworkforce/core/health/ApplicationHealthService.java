package com.aiworkforce.core.health;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

/**
 * Derives the public health status from Spring Boot Actuator, which aggregates every
 * registered health indicator (including the PostgreSQL connection).
 */
@Service
public class ApplicationHealthService {

    private static final String UNKNOWN_VERSION = "unknown";

    private final Supplier<String> statusSupplier;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final Clock clock;
    private final String serviceName;

    @Autowired
    public ApplicationHealthService(
            HealthEndpoint healthEndpoint,
            ObjectProvider<BuildProperties> buildProperties,
            Clock clock,
            @Value("${spring.application.name}") String serviceName) {
        this(() -> healthEndpoint.health().getStatus().getCode(), buildProperties, clock, serviceName);
    }

    /** Status source injectable for unit tests (Actuator's health descriptors are sealed types). */
    ApplicationHealthService(
            Supplier<String> statusSupplier,
            ObjectProvider<BuildProperties> buildProperties,
            Clock clock,
            String serviceName) {
        this.statusSupplier = statusSupplier;
        this.buildProperties = buildProperties;
        this.clock = clock;
        this.serviceName = serviceName;
    }

    public HealthResponse currentHealth() {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build == null || build.getVersion() == null ? UNKNOWN_VERSION : build.getVersion();
        return new HealthResponse(statusSupplier.get(), serviceName, version, Instant.now(clock));
    }
}
