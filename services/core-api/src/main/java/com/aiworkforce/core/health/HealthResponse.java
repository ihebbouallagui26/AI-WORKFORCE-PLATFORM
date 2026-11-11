package com.aiworkforce.core.health;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Public application health. Deliberately minimal: no component details, hostnames,
 * database versions or configuration values.
 */
@Schema(name = "HealthResponse", description = "Overall application health.")
public record HealthResponse(
        @Schema(description = "Aggregated status of the application and its dependencies.",
                example = "UP", allowableValues = {"UP", "DOWN", "OUT_OF_SERVICE", "UNKNOWN"})
        String status,
        @Schema(description = "Service name.", example = "core-api")
        String service,
        @Schema(description = "Application version, used by clients to check compatibility.", example = "0.1.0")
        String version,
        @Schema(description = "Time of the check (UTC, ISO 8601).", example = "2026-11-11T10:15:30Z")
        Instant timestamp) {
}
