package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.WorkflowViews.MessageView;
import com.aiworkforce.core.agent.WorkflowViews.RunView;
import com.aiworkforce.core.agent.WorkflowViews.StepView;
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
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Runs of multi-agent workflows: start (explicit, human-initiated), inspect, cancel. */
@RestController
@Validated
@RequestMapping(path = "/api/v1/organizations/{organizationId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Workflow runs", description = "Runs of deterministic multi-agent pipelines: steps, messages and cancellation")
public class WorkflowRunController {

    @Schema(name = "StartWorkflowRunRequest")
    public record StartRequest(@NotBlank @Size(max = 2000) String objective,
            @Schema(description = "Optional idempotency key: repeating it never creates a second run")
            @Size(min = 1, max = 64) String requestId) {
    }

    private final WorkflowRunService runs;

    public WorkflowRunController(WorkflowRunService runs) {
        this.runs = runs;
    }

    @PostMapping(path = "/workflows/{workflowId}/runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "startWorkflowRun", summary = "Start a run (202: it executes asynchronously; poll the run)")
    public RunView start(@PathVariable UUID organizationId, @PathVariable UUID workflowId, AuthenticatedUser user,
            @Valid @RequestBody StartRequest request) {
        return runs.start(organizationId, workflowId, user, request.objective(), request.requestId());
    }

    @GetMapping("/workflow-runs")
    @Operation(operationId = "listWorkflowRuns", summary = "Workflow runs of the organization, newest first")
    public PageResponse<RunView> list(@PathVariable UUID organizationId, AuthenticatedUser user,
            @RequestParam(required = false) WorkflowRunStatus status, @RequestParam(required = false) UUID workflowId,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(runs.list(organizationId, user, status, workflowId,
                PageParams.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))), Function.identity());
    }

    @GetMapping("/workflow-runs/{runId}")
    @Operation(operationId = "getWorkflowRun", summary = "One workflow run with its state, budget and result")
    public RunView get(@PathVariable UUID organizationId, @PathVariable UUID runId, AuthenticatedUser user) {
        return runs.get(organizationId, runId, user);
    }

    @GetMapping("/workflow-runs/{runId}/steps")
    @Operation(operationId = "listWorkflowRunSteps", summary = "Steps of a run in order; each is one agent execution")
    public PageResponse<StepView> steps(@PathVariable UUID organizationId, @PathVariable UUID runId, AuthenticatedUser user,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "50") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(runs.steps(organizationId, runId, user,
                PageParams.of(page, size, Sort.by(Sort.Order.asc("stepRank"), Sort.Order.asc("attempt")))), Function.identity());
    }

    @GetMapping("/workflow-runs/{runId}/messages")
    @Operation(operationId = "listWorkflowRunMessages", summary = "The append-only journal of a run, in order")
    public PageResponse<MessageView> messages(@PathVariable UUID organizationId, @PathVariable UUID runId, AuthenticatedUser user,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "50") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(runs.messages(organizationId, runId, user,
                PageParams.of(page, size, Sort.by(Sort.Order.asc("sequence")))), Function.identity());
    }

    @GetMapping("/workflow-runs/{runId}/actions")
    @Operation(operationId = "listWorkflowRunActions", summary = "The coordinator actions of a run (delegations and finish), accepted or rejected")
    public PageResponse<WorkflowViews.ActionView> actions(@PathVariable UUID organizationId, @PathVariable UUID runId,
            AuthenticatedUser user, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(runs.actions(organizationId, runId, user,
                PageParams.of(page, size, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")))), Function.identity());
    }

    @PostMapping("/workflow-runs/{runId}/cancel")
    @Operation(operationId = "cancelWorkflowRun",
            summary = "Request cooperative cancellation: nothing new starts; started steps are not undone")
    public RunView cancel(@PathVariable UUID organizationId, @PathVariable UUID runId, AuthenticatedUser user) {
        return runs.cancel(organizationId, runId, user);
    }
}
