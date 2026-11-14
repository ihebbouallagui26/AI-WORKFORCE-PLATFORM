package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.ToolCallCoordinator.BeginResult;
import com.aiworkforce.core.agent.ToolCallCoordinator.ResumeResult;
import com.aiworkforce.core.agent.ToolCallCoordinator.ToolCallDecision;
import com.aiworkforce.core.common.exception.ConflictException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import tools.jackson.databind.JsonNode;

/**
 * Orchestrator-only tool endpoints ({@code /api/v1/internal/**}, service account; never a user token).
 * The Core API decides, records and audits; the orchestrator only executes what it is told is authorized.
 */
@RestController
@Validated
@RequestMapping(path = "/api/v1/internal/organizations/{organizationId}/executions/{executionId}",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Internal (orchestrator)", description = "Service account only. Not for the desktop application.")
public class InternalToolCallController {

    @Schema(name = "ToolCallRequest")
    public record ToolCallRequest(
            @NotBlank @Size(max = 100) String toolCallId,
            @NotBlank @Size(max = 80) String toolCode,
            @Schema(type = "object", description = "The tool arguments as a JSON object") JsonNode arguments,
            @Size(max = 1000) String reason) {
    }

    @Schema(name = "ToolCallDecision")
    public record DecisionResponse(UUID invocationId, String decision, String code, String message, UUID approvalId,
            java.time.Instant expiresAt) {
    }

    /** The arguments leave the Core API here only, to the service that runs the call. {@code toString} is redacted. */
    @Schema(name = "ToolCallStart", description = "Single use: the call starts exactly once. Served with no-store.")
    public record BeginResponse(UUID invocationId, String toolCode, String mcpServer, String mcpTool,
            @Schema(type = "object", description = "The exact arguments to run, returned once") JsonNode arguments,
            int timeoutSeconds, int maxOutputBytes) {

        @Override
        public String toString() {
            return "BeginResponse[invocationId=" + invocationId + ", toolCode=" + toolCode + ", arguments=<redacted>]";
        }
    }

    @Schema(name = "ToolCallResult")
    public record CompleteRequest(@NotNull Boolean success, @Size(max = 200_000) String output, Boolean truncated,
            @Size(max = 40) String errorCode, @Size(max = 1000) String message) {
    }

    @Schema(name = "ToolCallOutcome")
    public record Outcome(UUID invocationId, InvocationStatus status) {
    }

    @Schema(name = "ExecutionResume")
    public record ResumeResponse(UUID invocationId, UUID approvalId, String decision, String toolCode, String rejectionReason) {
    }

    private final ToolCallCoordinator tools;

    public InternalToolCallController(ToolCallCoordinator tools) {
        this.tools = tools;
    }

    @PostMapping(path = "/tool-calls", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "requestToolCall",
            summary = "The agent asks for a tool: the Core API decides (DENIED, AUTO or APPROVAL_REQUIRED) and records it")
    public DecisionResponse request(@PathVariable UUID organizationId, @PathVariable UUID executionId,
            @Valid @RequestBody ToolCallRequest request) {
        ToolCallDecision decision = tools.request(organizationId, executionId, request.toolCallId(), request.toolCode(),
                request.arguments(), request.reason());
        return new DecisionResponse(decision.invocationId(), decision.decision(), decision.code(), decision.message(),
                decision.approvalId(), decision.expiresAt());
    }

    @PostMapping("/tool-calls/{invocationId}/begin")
    @Operation(operationId = "beginToolCall",
            summary = "Start an authorized call (revalidated now); returns the arguments once, no-store")
    public ResponseEntity<BeginResponse> begin(@PathVariable UUID organizationId, @PathVariable UUID executionId,
            @PathVariable UUID invocationId) {
        BeginResult result = tools.begin(organizationId, executionId, invocationId);
        if (result == null) {
            throw new ConflictException("The tool call is no longer authorized and was closed.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new BeginResponse(result.invocationId(), result.toolCode(), result.mcpServer(), result.mcpTool(),
                        parse(result.arguments()), result.timeoutSeconds(), result.maxOutputBytes()));
    }

    private static JsonNode parse(String json) {
        return ToolArguments.parse(json);
    }

    @PostMapping(path = "/tool-calls/{invocationId}/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "completeToolCall", summary = "Report the (bounded) result or the failure of a running call")
    public Outcome complete(@PathVariable UUID organizationId, @PathVariable UUID executionId,
            @PathVariable UUID invocationId, @Valid @RequestBody CompleteRequest request) {
        InvocationStatus status = tools.complete(organizationId, executionId, invocationId, request.success(),
                request.output(), Boolean.TRUE.equals(request.truncated()), request.errorCode(), request.message());
        return new Outcome(invocationId, status);
    }

    @PostMapping("/resume")
    @Operation(operationId = "resumeExecution",
            summary = "Continue a paused execution after the human decision (once per pause; acknowledges the notification)")
    public ResumeResponse resume(@PathVariable UUID organizationId, @PathVariable UUID executionId) {
        ResumeResult result = tools.resume(organizationId, executionId);
        return new ResumeResponse(result.invocationId(), result.approvalId(), result.decision(), result.toolCode(),
                result.rejectionReason());
    }
}
