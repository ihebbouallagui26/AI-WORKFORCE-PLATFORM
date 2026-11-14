package com.aiworkforce.core.agent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoint for the AI orchestrator (Phase C). Reachable only with the orchestrator's
 * service-account token (see {@code SecurityConfig}); never with a user token. Read-only.
 */
@RestController
@RequestMapping(path = "/api/v1/internal/organizations/{organizationId}/agents/{agentId}",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Internal (orchestrator)", description = "Service account only. Not for the desktop application.")
public class InternalExecutionController {

    private final ExecutionContextReader reader;

    public InternalExecutionController(ExecutionContextReader reader) {
        this.reader = reader;
    }

    @GetMapping("/execution-context")
    @Operation(operationId = "getAgentExecutionContext",
            summary = "Authorized configuration of an ACTIVE agent for execution (no secrets)")
    public ExecutionContextView executionContext(@PathVariable UUID organizationId, @PathVariable UUID agentId) {
        return reader.read(organizationId, agentId);
    }
}
