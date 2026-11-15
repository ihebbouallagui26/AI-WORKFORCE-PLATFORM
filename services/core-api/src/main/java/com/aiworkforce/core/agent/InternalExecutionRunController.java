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
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
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
    public record Started(UUID executionId, UUID agentId, String task,
            @Schema(description = "COORDINATOR when this execution is a workflow coordinator turn, otherwise null") String workflowRole,
            @Schema(description = "Platform-native actions offered ONLY to a coordinator turn (never MCP tools)") List<PlatformAction> platformActions,
            @Schema(description = "Seconds left (step or workflow deadline) when the execution belongs to a workflow; the orchestrator must not run past it") Long deadlineSeconds,
            @Schema(description = "Coordinator turns only: how many workflow.delegate calls this turn may make (1 = one task at a time)") Integer maxDelegationsPerTurn) {

        static Started of(StartedExecution started, boolean coordinatorTurn, Long deadlineSeconds, int delegationLimit) {
            return new Started(started.executionId(), started.agentId(), started.task(), coordinatorTurn ? "COORDINATOR" : null,
                    coordinatorTurn ? WorkflowActionCatalog.actions() : List.of(), deadlineSeconds, coordinatorTurn ? delegationLimit : null);
        }
    }

    @Schema(name = "PlatformAction")
    public record PlatformAction(String name, String description, @Schema(type = "object") JsonNode inputSchema) {
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
    public record FailRequest(@NotBlank @Size(max = 40) String errorCode, @Size(max = 1000) String message,
            @Min(0) Integer inputTokens, @Min(0) Integer outputTokens) {
    }

    private final ExecutionCoordinator coordinator;

    private final WorkflowActionService workflowActions;

    public InternalExecutionRunController(ExecutionCoordinator coordinator, WorkflowActionService workflowActions) {
        this.coordinator = coordinator;
        this.workflowActions = workflowActions;
    }

    @PostMapping("/start")
    @Operation(operationId = "startExecutionRun", summary = "PENDING to RUNNING; returns the instruction and agent")
    public Started start(@PathVariable UUID organizationId, @PathVariable UUID executionId) {
        StartedExecution started = coordinator.start(organizationId, executionId);
        return Started.of(started, workflowActions.isCoordinatorTurn(organizationId, executionId),
                workflowActions.remainingSeconds(organizationId, executionId), workflowActions.delegationLimit(organizationId, executionId));
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
        return coordinator.fail(organizationId, executionId, request.errorCode(), request.message(), request.inputTokens(), request.outputTokens());
    }
}
