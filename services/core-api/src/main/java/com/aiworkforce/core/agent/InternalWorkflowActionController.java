package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.WorkflowActionService.ActionResult;
import tools.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service account only: the orchestrator forwards a coordinator platform action; the Core API decides. */
@RestController
@Validated
@RequestMapping(path = "/api/v1/internal/organizations/{organizationId}/executions/{executionId}/workflow-actions",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Internal (orchestrator)", description = "Service account only. Not for the desktop application.")
public class InternalWorkflowActionController {

    @Schema(name = "WorkflowActionRequest")
    public record ActionRequest(@NotBlank @Size(max = 100) String toolCallId, @NotBlank @Size(max = 40) String action,
            @Schema(type = "object", description = "The arguments exactly as the provider produced them") JsonNode arguments) {
    }

    @Schema(name = "WorkflowActionResult")
    public record ActionResponse(String action, String status, String code, String message) {
    }

    private final WorkflowActionService service;

    public InternalWorkflowActionController(WorkflowActionService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "submitWorkflowAction", summary = "Validate and record a coordinator workflow.delegate or workflow.finish")
    public ActionResponse submit(@PathVariable UUID organizationId, @PathVariable UUID executionId,
            @Valid @RequestBody ActionRequest request) {
        ActionResult result = service.submit(organizationId, executionId, request.toolCallId(), request.action(), request.arguments());
        return new ActionResponse(result.action(), result.status(), result.code(), result.message());
    }
}
