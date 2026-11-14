package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.ToolViews.AvailableTool;
import com.aiworkforce.core.agent.ToolViews.InvocationView;
import com.aiworkforce.core.common.web.PageParams;
import com.aiworkforce.core.common.web.PageResponse;
import com.aiworkforce.core.identity.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tool activity of AI agent executions: timeline, history and the tools an agent can use. Read only. */
@RestController
@Validated
@RequestMapping(path = "/api/v1/organizations/{organizationId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Agent tools", description = "Tool calls requested by AI agents and the tools granted to them")
public class ToolActivityController {

    private final ToolActivityService activity;

    public ToolActivityController(ToolActivityService activity) {
        this.activity = activity;
    }

    @GetMapping("/executions/{executionId}/tool-calls")
    @Operation(operationId = "getExecutionToolTimeline", summary = "Tool calls of one execution, in order")
    public PageResponse<InvocationView> timeline(@PathVariable UUID organizationId, @PathVariable UUID executionId,
            AuthenticatedUser user, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(activity.timeline(organizationId, executionId, user,
                PageParams.of(page, size, Sort.by(Sort.Order.asc("sequence")))), Function.identity());
    }

    @GetMapping("/tool-invocations")
    @Operation(operationId = "listToolInvocations", summary = "Tool call history of the organization, newest first")
    public PageResponse<InvocationView> history(@PathVariable UUID organizationId, AuthenticatedUser user,
            @RequestParam(required = false) UUID agentId, @RequestParam(required = false) InvocationStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(activity.history(organizationId, agentId, status, user,
                PageParams.of(page, size, Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id")))), Function.identity());
    }

    @GetMapping("/agents/{agentId}/available-tools")
    @Operation(operationId = "listAvailableAgentTools",
            summary = "Tools granted to the agent and what the platform would do with a call (automatic, approval, denied)")
    public List<AvailableTool> availableTools(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            AuthenticatedUser user) {
        return activity.availableTools(organizationId, agentId, user);
    }
}
