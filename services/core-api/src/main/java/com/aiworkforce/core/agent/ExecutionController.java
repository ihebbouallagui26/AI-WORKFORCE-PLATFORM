package com.aiworkforce.core.agent;

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

/** Supervised executions of AI agents: launch (explicit, human-initiated), inspect and list. */
@RestController
@Validated
@RequestMapping(path = "/api/v1/organizations/{organizationId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Agent executions", description = "Human-initiated, supervised text-generation runs of an active AI agent")
public class ExecutionController {

    @Schema(name = "StartExecutionRequest")
    public record StartRequest(@NotBlank @Size(max = 8000) String task) {
    }

    private final ExecutionService executions;

    public ExecutionController(ExecutionService executions) {
        this.executions = executions;
    }

    @PostMapping(path = "/agents/{agentId}/executions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "startAgentExecution",
            summary = "Launch an execution (202: it runs asynchronously; poll the execution)")
    public ExecutionView start(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            AuthenticatedUser user, @Valid @RequestBody StartRequest request) {
        // 202 in every case, always with the execution id: if the hand-over was refused right away the
        // returned state is already FAILED (safe code), so the client never has to guess.
        return executions.current(organizationId, executions.start(organizationId, agentId, user, request.task()));
    }

    @GetMapping("/agents/{agentId}/executions")
    @Operation(operationId = "listAgentExecutions", summary = "Execution history of one agent, newest first")
    public PageResponse<ExecutionView> listForAgent(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            AuthenticatedUser user, @RequestParam(required = false) ExecutionStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(executions.list(organizationId, agentId, status, user,
                PageParams.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))), Function.identity());
    }

    @GetMapping("/executions")
    @Operation(operationId = "listExecutions", summary = "Execution history of the organization, newest first")
    public PageResponse<ExecutionView> list(@PathVariable UUID organizationId, AuthenticatedUser user,
            @RequestParam(required = false) UUID agentId, @RequestParam(required = false) ExecutionStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(executions.list(organizationId, agentId, status, user,
                PageParams.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))), Function.identity());
    }

    @GetMapping("/executions/{executionId}")
    @Operation(operationId = "getExecution", summary = "One execution with its result")
    public ExecutionView get(@PathVariable UUID organizationId, @PathVariable UUID executionId, AuthenticatedUser user) {
        return executions.get(organizationId, executionId, user);
    }
}
