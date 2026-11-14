package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.ExecutionCoordinator.CredentialGrant;
import com.aiworkforce.core.agent.ExecutionCoordinator.StartedExecution;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Orchestrator-only endpoints that drive an execution (service account, {@code /api/v1/internal/**};
 * never reachable with a user token). The orchestrator never writes business tables: it asks the
 * Core API to move the execution through its state machine, one guarded transition per call.
 */
@RestController
@Validated
@RequestMapping(path = "/api/v1/internal/organizations/{organizationId}/executions/{executionId}",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Internal (orchestrator)", description = "Service account only. Not for the desktop application.")
public class InternalExecutionRunController {

    @Schema(name = "ExecutionStarted")
    public record Started(UUID executionId, UUID agentId, String task) {

        static Started of(StartedExecution started) {
            return new Started(started.executionId(), started.agentId(), started.task());
        }
    }

    /** The one place a decrypted credential leaves the Core API. Its {@code toString} is redacted. */
    @Schema(name = "ExecutionCredential", description = "Single use, only while RUNNING. Served with no-store.")
    public record CredentialResponse(UUID connectionId, String providerType, String modelId, String credential) {

        @Override
        public String toString() {
            return "CredentialResponse[connectionId=" + connectionId + ", providerType=" + providerType
                    + ", modelId=" + modelId + ", credential=<redacted>]";
        }
    }

    @Schema(name = "CompleteExecutionRequest")
    public record CompleteRequest(
            @NotBlank @Size(max = 200_000) String result,
            @Min(0) Integer inputTokens,
            @Min(0) Integer outputTokens,
            @Size(max = 128) String model) {
    }

    @Schema(name = "FailExecutionRequest")
    public record FailRequest(@NotBlank @Size(max = 40) String errorCode, @Size(max = 1000) String message) {
    }

    private final ExecutionCoordinator coordinator;

    public InternalExecutionRunController(ExecutionCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping("/start")
    @Operation(operationId = "startExecutionRun", summary = "PENDING to RUNNING; returns the instruction and agent")
    public Started start(@PathVariable UUID organizationId, @PathVariable UUID executionId) {
        return Started.of(coordinator.start(organizationId, executionId));
    }

    @PostMapping("/credential")
    @Operation(operationId = "issueExecutionCredential",
            summary = "Provider credential for this execution (single use, RUNNING only, no-store)")
    public ResponseEntity<CredentialResponse> credential(@PathVariable UUID organizationId,
            @PathVariable UUID executionId) {
        CredentialGrant grant = coordinator.resolveCredential(organizationId, executionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CredentialResponse(grant.connectionId(), grant.providerType(), grant.modelId(),
                        grant.credential()));
    }

    @PostMapping(path = "/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "completeExecutionRun", summary = "RUNNING to SUCCEEDED with the result and usage")
    public ExecutionView complete(@PathVariable UUID organizationId, @PathVariable UUID executionId,
            @Valid @RequestBody CompleteRequest request) {
        return coordinator.complete(organizationId, executionId, request.result(), request.inputTokens(),
                request.outputTokens(), request.model());
    }

    @PostMapping(path = "/fail", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "failExecutionRun", summary = "Fail the execution with a safe error code and message")
    public ExecutionView fail(@PathVariable UUID organizationId, @PathVariable UUID executionId,
            @Valid @RequestBody FailRequest request) {
        return coordinator.fail(organizationId, executionId, request.errorCode(), request.message());
    }
}
