package com.aiworkforce.core.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public application health for clients (desktop app, load balancers). */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Health", description = "Application availability")
public class HealthController {

    private final ApplicationHealthService healthService;

    public HealthController(ApplicationHealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    @SecurityRequirements // Public endpoint: no authentication.
    @Operation(summary = "Get application health",
            description = "Returns UP when the application and its database are available.")
    @ApiResponse(responseCode = "200", description = "Application is healthy",
            content = @Content(schema = @Schema(implementation = HealthResponse.class)))
    @ApiResponse(responseCode = "503", description = "Application or one of its dependencies is unavailable",
            content = @Content(schema = @Schema(implementation = HealthResponse.class)))
    public ResponseEntity<HealthResponse> health() {
        HealthResponse health = healthService.currentHealth();
        HttpStatus status = "UP".equals(health.status()) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(health);
    }
}
