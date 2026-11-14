package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.WorkflowViews.DefinitionView;
import com.aiworkforce.core.common.web.PageParams;
import com.aiworkforce.core.common.web.PageResponse;
import com.aiworkforce.core.identity.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Multi-agent workflow definitions: explicit participants, ordered static steps, limits. */
@RestController
@Validated
@RequestMapping(path = "/api/v1/organizations/{organizationId}/workflows", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Workflows", description = "Deterministic multi-agent pipelines: definitions")
public class WorkflowController {

    @Schema(name = "CreateWorkflowRequest")
    public record CreateRequest(@NotBlank @Size(max = 120) String name, @Size(max = 2000) String description,
            @Min(1) @Max(8) Integer maxSteps, @Min(0) @Max(2) Integer maxRetriesPerStep,
            @Min(1000) @Max(10_000_000) Integer tokenBudget, @Min(30) @Max(86_400) Integer deadlineSeconds) {
    }

    @Schema(name = "UpdateWorkflowRequest")
    public record UpdateRequest(@Size(min = 1, max = 120) String name, @Size(max = 2000) String description,
            @Min(1) @Max(8) Integer maxSteps, @Min(0) @Max(2) Integer maxRetriesPerStep,
            @Min(1000) @Max(10_000_000) Integer tokenBudget, @Min(30) @Max(86_400) Integer deadlineSeconds) {
    }

    @Schema(name = "SetWorkflowParticipantsRequest")
    public record ParticipantsRequest(@Size(max = 16) List<UUID> agentIds) {
    }

    @Schema(name = "WorkflowStepInput")
    public record StepRequest(@NotBlank @Size(max = 120) String name, UUID agentId, @NotBlank @Size(max = 2000) String instruction,
            @Size(max = 7) List<Integer> inputsFrom) {
    }

    @Schema(name = "SetWorkflowStepsRequest")
    public record StepsRequest(@Size(min = 1, max = 8) @Valid List<StepRequest> steps) {
    }

    private final WorkflowDefinitionService workflows;

    public WorkflowController(WorkflowDefinitionService workflows) {
        this.workflows = workflows;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createWorkflow", summary = "Create a workflow definition (no participants or steps yet)")
    public DefinitionView create(@PathVariable UUID organizationId, AuthenticatedUser user, @Valid @RequestBody CreateRequest request) {
        return workflows.create(organizationId, user, request.name(), request.description(), request.maxSteps(),
                request.maxRetriesPerStep(), request.tokenBudget(), request.deadlineSeconds());
    }

    @GetMapping
    @Operation(operationId = "listWorkflows", summary = "Workflow definitions of the organization")
    public PageResponse<DefinitionView> list(@PathVariable UUID organizationId, AuthenticatedUser user,
            @RequestParam(required = false) WorkflowDefinitionStatus status, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(workflows.list(organizationId, user, status,
                PageParams.of(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")))), Function.identity());
    }

    @GetMapping("/{workflowId}")
    @Operation(operationId = "getWorkflow", summary = "One workflow definition with its participants and steps")
    public DefinitionView get(@PathVariable UUID organizationId, @PathVariable UUID workflowId, AuthenticatedUser user) {
        return workflows.get(organizationId, workflowId, user);
    }

    @PatchMapping(path = "/{workflowId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateWorkflow", summary = "Change the name, description or limits (a new version; runs in progress are unaffected)")
    public DefinitionView update(@PathVariable UUID organizationId, @PathVariable UUID workflowId, AuthenticatedUser user,
            @Valid @RequestBody UpdateRequest request) {
        return workflows.update(organizationId, workflowId, user, request.name(), request.description(), request.maxSteps(),
                request.maxRetriesPerStep(), request.tokenBudget(), request.deadlineSeconds());
    }

    @PutMapping(path = "/{workflowId}/participants", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "setWorkflowParticipants", summary = "Replace the participants: exactly the agents chosen here, never inherited from a team")
    public DefinitionView setParticipants(@PathVariable UUID organizationId, @PathVariable UUID workflowId, AuthenticatedUser user,
            @Valid @RequestBody ParticipantsRequest request) {
        return workflows.setParticipants(organizationId, workflowId, user, request.agentIds());
    }

    @PutMapping(path = "/{workflowId}/steps", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "setWorkflowSteps", summary = "Replace the ordered static steps (at most 8); every agent must be a participant")
    public DefinitionView setSteps(@PathVariable UUID organizationId, @PathVariable UUID workflowId, AuthenticatedUser user,
            @Valid @RequestBody StepsRequest request) {
        return workflows.setSteps(organizationId, workflowId, user, request.steps() == null ? List.of() : request.steps().stream()
                .map(s -> new WorkflowSpec.StepInput(s.name(), s.agentId(), s.instruction(), s.inputsFrom())).toList());
    }

    @PostMapping("/{workflowId}/archive")
    @Operation(operationId = "archiveWorkflow", summary = "Archive a definition (runs in progress keep going)")
    public DefinitionView archive(@PathVariable UUID organizationId, @PathVariable UUID workflowId, AuthenticatedUser user) {
        return workflows.archive(organizationId, workflowId, user);
    }
}
